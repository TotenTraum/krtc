// Apple platform pieces: libwebrtc's RTCDefaultVideo{En,De}coderFactory (VideoToolbox H.264/H.265, libvpx VP8/VP9,
// AV1) bridged to the C++ factory interfaces, and camera capture through RTCCameraVideoCapturer (AVFoundation).
#include "krtc_platform.h"

#include <TargetConditionals.h>
#include <atomic>
#include <future>
#include <chrono>
#include <thread>

#import <AVFoundation/AVFoundation.h>
#if TARGET_OS_OSX
#import <CoreGraphics/CoreGraphics.h>
#include "modules/desktop_capture/desktop_capture_options.h"
#include "modules/desktop_capture/desktop_capturer.h"
#include "modules/desktop_capture/desktop_frame.h"
#else
#import <ReplayKit/ReplayKit.h>
#import "sdk/objc/components/video_frame_buffer/RTCCVPixelBuffer.h"
#endif
#include "rtc_base/time_utils.h"

#import "sdk/objc/base/RTCVideoCapturer.h"
#import "sdk/objc/base/RTCVideoFrame.h"
#import "sdk/objc/components/capturer/RTCCameraVideoCapturer.h"
#import "sdk/objc/components/video_codec/RTCDefaultVideoDecoderFactory.h"
#import "sdk/objc/components/video_codec/RTCDefaultVideoEncoderFactory.h"
#include "sdk/objc/native/api/video_decoder_factory.h"
#include "sdk/objc/native/api/video_encoder_factory.h"
#include "sdk/objc/native/api/video_frame_buffer.h"

/** Receives captured frames and pushes them, as native frames, into a krtc::PushVideoSource. */
@interface KrtcCaptureSink : NSObject <RTCVideoCapturerDelegate>
- (instancetype)initWithSource:(krtc::PushVideoSource *)source;
@end

@implementation KrtcCaptureSink {
    webrtc::scoped_refptr<krtc::PushVideoSource> _source;
}

- (instancetype)initWithSource:(krtc::PushVideoSource *)source {
    if (self = [super init]) _source = webrtc::scoped_refptr<krtc::PushVideoSource>(source);
    return self;
}

- (void)capturer:(RTCVideoCapturer *)capturer didCaptureVideoFrame:(RTCVideoFrame *)frame {
    _source->Push(webrtc::VideoFrame::Builder()
                      .set_video_frame_buffer(webrtc::ObjCToNativeVideoFrameBuffer(frame.buffer))
                      .set_rotation(static_cast<webrtc::VideoRotation>(frame.rotation))
                      .set_timestamp_us(frame.timeStampNs / 1000)
                      .build());
}
@end

namespace krtc {

std::unique_ptr<webrtc::VideoEncoderFactory> PlatformVideoEncoderFactory() {
    return webrtc::ObjCToNativeVideoEncoderFactory([[RTCDefaultVideoEncoderFactory alloc] init]);
}

std::unique_ptr<webrtc::VideoDecoderFactory> PlatformVideoDecoderFactory() {
    return webrtc::ObjCToNativeVideoDecoderFactory([[RTCDefaultVideoDecoderFactory alloc] init]);
}

int CameraCount() { return static_cast<int>([RTCCameraVideoCapturer captureDevices].count); }

std::string CameraName(int index) {
    NSArray<AVCaptureDevice *> *devices = [RTCCameraVideoCapturer captureDevices];
    if (index < 0 || index >= static_cast<int>(devices.count)) return "";
    return std::string(devices[index].localizedName.UTF8String ?: "");
}

/**
 * Whether the process may use the camera. Asks the user once (the OS prompt; the calling thread waits for the
 * answer, so do not call this on the main thread of an app) and remembers the answer.
 */
static bool CameraAuthorized() {
    switch ([AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo]) {
        case AVAuthorizationStatusAuthorized: return true;
        case AVAuthorizationStatusNotDetermined: {
            __block bool granted = false;
            dispatch_semaphore_t done = dispatch_semaphore_create(0);
            [AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo completionHandler:^(BOOL ok) {
                granted = ok;
                dispatch_semaphore_signal(done);
            }];
            dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 60 * NSEC_PER_SEC));
            return granted;
        }
        default: return false; // denied or restricted
    }
}

std::function<void()> StartCamera(int index, int width, int height, int fps, PushVideoSource *source) {
    if (!CameraAuthorized()) return nullptr;
    NSArray<AVCaptureDevice *> *devices = [RTCCameraVideoCapturer captureDevices];
    if (index < 0 || index >= static_cast<int>(devices.count)) return nullptr;
    AVCaptureDevice *device = devices[index];
    // The format closest to the requested size.
    AVCaptureDeviceFormat *best = nil;
    long bestScore = LONG_MAX;
    for (AVCaptureDeviceFormat *format in [RTCCameraVideoCapturer supportedFormatsForDevice:device]) {
        CMVideoDimensions d = CMVideoFormatDescriptionGetDimensions(format.formatDescription);
        long score = labs(static_cast<long>(d.width) - width) + labs(static_cast<long>(d.height) - height);
        if (score < bestScore) {
            best = format;
            bestScore = score;
        }
    }
    if (!best) return nullptr;
    double maxFps = 0;
    for (AVFrameRateRange *range in best.videoSupportedFrameRateRanges) maxFps = MAX(maxFps, range.maxFrameRate);
    KrtcCaptureSink *sink = [[KrtcCaptureSink alloc] initWithSource:source];
    RTCCameraVideoCapturer *capturer = [[RTCCameraVideoCapturer alloc] initWithDelegate:sink];
    [capturer startCaptureWithDevice:device format:best fps:static_cast<NSInteger>(MIN(static_cast<double>(fps), maxFps))];
    // The lambda keeps the capturer and its delegate alive until the capture is stopped.
    return [capturer, sink]() {
        (void)sink;
        [capturer stopCapture];
    };
}

#if TARGET_OS_OSX
// ---------------------------------------------------------------- screen capture: macOS

namespace {

webrtc::DesktopCaptureOptions ScreenOptions() {
    webrtc::DesktopCaptureOptions options = webrtc::DesktopCaptureOptions::CreateDefault();
    options.set_allow_sck_capturer(true); // ScreenCaptureKit (macOS 14+); CGDisplayStream is gone in newer macOS
    return options;
}

std::unique_ptr<webrtc::DesktopCapturer> CreateCapturer(bool window) {
    return window ? webrtc::DesktopCapturer::CreateWindowCapturer(ScreenOptions()) : webrtc::DesktopCapturer::CreateScreenCapturer(ScreenOptions());
}

/** Screen Recording permission; the first call shows the system prompt (granted only for later launches). */
bool ScreenAuthorized() {
    if (CGPreflightScreenCaptureAccess()) return true;
    CGRequestScreenCaptureAccess();
    return false;
}

/** Owns a DesktopCapturer on its own thread: creation, every CaptureFrame and destruction happen there. */
class ScreenCapture : public webrtc::DesktopCapturer::Callback {
public:
    ScreenCapture(int64_t id, bool window, int fps, int maxWidth, int maxHeight, krtc::PushVideoSource *source)
        : source_(source), maxWidth_(maxWidth), maxHeight_(maxHeight) {
        std::promise<bool> started;
        auto ok = started.get_future();
        thread_ = std::thread([this, id, window, fps, &started] {
            auto capturer = CreateCapturer(window);
            bool selected = capturer && capturer->SelectSource(static_cast<webrtc::DesktopCapturer::SourceId>(id));
            started.set_value(selected);
            if (!selected) return;
            capturer->Start(this);
            const auto period = std::chrono::microseconds(1000000 / fps);
            auto next = std::chrono::steady_clock::now();
            while (running_) {
                capturer->CaptureFrame();
                next += period;
                std::this_thread::sleep_until(next);
                if (std::chrono::steady_clock::now() > next + period) next = std::chrono::steady_clock::now(); // fell behind
            }
        });
        ok_ = ok.get();
    }
    ~ScreenCapture() override {
        running_ = false;
        thread_.join();
    }
    bool ok() const { return ok_; }

    void OnCaptureResult(webrtc::DesktopCapturer::Result result, std::unique_ptr<webrtc::DesktopFrame> frame) override {
        if (result != webrtc::DesktopCapturer::Result::SUCCESS || !frame) return;
        krtc::PushBgra(source_.get(), frame->data(), frame->stride(), frame->size().width(), frame->size().height(), maxWidth_, maxHeight_, 0);
    }

private:
    webrtc::scoped_refptr<krtc::PushVideoSource> source_;
    int maxWidth_;
    int maxHeight_;
    std::atomic<bool> running_{true};
    bool ok_ = false;
    std::thread thread_;
};

}  // namespace

std::vector<ScreenSource> ScreenSources() {
    std::vector<ScreenSource> out;
    if (!CGPreflightScreenCaptureAccess()) return out; // listing windows needs the permission too
    for (bool window : {false, true}) {
        // Listing also runs on a thread of its own (ScreenCaptureKit answers asynchronously on other queues).
        std::thread([&] {
            auto capturer = CreateCapturer(window);
            webrtc::DesktopCapturer::SourceList list;
            if (!capturer || !capturer->GetSourceList(&list)) return;
            int index = 0;
            for (const auto &s : list) {
                std::string title = s.title.empty() ? (window ? "Window " : "Display ") + std::to_string(++index) : s.title;
                out.push_back({static_cast<int64_t>(s.id), window, title});
            }
        }).join();
    }
    return out;
}

std::function<void()> StartScreen(int64_t id, bool window, int fps, int maxWidth, int maxHeight, PushVideoSource *source) {
    if (!ScreenAuthorized()) return nullptr;
    auto capture = std::make_shared<ScreenCapture>(id, window, fps, maxWidth, maxHeight, source);
    if (!capture->ok()) return nullptr;
    return [capture]() mutable { capture.reset(); };
}

#else
// ---------------------------------------------------------------- screen capture: iOS (ReplayKit, this app only)

std::vector<ScreenSource> ScreenSources() {
    if (!RPScreenRecorder.sharedRecorder.available) return {};
    return {{0, false, "This app"}};
}

std::function<void()> StartScreen(int64_t, bool, int fps, int, int, PushVideoSource *source) {
    RPScreenRecorder *recorder = RPScreenRecorder.sharedRecorder;
    if (!recorder.available) return nullptr;
    webrtc::scoped_refptr<PushVideoSource> target(source);
    auto lastUs = std::make_shared<std::atomic<int64_t>>(0);
    const int64_t periodUs = 1000000 / fps;
    __block bool failed = false;
    dispatch_semaphore_t started = dispatch_semaphore_create(0);
    [recorder startCaptureWithHandler:^(CMSampleBufferRef sample, RPSampleBufferType type, NSError *error) {
        if (error || type != RPSampleBufferTypeVideo) return;
        CVPixelBufferRef pixels = CMSampleBufferGetImageBuffer(sample);
        if (!pixels) return;
        const int64_t now = webrtc::TimeMicros();
        if (now - lastUs->load() < periodUs) return; // ReplayKit delivers up to 60 fps
        lastUs->store(now);
        RTCCVPixelBuffer *buffer = [[RTCCVPixelBuffer alloc] initWithPixelBuffer:pixels];
        target->Push(webrtc::VideoFrame::Builder()
                         .set_video_frame_buffer(webrtc::ObjCToNativeVideoFrameBuffer(buffer))
                         .set_timestamp_us(now)
                         .build());
    } completionHandler:^(NSError *error) {
        failed = error != nil;
        dispatch_semaphore_signal(started);
    }];
    dispatch_semaphore_wait(started, dispatch_time(DISPATCH_TIME_NOW, 60 * NSEC_PER_SEC)); // the user answers a prompt
    if (failed) return nullptr;
    return [target]() { [RPScreenRecorder.sharedRecorder stopCaptureWithHandler:nil]; };
}
#endif

}  // namespace krtc
