// Software video codecs for the krtc shim (Linux, Android): whichever of libvpx VP8/VP9, OpenH264 + FFmpeg
// H.264, libaom AV1 and dav1d the libwebrtc build includes (its defines say which).
#include "krtc_platform.h"

#include "api/video_codecs/video_decoder_factory_template.h"
#include "api/video_codecs/video_decoder_factory_template_libvpx_vp8_adapter.h"
#include "api/video_codecs/video_encoder_factory_template.h"
#include "api/video_codecs/video_encoder_factory_template_libvpx_vp8_adapter.h"
#if defined(RTC_ENABLE_VP9)
#include "api/video_codecs/video_decoder_factory_template_libvpx_vp9_adapter.h"
#include "api/video_codecs/video_encoder_factory_template_libvpx_vp9_adapter.h"
#endif
#if defined(WEBRTC_USE_H264)
#include "api/video_codecs/video_decoder_factory_template_open_h264_adapter.h"
#include "api/video_codecs/video_encoder_factory_template_open_h264_adapter.h"
#endif
#if defined(RTC_USE_LIBAOM_AV1_ENCODER)
#include "api/video_codecs/video_encoder_factory_template_libaom_av1_adapter.h"
#endif
#if defined(RTC_DAV1D_IN_INTERNAL_DECODER_FACTORY)
#include "api/video_codecs/video_decoder_factory_template_dav1d_adapter.h"
#endif

namespace krtc {

std::unique_ptr<webrtc::VideoEncoderFactory> PlatformVideoEncoderFactory() {
    return std::make_unique<webrtc::VideoEncoderFactoryTemplate<
        webrtc::LibvpxVp8EncoderTemplateAdapter
#if defined(RTC_ENABLE_VP9)
        , webrtc::LibvpxVp9EncoderTemplateAdapter
#endif
#if defined(WEBRTC_USE_H264)
        , webrtc::OpenH264EncoderTemplateAdapter
#endif
#if defined(RTC_USE_LIBAOM_AV1_ENCODER)
        , webrtc::LibaomAv1EncoderTemplateAdapter
#endif
        >>();
}

std::unique_ptr<webrtc::VideoDecoderFactory> PlatformVideoDecoderFactory() {
    return std::make_unique<webrtc::VideoDecoderFactoryTemplate<
        webrtc::LibvpxVp8DecoderTemplateAdapter
#if defined(RTC_ENABLE_VP9)
        , webrtc::LibvpxVp9DecoderTemplateAdapter
#endif
#if defined(WEBRTC_USE_H264)
        , webrtc::OpenH264DecoderTemplateAdapter
#endif
#if defined(RTC_DAV1D_IN_INTERNAL_DECODER_FACTORY)
        , webrtc::Dav1dDecoderTemplateAdapter
#endif
        >>();
}

}  // namespace krtc

#if !defined(__ANDROID__)
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
#endif  // !__ANDROID__
