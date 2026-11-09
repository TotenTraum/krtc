// Platform video codec factories for the krtc shim (internal header).
#pragma once
#include <memory>
#include "api/video_codecs/video_decoder_factory.h"
#include "api/video_codecs/video_encoder_factory.h"

namespace krtc {
// Hardware-accelerated factories where the platform has them (Apple: VideoToolbox H.264/H.265 + libvpx/AV1);
// nullptr where none is built in, and the shim falls back to software VP8.
std::unique_ptr<webrtc::VideoEncoderFactory> PlatformVideoEncoderFactory();
std::unique_ptr<webrtc::VideoDecoderFactory> PlatformVideoDecoderFactory();
}  // namespace krtc
