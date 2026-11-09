// Apple video codec factories: libwebrtc's RTCDefaultVideo{En,De}coderFactory (VideoToolbox H.264/H.265,
// libvpx VP8/VP9, AV1) bridged to the C++ factory interfaces.
#include "krtc_platform.h"

#import "sdk/objc/components/video_codec/RTCDefaultVideoDecoderFactory.h"
#import "sdk/objc/components/video_codec/RTCDefaultVideoEncoderFactory.h"
#include "sdk/objc/native/api/video_decoder_factory.h"
#include "sdk/objc/native/api/video_encoder_factory.h"

namespace krtc {
std::unique_ptr<webrtc::VideoEncoderFactory> PlatformVideoEncoderFactory() {
    return webrtc::ObjCToNativeVideoEncoderFactory([[RTCDefaultVideoEncoderFactory alloc] init]);
}
std::unique_ptr<webrtc::VideoDecoderFactory> PlatformVideoDecoderFactory() {
    return webrtc::ObjCToNativeVideoDecoderFactory([[RTCDefaultVideoDecoderFactory alloc] init]);
}
}  // namespace krtc
