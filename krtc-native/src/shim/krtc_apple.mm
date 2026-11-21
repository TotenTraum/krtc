// Apple platform pieces: libwebrtc's RTCDefaultVideo{En,De}coderFactory (VideoToolbox H.264/H.265, libvpx VP8/VP9,
// AV1) bridged to the C++ factory interfaces, and camera capture through RTCCameraVideoCapturer (AVFoundation).
#include "krtc_platform.h"

#include <TargetConditionals.h>
#include <atomic>
#include <future>
#include <chrono>
#include <thread>

#import <AVFoundation/AVFoundation.h>
#import "sdk/objc/components/video_frame_buffer/RTCCVPixelBuffer.h"
#include <mutex>
#include <vector>
#include <unistd.h>
#if TARGET_OS_OSX
#import <AppKit/AppKit.h>
#import <CoreGraphics/CoreGraphics.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#include "krtc_sck_sources.h"
#include "modules/desktop_capture/desktop_capture_options.h"
#include "modules/desktop_capture/desktop_capturer.h"
#include "modules/desktop_capture/desktop_frame.h"
#include "modules/desktop_capture/mac/desktop_frame_utils.h"
#include "libyuv/scale_argb.h"
#else
#import <ReplayKit/ReplayKit.h>
#endif
#include "rtc_base/time_utils.h"
#include "rtc_base/event.h"

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

namespace krtc {
/** Pushes the PCM of an audio sample buffer (float or 16-bit integer, interleaved or not, either byte order). */
static void PushSampleBuffer(PushAudioSource *source, CMSampleBufferRef sample) {
    CMAudioFormatDescriptionRef format = CMSampleBufferGetFormatDescription(sample);
    const AudioStreamBasicDescription *d = format ? CMAudioFormatDescriptionGetStreamBasicDescription(format) : nullptr;
    if (!d || d->mFormatID != kAudioFormatLinearPCM) return;
    const size_t frames = CMSampleBufferGetNumSamples(sample);
    const int channels = static_cast<int>(d->mChannelsPerFrame);
    const bool isFloat = d->mFormatFlags & kAudioFormatFlagIsFloat;
    const bool planar = d->mFormatFlags & kAudioFormatFlagIsNonInterleaved;
    const bool bigEndian = d->mFormatFlags & kAudioFormatFlagIsBigEndian;
    if (frames == 0 || channels <= 0 || (!isFloat && d->mBitsPerChannel != 16) || (isFloat && d->mBitsPerChannel != 32)) return;
    size_t size = 0;
    CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(sample, &size, nullptr, 0, nullptr, nullptr, 0, nullptr);
    std::vector<uint8_t> storage(size);
    auto *list = reinterpret_cast<AudioBufferList *>(storage.data());
    CMBlockBufferRef block = nullptr;
    if (CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(sample, nullptr, list, size, nullptr, nullptr,
                                                                kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment, &block) != noErr) return;
    std::vector<int16_t> pcm(frames * channels);
    for (size_t f = 0; f < frames; f++) {
        for (int c = 0; c < channels; c++) {
            const AudioBuffer &b = list->mBuffers[planar ? c : 0];
            const size_t index = planar ? f : f * channels + c;
            if (planar && static_cast<UInt32>(c) >= list->mNumberBuffers) continue;
            int16_t v;
            if (isFloat) {
                v = static_cast<int16_t>(std::max(-1.0f, std::min(1.0f, static_cast<const float *>(b.mData)[index])) * 32767.0f);
            } else {
                uint16_t raw = static_cast<const uint16_t *>(b.mData)[index];
                v = static_cast<int16_t>(bigEndian ? static_cast<uint16_t>((raw >> 8) | (raw << 8)) : raw);
            }
            pcm[f * channels + c] = v;
        }
    }
    if (block) CFRelease(block);
    source->Push(pcm.data(), frames, channels, static_cast<int>(d->mSampleRate));
}
}  // namespace krtc

#if TARGET_OS_OSX
/** Receives ScreenCaptureKit's audio (and ignores its video: an audio-only stream still produces frames). */
API_AVAILABLE(macos(13.0))
@interface KrtcSystemAudioOutput : NSObject <SCStreamOutput>
- (instancetype)initWithSource:(krtc::PushAudioSource *)source;
@end

@implementation KrtcSystemAudioOutput {
    webrtc::scoped_refptr<krtc::PushAudioSource> _source;
}
- (instancetype)initWithSource:(krtc::PushAudioSource *)source {
    if (self = [super init]) _source = webrtc::scoped_refptr<krtc::PushAudioSource>(source);
    return self;
}
- (void)stream:(SCStream *)stream didOutputSampleBuffer:(CMSampleBufferRef)sample ofType:(SCStreamOutputType)type {
    if (type == SCStreamOutputTypeAudio) krtc::PushSampleBuffer(_source.get(), sample);
}
@end
#endif

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

API_AVAILABLE(macos(13.0)) SCShareableContent *ShareableContent(bool cached = false);

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
        ok_ = ok.get() && first_frame_.Wait(webrtc::TimeDelta::Seconds(3)) && received_frame_.load();
    }
    ~ScreenCapture() override {
        running_ = false;
        thread_.join();
    }
    bool ok() const { return ok_; }

    void OnCaptureResult(webrtc::DesktopCapturer::Result result, std::unique_ptr<webrtc::DesktopFrame> frame) override {
        if (result != webrtc::DesktopCapturer::Result::SUCCESS || !frame) {
            if (result == webrtc::DesktopCapturer::Result::ERROR_PERMANENT) first_frame_.Set();
            return;
        }
        krtc::PushBgra(source_.get(), frame->data(), frame->stride(), frame->size().width(), frame->size().height(), maxWidth_, maxHeight_, 0);
        received_frame_ = true;
        first_frame_.Set();
    }

private:
    webrtc::scoped_refptr<krtc::PushVideoSource> source_;
    int maxWidth_;
    int maxHeight_;
    std::atomic<bool> running_{true};
    std::atomic<bool> received_frame_{false};
    webrtc::Event first_frame_;
    bool ok_ = false;
    std::thread thread_;
};

}  // namespace

std::vector<ScreenSource> ScreenSources() {
    std::vector<ScreenSource> out;
    if (!CGPreflightScreenCaptureAccess()) return out; // listing windows needs the permission too
    if (@available(macOS 13.0, *)) {
        // Use the same API and IDs as StartSckScreen. DesktopCapturer's SCK backend only supplies a source for
        // the system picker and can otherwise return an empty display list. Its legacy window list also omits
        // untitled/custom windows (including some browsers and game-streaming clients).
        SCShareableContent *content = ShareableContent();
        if (!content) return out;
        std::unordered_set<pid_t> applications;
        for (NSRunningApplication *app in NSWorkspace.sharedWorkspace.runningApplications) {
            if (app.terminated) continue;
            // Regular GUI apps include Finder and apps on other Spaces. Also allow user menu-bar apps
            // with windows; exclude background-only processes and Apple's system presentation agents.
            const bool regular = app.activationPolicy == NSApplicationActivationPolicyRegular;
            const bool userAccessory = app.activationPolicy == NSApplicationActivationPolicyAccessory &&
                [app.bundleURL.path.pathExtension isEqualToString:@"app"] &&
                ![app.bundleURL.path hasPrefix:@"/System/Library/"];
            if (regular || userAccessory) applications.insert(app.processIdentifier);
        }
        NSArray<NSDictionary *> *info = CFBridgingRelease(CGWindowListCopyWindowInfo(kCGWindowListOptionAll, kCGNullWindowID));
        return SckSources(content, getpid(), applications, info);
    }
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

namespace {
/** ScreenCaptureKit's shareable content, waited for (it answers on another queue). */
API_AVAILABLE(macos(13.0)) SCShareableContent *ShareableContent(bool cached) {
    static std::mutex cacheLock;
    static SCShareableContent *lastContent = nil;
    static std::chrono::steady_clock::time_point lastListed;
    if (cached) {
        std::lock_guard<std::mutex> lock(cacheLock);
        if (lastContent && std::chrono::steady_clock::now() - lastListed < std::chrono::seconds(2)) return lastContent;
    }
    __block SCShareableContent *content = nil;
    dispatch_semaphore_t listed = dispatch_semaphore_create(0);
    // Include windows on other Spaces and full-screen apps. Do not require a title: it is optional in SCK.
    [SCShareableContent getShareableContentExcludingDesktopWindows:YES onScreenWindowsOnly:NO completionHandler:^(SCShareableContent *c, NSError *error) {
        if (error) NSLog(@"krtc: listing screen sources failed: %@", error);
        content = c;
        dispatch_semaphore_signal(listed);
    }];
    if (dispatch_semaphore_wait(listed, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)) != 0) {
        NSLog(@"krtc: listing screen sources timed out");
        return nil;
    }
    if (content) {
        std::lock_guard<std::mutex> lock(cacheLock);
        lastContent = content;
        lastListed = std::chrono::steady_clock::now();
    }
    return content;
}

/** Captures the sound [filter] lets through (never our own process: no echo of the call); video kept tiny and slow. */
API_AVAILABLE(macos(13.0)) std::function<void()> StartFilteredAudio(PushAudioSource *source, SCContentFilter *filter) {
    SCStreamConfiguration *config = [[SCStreamConfiguration alloc] init];
    config.capturesAudio = YES;
    config.excludesCurrentProcessAudio = YES;
    config.sampleRate = 48000;
    config.channelCount = 2;
    config.width = 2;
    config.height = 2;
    config.minimumFrameInterval = CMTimeMake(1, 1);
    SCStream *stream = [[SCStream alloc] initWithFilter:filter configuration:config delegate:nil];
    KrtcSystemAudioOutput *output = [[KrtcSystemAudioOutput alloc] initWithSource:source];
    dispatch_queue_t queue = dispatch_queue_create("krtc.system-audio", DISPATCH_QUEUE_SERIAL);
    NSError *error = nil;
    if (![stream addStreamOutput:output type:SCStreamOutputTypeAudio sampleHandlerQueue:queue error:&error]) return nullptr;
    [stream addStreamOutput:output type:SCStreamOutputTypeScreen sampleHandlerQueue:queue error:nil];
    __block bool started = false;
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    [stream startCaptureWithCompletionHandler:^(NSError *e) {
        started = e == nil;
        dispatch_semaphore_signal(done);
    }];
    dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC));
    if (!started) return nullptr;
    return [stream, output]() {
        (void)output;
        [stream stopCaptureWithCompletionHandler:nil];
    };
}
}  // namespace

std::function<void()> StartSystemAudio(PushAudioSource *source) {
    if (@available(macOS 13.0, *)) {
        if (!ScreenAuthorized()) return nullptr;
        SCDisplay *display = ShareableContent().displays.firstObject;
        if (!display) return nullptr;
        // The whole system's sound.
        return StartFilteredAudio(source, [[SCContentFilter alloc] initWithDisplay:display excludingWindows:@[]]);
    }
    return nullptr;
}

std::function<void()> StartWindowAudio(PushAudioSource *source, int64_t window) {
    if (@available(macOS 13.0, *)) {
        if (!ScreenAuthorized()) return nullptr;
        SCShareableContent *content = ShareableContent();
        SCDisplay *display = content.displays.firstObject;
        SCRunningApplication *app = nil;
        for (SCWindow *w in content.windows) {
            if (static_cast<int64_t>(w.windowID) == window) {
                app = w.owningApplication;
                break;
            }
        }
        if (!display || !app) return nullptr;
        // Audio follows the filter's applications: only the window's application is heard.
        return StartFilteredAudio(source, [[SCContentFilter alloc] initWithDisplay:display includingApplications:@[app] exceptingWindows:@[]]);
    }
    return nullptr;
}

}  // namespace krtc

/**
 * Screen and window frames from ScreenCaptureKit: NV12 pixel buffers the system already scaled on the GPU, passed on
 * without a copy. ScreenCaptureKit sends nothing while the picture stands still, so the last frame goes out again
 * every half second (an encoder needs input to answer a key frame request from someone who just started watching).
 */
API_AVAILABLE(macos(12.3))
@interface KrtcScreenOutput : NSObject <SCStreamOutput, SCStreamDelegate>
- (instancetype)initWithSource:(webrtc::scoped_refptr<krtc::PushVideoSource>)source;
- (void)repeatIfIdle;
- (BOOL)waitForFirstFrame;
@end

@implementation KrtcScreenOutput {
    webrtc::scoped_refptr<krtc::PushVideoSource> _source;
    std::mutex _lock;
    RTCCVPixelBuffer *_last;
    int64_t _lastPushUs;
    dispatch_semaphore_t _firstFrame;
}

- (instancetype)initWithSource:(webrtc::scoped_refptr<krtc::PushVideoSource>)source {
    if (self = [super init]) {
        _source = source;
        _firstFrame = dispatch_semaphore_create(0);
    }
    return self;
}

- (void)push:(RTCCVPixelBuffer *)buffer {
    const int64_t now = webrtc::TimeMicros();
    {
        std::lock_guard<std::mutex> g(_lock);
        if (!_last) dispatch_semaphore_signal(_firstFrame);
        _last = buffer;
        _lastPushUs = now;
    }
    _source->Push(webrtc::VideoFrame::Builder().set_video_frame_buffer(webrtc::ObjCToNativeVideoFrameBuffer(buffer)).set_timestamp_us(now).build());
}

- (BOOL)waitForFirstFrame {
    if (dispatch_semaphore_wait(_firstFrame, dispatch_time(DISPATCH_TIME_NOW, 3 * NSEC_PER_SEC)) != 0) return NO;
    std::lock_guard<std::mutex> g(_lock);
    return _last != nil;
}

- (void)stream:(SCStream *)stream didStopWithError:(NSError *)error {
    NSLog(@"krtc: screen capture stopped: %@", error);
    dispatch_semaphore_signal(_firstFrame);
}

- (void)stream:(SCStream *)stream didOutputSampleBuffer:(CMSampleBufferRef)sample ofType:(SCStreamOutputType)type {
    if (type != SCStreamOutputTypeScreen || !CMSampleBufferIsValid(sample)) return;
    CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sample, false);
    if (attachments && CFArrayGetCount(attachments) > 0) {
        NSDictionary *info = (__bridge NSDictionary *)CFArrayGetValueAtIndex(attachments, 0);
        NSNumber *status = info[SCStreamFrameInfoStatus];
        if (status && status.integerValue != SCFrameStatusComplete) {
            [self repeatIfIdle];
            return;
        }
    }
    CVPixelBufferRef pixels = CMSampleBufferGetImageBuffer(sample);
    if (!pixels) return;
    [self push:[[RTCCVPixelBuffer alloc] initWithPixelBuffer:pixels]];
}

- (void)repeatIfIdle {
    RTCCVPixelBuffer *last;
    {
        std::lock_guard<std::mutex> g(_lock);
        if (!_last || webrtc::TimeMicros() - _lastPushUs < 500000) return;
        last = _last;
    }
    [self push:last];
}
@end

namespace krtc {
namespace {
/** Even dimensions of [w]×[h] scaled down to fit [maxW]×[maxH] (0: no limit), never up. */
std::pair<int, int> Fit(double w, double h, int maxW, int maxH) {
    double scale = 1.0;
    if (maxW > 0) scale = std::min(scale, maxW / w);
    if (maxH > 0) scale = std::min(scale, maxH / h);
    return {std::max(2, static_cast<int>(w * scale)) & ~1, std::max(2, static_cast<int>(h * scale)) & ~1};
}

/** Pixels per point of [display] (2 on Retina), from its current mode. */
double BackingScale(CGDirectDisplayID display) {
    CGDisplayModeRef mode = CGDisplayCopyDisplayMode(display);
    if (!mode) return 1.0;
    const size_t points = CGDisplayModeGetWidth(mode), pixels = CGDisplayModeGetPixelWidth(mode);
    CGDisplayModeRelease(mode);
    return points > 0 && pixels > 0 ? static_cast<double>(pixels) / points : 1.0;
}

/** Captures display or window [id] with ScreenCaptureKit; nullptr if it cannot (then the DesktopCapturer is used). */
API_AVAILABLE(macos(13.0)) std::function<void()> StartSckScreen(int64_t id, bool window, int fps, int maxWidth, int maxHeight, PushVideoSource *source) {
    SCShareableContent *content = ShareableContent();
    if (!content) return nullptr;
    SCContentFilter *filter = nil;
    double pixelsW = 0, pixelsH = 0;
    CGDirectDisplayID display = CGMainDisplayID();
    if (window) {
        for (SCWindow *w in content.windows) {
            if (static_cast<int64_t>(w.windowID) != id) continue;
            filter = [[SCContentFilter alloc] initWithDesktopIndependentWindow:w];
            pixelsW = w.frame.size.width;
            pixelsH = w.frame.size.height;
            break;
        }
    } else {
        for (SCDisplay *d in content.displays) {
            // A null ScreenSource is -1 at the C ABI: capture the main display, not a nonexistent display -1.
            if (static_cast<int64_t>(d.displayID) != (id < 0 ? static_cast<int64_t>(CGMainDisplayID()) : id)) continue;
            filter = [[SCContentFilter alloc] initWithDisplay:d excludingWindows:@[]];
            display = d.displayID;
            pixelsW = CGDisplayBounds(d.displayID).size.width;
            pixelsH = CGDisplayBounds(d.displayID).size.height;
            break;
        }
    }
    if (!filter || pixelsW <= 0 || pixelsH <= 0) return nullptr;
    // Sizes above are in points; Retina screens have twice the pixels. Ask for the backing size, scaled to the limit.
    const double backing = BackingScale(display);
    auto [width, height] = Fit(pixelsW * backing, pixelsH * backing, maxWidth, maxHeight);
    SCStreamConfiguration *config = [[SCStreamConfiguration alloc] init];
    config.width = width;
    config.height = height;
    config.minimumFrameInterval = CMTimeMake(1, std::max(1, fps));
    config.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange; // NV12: what encoders take
    config.queueDepth = 6;
    config.showsCursor = YES;
    webrtc::scoped_refptr<PushVideoSource> target(source);
    KrtcScreenOutput *output = [[KrtcScreenOutput alloc] initWithSource:target];
    SCStream *stream = [[SCStream alloc] initWithFilter:filter configuration:config delegate:output];
    dispatch_queue_t queue = dispatch_queue_create("krtc.screen", DISPATCH_QUEUE_SERIAL);
    NSError *error = nil;
    if (![stream addStreamOutput:output type:SCStreamOutputTypeScreen sampleHandlerQueue:queue error:&error]) {
        NSLog(@"krtc: adding screen output failed: %@", error);
        return nullptr;
    }
    __block bool started = false;
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    [stream startCaptureWithCompletionHandler:^(NSError *e) {
        if (e) NSLog(@"krtc: starting screen capture failed: %@", e);
        started = e == nil;
        dispatch_semaphore_signal(done);
    }];
    const bool completed = dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)) == 0;
    if (!completed || !started || ![output waitForFirstFrame]) {
        NSLog(@"krtc: screen capture did not deliver its first frame (id=%lld, window=%d)", static_cast<long long>(id), window);
        [stream stopCaptureWithCompletionHandler:nil];
        return nullptr;
    }
    // A still picture still goes out twice a second (see KrtcScreenOutput).
    dispatch_source_t timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, queue);
    dispatch_source_set_timer(timer, dispatch_time(DISPATCH_TIME_NOW, 0), 250 * NSEC_PER_MSEC, 50 * NSEC_PER_MSEC);
    dispatch_source_set_event_handler(timer, ^{ [output repeatIfIdle]; });
    dispatch_resume(timer);
    return [stream, output, timer]() {
        (void)output;
        dispatch_source_cancel(timer);
        dispatch_semaphore_t stopped = dispatch_semaphore_create(0);
        [stream stopCaptureWithCompletionHandler:^(NSError *) { dispatch_semaphore_signal(stopped); }];
        dispatch_semaphore_wait(stopped, dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC));
    };
}
}  // namespace

int ScreenThumbnail(int64_t id, bool window, int maxWidth, int maxHeight, uint32_t *argb, int *width, int *height) {
    if (@available(macOS 14.0, *)) {
        if (!CGPreflightScreenCaptureAccess()) return -1;
        SCShareableContent *content = ShareableContent(true);
        if (!content) return -1;
        SCContentFilter *filter = nil;
        CGSize size = CGSizeZero;
        if (window) {
            for (SCWindow *w in content.windows) {
                if (static_cast<int64_t>(w.windowID) != id) continue;
                filter = [[SCContentFilter alloc] initWithDesktopIndependentWindow:w];
                size = w.frame.size;
                break;
            }
        } else {
            for (SCDisplay *d in content.displays) {
                if (static_cast<int64_t>(d.displayID) != (id < 0 ? static_cast<int64_t>(CGMainDisplayID()) : id)) continue;
                filter = [[SCContentFilter alloc] initWithDisplay:d excludingWindows:@[]];
                size = d.frame.size;
                break;
            }
        }
        if (!filter || size.width <= 0 || size.height <= 0) return -1;
        auto [w, h] = Fit(size.width, size.height, maxWidth, maxHeight);
        SCStreamConfiguration *config = [[SCStreamConfiguration alloc] init];
        config.width = w;
        config.height = h;
        config.showsCursor = NO;
        // The callback owns this result too: a late screenshot after timeout never writes to the caller's buffer.
        struct Snapshot {
            std::mutex lock;
            dispatch_semaphore_t done = dispatch_semaphore_create(0);
            CGImageRef image = nullptr;
            ~Snapshot() { if (image) CGImageRelease(image); }
        };
        auto result = std::make_shared<Snapshot>();
        static std::atomic<int> screenshotsInFlight{0};
        if (screenshotsInFlight.fetch_add(1) >= 8) {
            screenshotsInFlight.fetch_sub(1);
            return -1;
        }
        [SCScreenshotManager captureImageWithFilter:filter configuration:config completionHandler:^(CGImageRef image, NSError *error) {
            if (!error && image) {
                std::lock_guard<std::mutex> lock(result->lock);
                result->image = CGImageRetain(image);
            }
            screenshotsInFlight.fetch_sub(1);
            dispatch_semaphore_signal(result->done);
        }];
        if (dispatch_semaphore_wait(result->done, dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC)) != 0) return -1;
        std::unique_ptr<webrtc::DesktopFrame> frame;
        {
            std::lock_guard<std::mutex> lock(result->lock);
            if (!result->image) return -1;
            frame = webrtc::CreateDesktopFrameFromCGImage(
                webrtc::ScopedCFTypeRef<CGImageRef>(result->image, webrtc::RetainPolicy::RETAIN));
        }
        if (!frame || frame->size().width() <= 0 || frame->size().height() <= 0) return -1;
        auto [outW, outH] = Fit(frame->size().width(), frame->size().height(), maxWidth, maxHeight);
        if (libyuv::ARGBScale(frame->data(), frame->stride(), frame->size().width(), frame->size().height(),
            reinterpret_cast<uint8_t *>(argb), outW * 4, outW, outH, libyuv::kFilterBilinear) != 0) return -1;
        *width = outW;
        *height = outH;
        return 0;
    }
    return -2;
}

std::function<void()> StartScreen(int64_t id, bool window, int fps, int maxWidth, int maxHeight, PushVideoSource *source, bool) {
    if (!ScreenAuthorized()) return nullptr;
    // ScreenCaptureKit: scaled on the GPU, any frame rate the display gives; windows otherwise come through
    // CGWindowListCreateImage, a few frames a second for a large window.
    if (@available(macOS 13.0, *)) {
        if (auto stop = StartSckScreen(id, window, fps, maxWidth, maxHeight, source)) return stop;
    }
    auto capture = std::make_shared<ScreenCapture>(id, window, fps, maxWidth, maxHeight, source);
    if (!capture->ok()) return nullptr;
    return [capture]() mutable { capture.reset(); };
}

#else
// ---------------------------------------------------------------- screen capture: iOS (ReplayKit, this app only)

// The app's sound arrives with the ReplayKit screen capture; StartSystemAudio only says where to push it.
static std::mutex replayAudioLock;
static webrtc::scoped_refptr<PushAudioSource> replayAudio;

std::function<void()> StartWindowAudio(PushAudioSource *, int64_t) { return nullptr; }

std::function<void()> StartSystemAudio(PushAudioSource *source) {
    if (!RPScreenRecorder.sharedRecorder.available) return nullptr;
    webrtc::scoped_refptr<PushAudioSource> target(source);
    {
        std::lock_guard<std::mutex> g(replayAudioLock);
        replayAudio = target;
    }
    return [target]() {
        std::lock_guard<std::mutex> g(replayAudioLock);
        if (replayAudio == target) replayAudio = nullptr;
    };
}

std::vector<ScreenSource> ScreenSources() {
    if (!RPScreenRecorder.sharedRecorder.available) return {};
    return {{0, false, "This app"}};
}

std::function<void()> StartScreen(int64_t, bool, int fps, int, int, PushVideoSource *source, bool) {
    RPScreenRecorder *recorder = RPScreenRecorder.sharedRecorder;
    if (!recorder.available) return nullptr;
    webrtc::scoped_refptr<PushVideoSource> target(source);
    auto lastUs = std::make_shared<std::atomic<int64_t>>(0);
    const int64_t periodUs = 1000000 / fps;
    __block bool failed = false;
    dispatch_semaphore_t started = dispatch_semaphore_create(0);
    recorder.microphoneEnabled = NO; // the call has its own microphone track
    [recorder startCaptureWithHandler:^(CMSampleBufferRef sample, RPSampleBufferType type, NSError *error) {
        if (error) return;
        if (type == RPSampleBufferTypeAudioApp) {
            std::lock_guard<std::mutex> g(replayAudioLock);
            if (replayAudio) PushSampleBuffer(replayAudio.get(), sample);
            return;
        }
        if (type != RPSampleBufferTypeVideo) return;
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

#if TARGET_OS_OSX
std::function<void()> WatchAudioFormat(std::function<void()> onChange) {
    // One listener on the system (devices and default output changed) and one per device (nominal sample rate).
    // Blocks run on a private queue; the shim debounces and restarts playout on its worker thread.
    dispatch_queue_t queue = dispatch_queue_create("krtc.audio-format", DISPATCH_QUEUE_SERIAL);
    auto callback = std::make_shared<std::function<void()>>(std::move(onChange));
    AudioObjectPropertyListenerBlock block = ^(UInt32, const AudioObjectPropertyAddress *) { (*callback)(); };
    const AudioObjectPropertyAddress rate = {kAudioDevicePropertyNominalSampleRate, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
    const AudioObjectPropertyAddress defaultOut = {kAudioHardwarePropertyDefaultOutputDevice, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
    const AudioObjectPropertyAddress devicesAddr = {kAudioHardwarePropertyDevices, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
    std::vector<AudioObjectID> devices;
    UInt32 size = 0;
    if (AudioObjectGetPropertyDataSize(kAudioObjectSystemObject, &devicesAddr, 0, nullptr, &size) == noErr && size > 0) {
        devices.resize(size / sizeof(AudioObjectID));
        if (AudioObjectGetPropertyData(kAudioObjectSystemObject, &devicesAddr, 0, nullptr, &size, devices.data()) != noErr) devices.clear();
    }
    for (AudioObjectID d : devices) AudioObjectAddPropertyListenerBlock(d, &rate, queue, block);
    AudioObjectAddPropertyListenerBlock(kAudioObjectSystemObject, &defaultOut, queue, block);
    return [devices, block, queue, rate, defaultOut, callback] {
        for (AudioObjectID d : devices) AudioObjectRemovePropertyListenerBlock(d, &rate, queue, block);
        AudioObjectRemovePropertyListenerBlock(kAudioObjectSystemObject, &defaultOut, queue, block);
        dispatch_sync(queue, ^{}); // no block runs after this returns
    };
}
#else
std::function<void()> WatchAudioFormat(std::function<void()>) { return nullptr; }
#endif

}  // namespace krtc
