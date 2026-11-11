// Linux, Windows and Android pieces of the krtc shim: software video codecs (whichever of libvpx VP8/VP9,
// OpenH264 + FFmpeg H.264, libaom AV1 and dav1d the libwebrtc build includes; its defines say which) and camera
// capture: V4L2 on Linux, DirectShow on Windows.
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
#if defined(_WIN32)
// libwebrtc's video capture factory, which on Windows is DirectShow (DeviceInfoDS, VideoCaptureDS).
#include "modules/video_capture/video_capture_factory.h"
#elif !defined(__ANDROID__)
// V4L2 directly: VideoCaptureFactory would also link the PipeWire/xdg-portal camera code, which needs glib.
#include "api/make_ref_counted.h"
#include "modules/video_capture/linux/device_info_v4l2.h"
#include "modules/video_capture/linux/video_capture_v4l2.h"
#include "system_wrappers/include/clock.h"
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

#if defined(__ANDROID__)
// Android: the application captures with Camera2 and pushes frames (krtc_video_source_push_*).
int CameraCount() { return 0; }
std::string CameraName(int) { return ""; }
std::function<void()> StartCamera(int, int, int, int, PushVideoSource *) { return nullptr; }
#else
// Linux: V4L2 through libwebrtc's video capture module (its V4L2 classes, not the factory).
// Windows: the same module through VideoCaptureFactory (DirectShow; COM is initialized by DeviceInfoDS).
namespace {
class CameraSink : public webrtc::VideoSinkInterface<webrtc::VideoFrame> {
public:
    explicit CameraSink(PushVideoSource *source) : source_(source) {}
    void OnFrame(const webrtc::VideoFrame &frame) override { source_->Push(frame); }

private:
    webrtc::scoped_refptr<PushVideoSource> source_;
};

std::unique_ptr<webrtc::VideoCaptureModule::DeviceInfo> DeviceInfo() {
#if defined(_WIN32)
    return std::unique_ptr<webrtc::VideoCaptureModule::DeviceInfo>(webrtc::VideoCaptureFactory::CreateDeviceInfo());
#else
    auto info = std::make_unique<webrtc::videocapturemodule::DeviceInfoV4l2>();
    return info->Init() == 0 ? std::move(info) : nullptr;
#endif
}

webrtc::scoped_refptr<webrtc::VideoCaptureModule> OpenCamera(const std::string &id) {
#if defined(_WIN32)
    return webrtc::VideoCaptureFactory::Create(id.c_str());
#else
    auto module = webrtc::make_ref_counted<webrtc::videocapturemodule::VideoCaptureModuleV4L2>(webrtc::Clock::GetRealTimeClock());
    if (module->Init(id.c_str()) != 0) return nullptr;
    return module;
#endif
}

bool DeviceId(int index, std::string *name, std::string *id) {
    auto info = DeviceInfo();
    if (!info || index < 0 || index >= static_cast<int>(info->NumberOfDevices())) return false;
    char n[256] = {0}, u[256] = {0};
    if (info->GetDeviceName(index, n, sizeof n, u, sizeof u) != 0) return false;
    if (name) *name = n;
    if (id) *id = u;
    return true;
}
}  // namespace

int CameraCount() {
    auto info = DeviceInfo();
    return info ? static_cast<int>(info->NumberOfDevices()) : 0;
}

std::string CameraName(int index) {
    std::string name;
    return DeviceId(index, &name, nullptr) ? name : "";
}

std::function<void()> StartCamera(int index, int width, int height, int fps, PushVideoSource *source) {
    std::string id;
    if (!DeviceId(index, nullptr, &id)) return nullptr;
    auto module = OpenCamera(id);
    if (!module) return nullptr;
    auto sink = std::make_shared<CameraSink>(source);
    module->RegisterCaptureDataCallback(sink.get());
    module->SetApplyRotation(true);
    webrtc::VideoCaptureCapability capability;
    capability.width = width;
    capability.height = height;
    capability.maxFPS = fps;
    capability.videoType = webrtc::VideoType::kI420;
    auto info = DeviceInfo();
    webrtc::VideoCaptureCapability best;
    if (info && info->GetBestMatchedCapability(id.c_str(), capability, best) >= 0) capability = best;
    if (module->StartCapture(capability) != 0) {
        module->DeRegisterCaptureDataCallback();
        return nullptr;
    }
    return [module, sink]() {
        module->StopCapture();
        module->DeRegisterCaptureDataCallback();
    };
}
#endif

}  // namespace krtc

#if defined(__linux__) && !defined(__ANDROID__)
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
#endif  // Linux
