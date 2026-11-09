// Linux video codecs for the krtc shim: the software codecs libwebrtc is built with
// (libvpx VP8/VP9, OpenH264 + FFmpeg H.264, libaom AV1 encoder, dav1d AV1 decoder).
#include "krtc_platform.h"

#include "api/video_codecs/video_decoder_factory_template.h"
#include "api/video_codecs/video_decoder_factory_template_dav1d_adapter.h"
#include "api/video_codecs/video_decoder_factory_template_libvpx_vp8_adapter.h"
#include "api/video_codecs/video_decoder_factory_template_libvpx_vp9_adapter.h"
#include "api/video_codecs/video_decoder_factory_template_open_h264_adapter.h"
#include "api/video_codecs/video_encoder_factory_template.h"
#include "api/video_codecs/video_encoder_factory_template_libaom_av1_adapter.h"
#include "api/video_codecs/video_encoder_factory_template_libvpx_vp8_adapter.h"
#include "api/video_codecs/video_encoder_factory_template_libvpx_vp9_adapter.h"
#include "api/video_codecs/video_encoder_factory_template_open_h264_adapter.h"

namespace krtc {

std::unique_ptr<webrtc::VideoEncoderFactory> PlatformVideoEncoderFactory() {
    return std::make_unique<webrtc::VideoEncoderFactoryTemplate<
        webrtc::LibvpxVp8EncoderTemplateAdapter, webrtc::LibvpxVp9EncoderTemplateAdapter,
        webrtc::OpenH264EncoderTemplateAdapter, webrtc::LibaomAv1EncoderTemplateAdapter>>();
}

std::unique_ptr<webrtc::VideoDecoderFactory> PlatformVideoDecoderFactory() {
    return std::make_unique<webrtc::VideoDecoderFactoryTemplate<
        webrtc::LibvpxVp8DecoderTemplateAdapter, webrtc::LibvpxVp9DecoderTemplateAdapter,
        webrtc::OpenH264DecoderTemplateAdapter, webrtc::Dav1dDecoderTemplateAdapter>>();
}

}  // namespace krtc

// libwebrtc's PulseAudio device opens an X display only to detect key presses (typing noise).
// These forwarders keep the client free of a link-time libX11 dependency: with libX11.so.6 present
// they call it, otherwise there is no display and the key-press detection is simply off.
#include <dlfcn.h>

namespace {
void *X11(const char *name) {
    static void *lib = dlopen("libX11.so.6", RTLD_LAZY | RTLD_LOCAL);
    return lib ? dlsym(lib, name) : nullptr;
}
}  // namespace

extern "C" {
struct _XDisplay;
_XDisplay *XOpenDisplay(const char *name) {
    static auto fn = reinterpret_cast<_XDisplay *(*)(const char *)>(X11("XOpenDisplay"));
    return fn ? fn(name) : nullptr;
}
int XCloseDisplay(_XDisplay *display) {
    static auto fn = reinterpret_cast<int (*)(_XDisplay *)>(X11("XCloseDisplay"));
    return fn ? fn(display) : 0;
}
int XQueryKeymap(_XDisplay *display, char keys[32]) {
    static auto fn = reinterpret_cast<int (*)(_XDisplay *, char *)>(X11("XQueryKeymap"));
    return fn ? fn(display, keys) : 0;
}
}
