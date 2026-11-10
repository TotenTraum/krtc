// Apple platform pieces: libwebrtc's RTCDefaultVideo{En,De}coderFactory (VideoToolbox H.264/H.265, libvpx VP8/VP9,
// AV1) bridged to the C++ factory interfaces, and camera capture through RTCCameraVideoCapturer (AVFoundation).
#include "krtc_platform.h"

#import <AVFoundation/AVFoundation.h>

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

}  // namespace krtc
