// Platform pieces of the krtc shim (internal header).
#pragma once
#include <functional>
#include <memory>
#include <string>
#include <vector>
#include <cstdint>

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
    /** [screencast]: screen content, so encoders keep resolution and sharpness over frame rate. */
    explicit PushVideoSource(bool screencast = false) : screencast_(screencast) {}
    void Push(const webrtc::VideoFrame &frame) { OnFrame(frame); }
    SourceState state() const override { return kLive; }
    bool remote() const override { return false; }
    bool is_screencast() const override { return screencast_; }
    std::optional<bool> needs_denoising() const override { return false; }

private:
    const bool screencast_;
};

// Hardware-accelerated factories where the platform has them (Apple: VideoToolbox H.264/H.265 + libvpx/AV1);
// nullptr where none is built in, and the shim falls back to software VP8.
std::unique_ptr<webrtc::VideoEncoderFactory> PlatformVideoEncoderFactory();
std::unique_ptr<webrtc::VideoDecoderFactory> PlatformVideoDecoderFactory();

// Cameras: Apple (AVFoundation), Linux (V4L2), Windows (DirectShow). Elsewhere (Android: the app captures with Camera2) none.
int CameraCount();
std::string CameraName(int index);

/**
 * Starts capturing camera [index] at about width × height and fps into [source]. Returns a function that
 * stops the capture, or nullptr if the camera is unavailable.
 */
std::function<void()> StartCamera(int index, int width, int height, int fps, PushVideoSource *source);

/** Something that can be captured as a screen share: a display (window = false) or a window. */
struct ScreenSource {
    int64_t id;
    bool window;
    std::string title;
};

/**
 * Screen capture built into the shim. macOS: displays and windows (libwebrtc DesktopCapturer on ScreenCaptureKit;
 * asks for the Screen Recording permission). Linux: the X11 display (XGetImage; no Wayland). iOS: this app's own
 * screen (ReplayKit in-app capture, one source). Android: none (the app uses MediaProjection and pushes frames).
 */
std::vector<ScreenSource> ScreenSources();

/**
 * Starts capturing [id] (a display, or a window if [window]) at up to [fps] into [source], scaled down to at most
 * [maxWidth] × [maxHeight]. Returns a function that stops it, or nullptr if capture is unavailable or not permitted.
 */
std::function<void()> StartScreen(int64_t id, bool window, int fps, int maxWidth, int maxHeight, PushVideoSource *source);

/** Pushes a 32-bit frame with libyuv byte order [argb] = B,G,R,A in memory, scaled to fit the limits. */
void PushBgra(PushVideoSource *source, const uint8_t *bgra, int stride, int width, int height, int maxWidth, int maxHeight, int64_t timestampUs);

}  // namespace krtc
