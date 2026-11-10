// Platform pieces of the krtc shim (internal header).
#pragma once
#include <functional>
#include <memory>
#include <string>

#include "api/environment/environment.h"
#include "api/video/video_frame.h"
#include "api/video_codecs/video_decoder_factory.h"
#include "api/video_codecs/video_encoder_factory.h"
#include "media/base/adapted_video_track_source.h"
#include "rtc_base/thread.h"

namespace krtc {

/** A video source the application or a capturer pushes frames into. Thread-safe. */
class PushVideoSource : public webrtc::AdaptedVideoTrackSource {
public:
    void Push(const webrtc::VideoFrame &frame) { OnFrame(frame); }
    SourceState state() const override { return kLive; }
    bool remote() const override { return false; }
    bool is_screencast() const override { return false; }
    std::optional<bool> needs_denoising() const override { return false; }
};

// Hardware-accelerated factories where the platform has them (Apple: VideoToolbox H.264/H.265 + libvpx/AV1);
// nullptr where none is built in, and the shim falls back to software VP8.
std::unique_ptr<webrtc::VideoEncoderFactory> PlatformVideoEncoderFactory();
std::unique_ptr<webrtc::VideoDecoderFactory> PlatformVideoDecoderFactory();

// Cameras: Apple (AVFoundation), Linux (V4L2). Elsewhere (Android: the app captures with Camera2) none.
int CameraCount();
std::string CameraName(int index);

/**
 * Starts capturing camera [index] at about width × height and fps into [source]. Returns a function that
 * stops the capture, or nullptr if the camera is unavailable.
 */
std::function<void()> StartCamera(int index, int width, int height, int fps, PushVideoSource *source);

}  // namespace krtc
