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
#include <atomic>
#include <chrono>
#include <thread>
#include <future>
#if defined(_WIN32)
#include <audioclient.h>
#include <mmdeviceapi.h>
#include "modules/desktop_capture/desktop_capture_options.h"
#include "modules/desktop_capture/desktop_capturer.h"
#include "modules/desktop_capture/desktop_frame.h"
#elif defined(__linux__) && !defined(__ANDROID__)
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <dlfcn.h>
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

// ---------------------------------------------------------------- screen capture

namespace {
/** Runs [capture] about [fps] times a second on a thread of its own until the returned stop function is called. */
std::function<void()> Every(int fps, std::function<void()> setup, std::function<void()> capture, std::function<void()> teardown) {
    auto running = std::make_shared<std::atomic<bool>>(true);
    auto thread = std::make_shared<std::thread>([=] {
        setup();
        const auto period = std::chrono::microseconds(1000000 / std::max(1, fps));
        auto next = std::chrono::steady_clock::now();
        while (running->load()) {
            capture();
            next += period;
            std::this_thread::sleep_until(next);
            if (std::chrono::steady_clock::now() > next + period) next = std::chrono::steady_clock::now(); // fell behind
        }
        teardown();
    });
    return [running, thread] {
        running->store(false);
        thread->join();
    };
}
}  // namespace

#if defined(_WIN32)
// Windows: libwebrtc's DesktopCapturer (DXGI or GDI for displays, window capture). Each capturer lives on its
// capture thread: created, used and destroyed there.
namespace {
webrtc::DesktopCaptureOptions ScreenOptions() {
    webrtc::DesktopCaptureOptions options = webrtc::DesktopCaptureOptions::CreateDefault();
    options.set_allow_directx_capturer(true);
    return options;
}

std::unique_ptr<webrtc::DesktopCapturer> CreateCapturer(bool window) {
    return window ? webrtc::DesktopCapturer::CreateWindowCapturer(ScreenOptions()) : webrtc::DesktopCapturer::CreateScreenCapturer(ScreenOptions());
}

class FrameSink : public webrtc::DesktopCapturer::Callback {
public:
    FrameSink(PushVideoSource *source, int maxWidth, int maxHeight) : source_(source), maxWidth_(maxWidth), maxHeight_(maxHeight) {}
    void OnCaptureResult(webrtc::DesktopCapturer::Result result, std::unique_ptr<webrtc::DesktopFrame> frame) override {
        if (result != webrtc::DesktopCapturer::Result::SUCCESS || !frame) return;
        PushBgra(source_.get(), frame->data(), frame->stride(), frame->size().width(), frame->size().height(), maxWidth_, maxHeight_, 0);
    }

private:
    webrtc::scoped_refptr<PushVideoSource> source_;
    int maxWidth_, maxHeight_;
};
}  // namespace

std::vector<ScreenSource> ScreenSources() {
    std::vector<ScreenSource> out;
    for (bool window : {false, true}) {
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

std::function<void()> StartScreen(int64_t id, bool window, int fps, int maxWidth, int maxHeight, PushVideoSource *source) {
    // Check on the caller's thread that the source can be selected, then capture on a thread of its own.
    {
        bool ok = false;
        std::thread([&] {
            auto probe = CreateCapturer(window);
            ok = probe && probe->SelectSource(static_cast<webrtc::DesktopCapturer::SourceId>(id));
        }).join();
        if (!ok) return nullptr;
    }
    auto sink = std::make_shared<FrameSink>(source, maxWidth, maxHeight);
    auto capturer = std::make_shared<std::unique_ptr<webrtc::DesktopCapturer>>();
    return Every(
        fps,
        [=] {
            *capturer = CreateCapturer(window);
            if (*capturer && (*capturer)->SelectSource(static_cast<webrtc::DesktopCapturer::SourceId>(id))) (*capturer)->Start(sink.get());
            else capturer->reset();
        },
        [=] { if (*capturer) (*capturer)->CaptureFrame(); },
        [=] { capturer->reset(); });
}

// System sound: WASAPI loopback of the default output device (shared-mode mix format: 32-bit float or 16-bit).
std::function<void()> StartSystemAudio(PushAudioSource *source) {
    webrtc::scoped_refptr<PushAudioSource> target(source);
    auto running = std::make_shared<std::atomic<bool>>(true);
    auto started = std::make_shared<std::promise<bool>>();
    auto ok = started->get_future();
    auto thread = std::make_shared<std::thread>([=] {
        CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        IMMDeviceEnumerator *enumerator = nullptr;
        IMMDevice *device = nullptr;
        IAudioClient *client = nullptr;
        IAudioCaptureClient *capture = nullptr;
        WAVEFORMATEX *format = nullptr;
        bool ready = SUCCEEDED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator), reinterpret_cast<void **>(&enumerator))) &&
                     SUCCEEDED(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device)) &&
                     SUCCEEDED(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, reinterpret_cast<void **>(&client))) &&
                     SUCCEEDED(client->GetMixFormat(&format)) && (format->wBitsPerSample == 32 || format->wBitsPerSample == 16) &&
                     SUCCEEDED(client->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, 200000, 0, format, nullptr)) &&
                     SUCCEEDED(client->GetService(__uuidof(IAudioCaptureClient), reinterpret_cast<void **>(&capture))) &&
                     SUCCEEDED(client->Start());
        started->set_value(ready);
        if (ready) {
            const int channels = format->nChannels;
            const int rate = static_cast<int>(format->nSamplesPerSec);
            const bool isFloat = format->wBitsPerSample == 32;
            std::vector<int16_t> silence;
            while (running->load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                UINT32 packet = 0;
                while (SUCCEEDED(capture->GetNextPacketSize(&packet)) && packet > 0) {
                    BYTE *data = nullptr;
                    UINT32 frames = 0;
                    DWORD flags = 0;
                    if (FAILED(capture->GetBuffer(&data, &frames, &flags, nullptr, nullptr))) break;
                    if (flags & AUDCLNT_BUFFERFLAGS_SILENT) {
                        silence.assign(static_cast<size_t>(frames) * channels, 0);
                        target->Push(silence.data(), frames, channels, rate);
                    } else if (isFloat) {
                        PushFloat(target.get(), reinterpret_cast<const float *>(data), frames, channels, rate);
                    } else {
                        target->Push(reinterpret_cast<const int16_t *>(data), frames, channels, rate);
                    }
                    capture->ReleaseBuffer(frames);
                }
            }
            client->Stop();
        }
        if (format) CoTaskMemFree(format);
        if (capture) capture->Release();
        if (client) client->Release();
        if (device) device->Release();
        if (enumerator) enumerator->Release();
        CoUninitialize();
    });
    if (!ok.get()) {
        thread->join();
        return nullptr;
    }
    return [running, thread] {
        running->store(false);
        thread->join();
    };
}

#elif defined(__linux__) && !defined(__ANDROID__)
// Linux: the X11 display with XGetImage (whole frames; no XDamage), through libX11 loaded at run time, so the
// client still has no link-time X11 dependency. Wayland sessions are not supported (they need PipeWire and the
// desktop portal); under XWayland only X11 windows are visible.
namespace {
void *X11Symbol(const char *name) {
    static void *lib = dlopen("libX11.so.6", RTLD_LAZY | RTLD_LOCAL);
    return lib ? dlsym(lib, name) : nullptr;
}

struct XFunctions {
    Display *(*open)(const char *) = nullptr;
    int (*close)(Display *) = nullptr;
    Window (*root)(Display *) = nullptr;
    Status (*attributes)(Display *, Window, XWindowAttributes *) = nullptr;
    XImage *(*image)(Display *, Drawable, int, int, unsigned int, unsigned int, unsigned long, int) = nullptr;
    bool ok = false;
};

const XFunctions &X() {
    static const XFunctions f = [] {
        XFunctions f;
        f.open = reinterpret_cast<decltype(f.open)>(X11Symbol("XOpenDisplay"));
        f.close = reinterpret_cast<decltype(f.close)>(X11Symbol("XCloseDisplay"));
        f.root = reinterpret_cast<decltype(f.root)>(X11Symbol("XDefaultRootWindow"));
        f.attributes = reinterpret_cast<decltype(f.attributes)>(X11Symbol("XGetWindowAttributes"));
        f.image = reinterpret_cast<decltype(f.image)>(X11Symbol("XGetImage"));
        f.ok = f.open && f.close && f.root && f.attributes && f.image;
        return f;
    }();
    return f;
}
}  // namespace

std::vector<ScreenSource> ScreenSources() {
    const XFunctions &x = X();
    if (!x.ok) return {};
    Display *display = x.open(nullptr);
    if (!display) return {};
    XWindowAttributes a{};
    x.attributes(display, x.root(display), &a);
    x.close(display);
    const char *name = getenv("DISPLAY");
    return {{-1, false, "X11 display " + std::string(name ? name : "") + " " + std::to_string(a.width) + "x" + std::to_string(a.height)}};
}

std::function<void()> StartScreen(int64_t, bool window, int fps, int maxWidth, int maxHeight, PushVideoSource *source) {
    const XFunctions &x = X();
    if (window || !x.ok) return nullptr;
    Display *display = x.open(nullptr);
    if (!display) return nullptr;
    webrtc::scoped_refptr<PushVideoSource> target(source);
    return Every(
        fps, [] {},
        [=] {
            Window root = x.root(display);
            XWindowAttributes a{};
            if (!x.attributes(display, root, &a)) return;
            XImage *image = x.image(display, root, 0, 0, a.width, a.height, AllPlanes, ZPixmap);
            if (!image) return;
            // 24/32-bit TrueColor on little-endian: B, G, R, X in memory, which libyuv calls ARGB.
            if (image->bits_per_pixel == 32)
                PushBgra(target.get(), reinterpret_cast<const uint8_t *>(image->data), image->bytes_per_line, image->width, image->height, maxWidth, maxHeight, 0);
            image->f.destroy_image(image);
        },
        [=] { x.close(display); });
}

// System sound: the monitor of the default sink through libpulse-simple, loaded at run time (works with PulseAudio
// and with PipeWire's pulse server). No sound server: no system audio.
namespace {
struct PaSampleSpec {
    int format; // PA_SAMPLE_S16LE = 3
    uint32_t rate;
    uint8_t channels;
};
struct PaBufferAttr {
    uint32_t maxlength, tlength, prebuf, minreq, fragsize;
};
struct PulseFunctions {
    void *(*open)(const char *, const char *, int, const char *, const char *, const PaSampleSpec *, const void *, const PaBufferAttr *, int *) = nullptr;
    int (*read)(void *, void *, size_t, int *) = nullptr;
    void (*free)(void *) = nullptr;
    bool ok = false;
};
const PulseFunctions &Pulse() {
    static const PulseFunctions f = [] {
        PulseFunctions f;
        void *lib = dlopen("libpulse-simple.so.0", RTLD_LAZY | RTLD_LOCAL);
        if (!lib) return f;
        f.open = reinterpret_cast<decltype(f.open)>(dlsym(lib, "pa_simple_new"));
        f.read = reinterpret_cast<decltype(f.read)>(dlsym(lib, "pa_simple_read"));
        f.free = reinterpret_cast<decltype(f.free)>(dlsym(lib, "pa_simple_free"));
        f.ok = f.open && f.read && f.free;
        return f;
    }();
    return f;
}
}  // namespace

std::function<void()> StartSystemAudio(PushAudioSource *source) {
    const PulseFunctions &pa = Pulse();
    if (!pa.ok) return nullptr;
    const int rate = 48000, channels = 2, block = rate / 100; // 10 ms
    PaSampleSpec spec{3, static_cast<uint32_t>(rate), static_cast<uint8_t>(channels)};
    PaBufferAttr attr{UINT32_MAX, UINT32_MAX, UINT32_MAX, UINT32_MAX, static_cast<uint32_t>(block * channels * 2)};
    int error = 0;
    void *stream = pa.open(nullptr, "krtc", 2 /* PA_STREAM_RECORD */, "@DEFAULT_MONITOR@", "screen sound", &spec, nullptr, &attr, &error);
    if (!stream) return nullptr;
    webrtc::scoped_refptr<PushAudioSource> target(source);
    auto running = std::make_shared<std::atomic<bool>>(true);
    auto thread = std::make_shared<std::thread>([=, &pa] {
        std::vector<int16_t> pcm(static_cast<size_t>(block) * channels);
        int err = 0;
        while (running->load() && pa.read(stream, pcm.data(), pcm.size() * 2, &err) >= 0) target->Push(pcm.data(), block, channels, rate);
        pa.free(stream);
    });
    return [running, thread] {
        running->store(false); // the blocking read returns within 10 ms
        thread->join();
    };
}

#else
// Android: the application captures with MediaProjection (AndroidScreenCapture) into a screencast source, and the
// sound with AudioPlaybackCapture into a pushed audio source.
std::vector<ScreenSource> ScreenSources() { return {}; }
std::function<void()> StartScreen(int64_t, bool, int, int, int, PushVideoSource *) { return nullptr; }
std::function<void()> StartSystemAudio(PushAudioSource *) { return nullptr; }
#endif

std::function<void()> WatchAudioFormat(std::function<void()>) { return nullptr; }

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
