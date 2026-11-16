#pragma once

#include "api/video_codecs/video_encoder_factory.h"
#include <memory>
#include <string>

namespace krtc {
std::string WindowsEncoderDiagnosticsJson();
// Adds Media Foundation H.264, retaining the supplied software factory for every codec and fallback.
std::unique_ptr<webrtc::VideoEncoderFactory> WindowsVideoEncoderFactory(
    std::unique_ptr<webrtc::VideoEncoderFactory> software);
}
