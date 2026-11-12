// krtc native shim: implementation of krtc.h over libwebrtc's C++ API (ADR-0002).
// Built with -fvisibility=hidden; only the extern "C" functions below are exported.

#include "krtc.h"
#include "krtc_audio.h"
#include "krtc_platform.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "api/audio/audio_frame.h"
#include "api/audio/audio_frame_processor.h"
#include "api/audio/audio_processing.h"
#include "api/audio/builtin_audio_processing_builder.h"
#include "api/audio/create_audio_device_module.h"
#include "api/audio_codecs/builtin_audio_decoder_factory.h"
#include "api/audio_codecs/builtin_audio_encoder_factory.h"
#include "api/create_peerconnection_factory.h"
#include "api/data_channel_interface.h"
#include "api/environment/environment_factory.h"
#include "api/jsep.h"
#include "api/make_ref_counted.h"
#include "api/peer_connection_interface.h"
#include "api/set_local_description_observer_interface.h"
#include "api/set_remote_description_observer_interface.h"
#include "api/stats/rtc_stats_collector_callback.h"
#include "api/stats/rtc_stats_report.h"
#include "api/video/i420_buffer.h"
#include "api/video/video_frame.h"
#include "api/video_codecs/video_decoder_factory_template.h"
#include "api/video_codecs/video_decoder_factory_template_libvpx_vp8_adapter.h"
#include "api/video_codecs/video_encoder_factory_template.h"
#include "api/video_codecs/video_encoder_factory_template_libvpx_vp8_adapter.h"
#include "media/base/adapted_video_track_source.h"
#include "rtc_base/thread.h"
#include "rtc_base/time_utils.h"
#include "third_party/libyuv/include/libyuv/convert.h"
#include "third_party/libyuv/include/libyuv/convert_argb.h"

#define KRTC_API extern "C" __attribute__((visibility("default")))

using namespace webrtc;

namespace {

char *Dup(const std::string &s) {
    char *p = static_cast<char *>(std::malloc(s.size() + 1));
    std::memcpy(p, s.c_str(), s.size() + 1);
    return p;
}

// Generates a moving square on a coloured background, so the remote side can see motion.
class TestVideoSource : public AdaptedVideoTrackSource {
public:
    TestVideoSource(int width, int height, int fps) : width_(width), height_(height), fps_(fps) {
        thread_ = std::thread([this] { Loop(); });
    }
    ~TestVideoSource() override {
        running_ = false;
        if (thread_.joinable()) thread_.join();
    }
    SourceState state() const override { return kLive; }
    bool remote() const override { return false; }
    bool is_screencast() const override { return false; }
    std::optional<bool> needs_denoising() const override { return false; }

private:
    void Loop() {
        int64_t n = 0;
        while (running_) {
            scoped_refptr<I420Buffer> buf = I420Buffer::Create(width_, height_);
            I420Buffer::SetBlack(buf.get());
            std::memset(buf->MutableDataU(), 90, buf->StrideU() * ((height_ + 1) / 2));
            std::memset(buf->MutableDataV(), 170, buf->StrideV() * ((height_ + 1) / 2));
            const int size = height_ / 4;
            const int x = static_cast<int>((n * 7) % (width_ - size));
            const int y = static_cast<int>((n * 3) % (height_ - size));
            for (int row = y; row < y + size; ++row) std::memset(buf->MutableDataY() + row * buf->StrideY() + x, 235, size);
            OnFrame(VideoFrame::Builder()
                        .set_video_frame_buffer(buf)
                        .set_timestamp_us(TimeMicros())
                        .set_rotation(kVideoRotation_0)
                        .build());
            ++n;
            std::this_thread::sleep_for(std::chrono::milliseconds(1000 / fps_));
        }
    }

    const int width_, height_, fps_;
    std::atomic<bool> running_{true};
    std::thread thread_;
};

/** Converts every frame of a track to ARGB for the application. */
class ArgbSink : public VideoSinkInterface<VideoFrame> {
public:
    ArgbSink(krtc_frame_fn fn, void *user) : fn_(fn), user_(user) {}
    void OnFrame(const VideoFrame &frame) override {
        scoped_refptr<I420BufferInterface> i420 = frame.video_frame_buffer()->ToI420();
        if (!i420) return;
        const int w = i420->width(), h = i420->height();
        argb_.resize(static_cast<size_t>(w) * h);
        libyuv::I420ToARGB(i420->DataY(), i420->StrideY(), i420->DataU(), i420->StrideU(), i420->DataV(), i420->StrideV(),
                           reinterpret_cast<uint8_t *>(argb_.data()), w * 4, w, h);
        fn_(user_, w, h, argb_.data(), frame.timestamp_us());
    }

private:
    krtc_frame_fn fn_;
    void *user_;
    std::vector<uint32_t> argb_;
};

class FrameCounter : public VideoSinkInterface<VideoFrame> {
public:
    void OnFrame(const VideoFrame &frame) override {
        width = frame.width();
        height = frame.height();
        frames.fetch_add(1);
    }
    std::atomic<int64_t> frames{0};
    std::atomic<int> width{0}, height{0};
};

class DoneObserver : public SetRemoteDescriptionObserverInterface, public SetLocalDescriptionObserverInterface {
public:
    DoneObserver(krtc_done_fn fn, void *user) : fn_(fn), user_(user) {}
    void OnSetRemoteDescriptionComplete(RTCError error) override { Done(error); }
    void OnSetLocalDescriptionComplete(RTCError error) override { Done(error); }

private:
    void Done(const RTCError &error) { fn_(user_, nullptr, error.ok() ? nullptr : error.message()); }
    krtc_done_fn fn_;
    void *user_;
};

class AnswerObserver : public CreateSessionDescriptionObserver {
public:
    AnswerObserver(krtc_done_fn fn, void *user) : fn_(fn), user_(user) {}
    void OnSuccess(SessionDescriptionInterface *desc) override {
        std::string sdp;
        desc->ToString(&sdp);
        delete desc;
        fn_(user_, sdp.c_str(), nullptr);
    }
    void OnFailure(RTCError error) override { fn_(user_, nullptr, error.message()); }

private:
    krtc_done_fn fn_;
    void *user_;
};

std::unique_ptr<SessionDescriptionInterface> Parse(const char *type, const char *sdp, std::string *error) {
    std::optional<SdpType> t = SdpTypeFromString(type);
    if (!t) {
        *error = std::string("bad SDP type ") + type;
        return nullptr;
    }
    SdpParseError parse_error;
    std::unique_ptr<SessionDescriptionInterface> desc = CreateSessionDescription(*t, sdp, &parse_error);
    if (!desc) *error = "SDP parse error: " + parse_error.description + " at " + parse_error.line;
    return desc;
}

}  // namespace

namespace {
/** Shared between the factory handle and the gate, which libwebrtc owns. */
struct InputGateState {
    std::atomic<int> mode{0};
    std::atomic<double> threshold{0.05};
    std::atomic<int> hold_ms{400};
    std::atomic<double> level{0};
    std::atomic<bool> open{true};
};

/**
 * Sits on the captured audio after the processing (AudioTransport hands it every 10 ms frame): measures the level
 * and silences the frame while the gate is closed, with no delay beyond the frame itself.
 */
class InputGate : public AudioFrameProcessor {
public:
    explicit InputGate(std::shared_ptr<InputGateState> state) : state_(std::move(state)) {}

    void Process(std::unique_ptr<AudioFrame> frame) override {
        int peak = 0;
        if (!frame->muted()) {
            const int16_t *data = frame->data();
            const size_t n = frame->samples_per_channel() * frame->num_channels();
            for (size_t i = 0; i < n; ++i) peak = std::max(peak, std::abs(static_cast<int>(data[i])));
        }
        const double level = peak / 32768.0;
        state_->level = std::max(level, state_->level.load() * 0.8); // a meter that falls back over ~100 ms
        const int64_t now = TimeMillis();
        bool open = true;
        switch (state_->mode.load()) {
        case 1:
            if (level >= state_->threshold.load()) open_until_ = now + state_->hold_ms.load();
            open = now < open_until_;
            break;
        case 2: open = false; break;
        default: break;
        }
        state_->open = open;
        if (!open) frame->Mute();
        std::lock_guard<std::mutex> lock(mu_);
        if (sink_) sink_(std::move(frame));
    }

    void SetSink(OnAudioFrameCallback sink) override {
        std::lock_guard<std::mutex> lock(mu_);
        sink_ = std::move(sink);
    }

private:
    std::shared_ptr<InputGateState> state_;
    int64_t open_until_ = 0;
    std::mutex mu_;
    OnAudioFrameCallback sink_;
};
}  // namespace

struct krtc_factory {
    std::shared_ptr<InputGateState> gate = std::make_shared<InputGateState>();
    std::unique_ptr<Thread> network, worker, signaling;
    scoped_refptr<AudioDeviceModule> adm;
    scoped_refptr<AudioProcessing> apm;
    bool synthetic_audio = false;
    std::atomic<bool> echo_cancellation{true}, noise_suppression{true}, auto_gain_control{true};
    scoped_refptr<PeerConnectionFactoryInterface> factory;
};

namespace {
/** Audio options for new microphone tracks; libwebrtc applies them to the processing when the track starts sending. */
AudioOptions MicrophoneOptions(const krtc_factory *f) {
    AudioOptions o;
    o.echo_cancellation = f->echo_cancellation.load();
    o.noise_suppression = f->noise_suppression.load();
    o.auto_gain_control = f->auto_gain_control.load();
    o.highpass_filter = true;
    return o;
}

/**
 * The adaptive digital gain (AGC2) follows the speech level whatever the device does; the voice engine sets
 * echo cancellation, noise suppression and the analog AGC from the track's options, and leaves AGC2 alone.
 */
void ApplyProcessing(krtc_factory *f) {
    if (!f->apm) return;
    AudioProcessing::Config c = f->apm->GetConfig();
    c.echo_canceller.enabled = f->echo_cancellation.load();
    c.noise_suppression.enabled = f->noise_suppression.load();
    c.noise_suppression.level = AudioProcessing::Config::NoiseSuppression::kHigh;
    const bool agc = f->auto_gain_control.load();
    c.gain_controller1.enabled = agc;
    c.gain_controller2.enabled = agc;
    c.gain_controller2.adaptive_digital.enabled = agc;
    c.high_pass_filter.enabled = true;
    f->apm->ApplyConfig(c);
}
}  // namespace

namespace {
class StatsCallback : public RTCStatsCollectorCallback {
public:
    StatsCallback(krtc_done_fn fn, void *user) : fn_(fn), user_(user) {}
    void OnStatsDelivered(const scoped_refptr<const RTCStatsReport> &report) override {
        std::string json = report->ToJson();
        fn_(user_, json.c_str(), nullptr);
    }

private:
    krtc_done_fn fn_;
    void *user_;
};
}  // namespace

struct krtc_video_source {
    scoped_refptr<krtc::PushVideoSource> source;
    std::function<void()> stop_camera;
    ~krtc_video_source() {
        if (stop_camera) stop_camera();
    }
};

struct krtc_audio_source {
    scoped_refptr<krtc::PushAudioSource> source;
    std::function<void()> stop_capture;
    ~krtc_audio_source() {
        if (stop_capture) stop_capture();
    }
};

struct krtc_track {
    scoped_refptr<MediaStreamTrackInterface> track;
    std::unique_ptr<FrameCounter> counter;
    std::unique_ptr<ArgbSink> sink;
    ~krtc_track() {
        if (track->kind() == MediaStreamTrackInterface::kVideoKind) {
            auto *video = static_cast<VideoTrackInterface *>(track.get());
            if (counter) video->RemoveSink(counter.get());
            if (sink) video->RemoveSink(sink.get());
        }
    }
};

struct krtc_transceiver {
    scoped_refptr<RtpTransceiverInterface> transceiver;
};

struct krtc_data_channel : public DataChannelObserver {
    scoped_refptr<DataChannelInterface> dc;
    std::atomic<krtc_data_channel_event_fn> fn{nullptr};
    std::atomic<void *> user{nullptr};
    bool registered = false;

    explicit krtc_data_channel(scoped_refptr<DataChannelInterface> c) : dc(std::move(c)) {}
    ~krtc_data_channel() override {
        if (registered) dc->UnregisterObserver();
    }
    void OnStateChange() override {
        if (auto f = fn.load()) f(user.load(), KRTC_DATA_CHANNEL_STATE, static_cast<int>(dc->state()), nullptr, 0);
    }
    void OnMessage(const DataBuffer &buffer) override {
        if (auto f = fn.load()) f(user.load(), KRTC_DATA_CHANNEL_MESSAGE, buffer.binary ? 1 : 0, buffer.data.cdata(), static_cast<int>(buffer.size()));
    }
    void OnBufferedAmountChange(uint64_t) override {
        if (auto f = fn.load()) f(user.load(), KRTC_DATA_CHANNEL_BUFFERED_AMOUNT, 0, nullptr, 0);
    }
};

struct krtc_pc : public PeerConnectionObserver {
    krtc_event_fn on_event = nullptr;
    void *user = nullptr;
    scoped_refptr<PeerConnectionInterface> pc;

    void OnSignalingChange(PeerConnectionInterface::SignalingState) override {}
    std::atomic<krtc_data_channel_fn> on_data_channel{nullptr};
    std::atomic<void *> data_channel_user{nullptr};
    void OnDataChannel(scoped_refptr<DataChannelInterface> channel) override {
        if (auto f = on_data_channel.load()) f(data_channel_user.load(), new krtc_data_channel(channel));
    }
    void OnIceGatheringChange(PeerConnectionInterface::IceGatheringState) override {}
    void OnIceCandidate(const IceCandidate *) override {}
    void OnConnectionChange(PeerConnectionInterface::PeerConnectionState state) override {
        if (on_event) on_event(user, KRTC_EVENT_CONNECTION_STATE, static_cast<int>(state), nullptr, nullptr);
    }
    void OnTrack(scoped_refptr<RtpTransceiverInterface> transceiver) override {
        if (on_event) on_event(user, KRTC_EVENT_TRACK, 0, new krtc_transceiver{transceiver}, new krtc_track{transceiver->receiver()->track(), nullptr});
    }
};

KRTC_API krtc_factory *krtc_factory_create(void) { return krtc_factory_create_with(KRTC_AUDIO_SYNTHETIC, 1); }

KRTC_API krtc_factory *krtc_factory_create_with(int audio_mode, int platform_video_codecs) {
    auto *k = new krtc_factory();
    k->network = Thread::CreateWithSocketServer();
    k->worker = Thread::Create();
    k->signaling = Thread::Create();
    k->network->SetName("krtc-network", nullptr);
    k->worker->SetName("krtc-worker", nullptr);
    k->signaling->SetName("krtc-signaling", nullptr);
    if (!k->network->Start() || !k->worker->Start() || !k->signaling->Start()) {
        delete k;
        return nullptr;
    }
    k->synthetic_audio = audio_mode != KRTC_AUDIO_PLATFORM;
    k->adm = k->worker->BlockingCall([&] {
        return k->synthetic_audio ? krtc::CreateSyntheticAudioDevice()
                                  : CreateAudioDeviceModule(CreateEnvironment(), AudioDeviceModule::kPlatformDefaultAudio);
    });
    std::unique_ptr<VideoEncoderFactory> encoders = platform_video_codecs ? krtc::PlatformVideoEncoderFactory() : nullptr;
    std::unique_ptr<VideoDecoderFactory> decoders = platform_video_codecs ? krtc::PlatformVideoDecoderFactory() : nullptr;
    if (!encoders) encoders = std::make_unique<VideoEncoderFactoryTemplate<LibvpxVp8EncoderTemplateAdapter>>();
    if (!decoders) decoders = std::make_unique<VideoDecoderFactoryTemplate<LibvpxVp8DecoderTemplateAdapter>>();
    k->apm = BuiltinAudioProcessingBuilder().Build(CreateEnvironment());
    k->factory = CreatePeerConnectionFactory(
        k->network.get(), k->worker.get(), k->signaling.get(), k->adm,
        CreateBuiltinAudioEncoderFactory(), CreateBuiltinAudioDecoderFactory(),
        std::move(encoders), std::move(decoders), nullptr, k->apm, std::make_unique<InputGate>(k->gate));
    if (!k->factory) {
        delete k;
        return nullptr;
    }
    ApplyProcessing(k);
    return k;
}

KRTC_API void krtc_factory_release(krtc_factory *factory) {
    if (!factory) return;
    factory->factory = nullptr;
    factory->adm = nullptr;
    factory->apm = nullptr;
    delete factory;
}

KRTC_API void krtc_factory_set_test_tone(krtc_factory *f, int enabled) {
    if (f->synthetic_audio) krtc::SetTestTone(f->adm.get(), enabled != 0);
}

KRTC_API int krtc_factory_push_audio(krtc_factory *f, const int16_t *samples, int count) {
    return f->synthetic_audio ? krtc::PushAudio(f->adm.get(), samples, count) : 0;
}

KRTC_API int krtc_factory_pull_audio(krtc_factory *f, int16_t *out, int count) {
    return f->synthetic_audio ? krtc::PullAudio(f->adm.get(), out, count) : 0;
}

KRTC_API double krtc_factory_output_level(krtc_factory *f) {
    return f->synthetic_audio ? krtc::OutputLevel(f->adm.get()) : 0.0;
}

KRTC_API char *krtc_factory_video_codecs(krtc_factory *f) {
    std::string out;
    for (const RtpCodecCapability &c : f->factory->GetRtpSenderCapabilities(MediaType::VIDEO).codecs) {
        if (out.find(c.mime_type()) != std::string::npos) continue;
        if (!out.empty()) out += ' ';
        out += c.mime_type();
    }
    return Dup(out);
}

KRTC_API void krtc_factory_set_audio_processing(krtc_factory *f, int echo_cancellation, int noise_suppression, int auto_gain_control) {
    f->echo_cancellation = echo_cancellation != 0;
    f->noise_suppression = noise_suppression != 0;
    f->auto_gain_control = auto_gain_control != 0;
    ApplyProcessing(f);
}

KRTC_API void krtc_factory_set_input_gate(krtc_factory *f, int mode, double threshold, int hold_ms) {
    f->gate->threshold = threshold;
    f->gate->hold_ms = hold_ms;
    f->gate->mode = mode;
}

KRTC_API double krtc_factory_input_level(krtc_factory *f) { return f->gate->level.load(); }

KRTC_API int krtc_factory_input_gate_open(krtc_factory *f) { return f->gate->open.load() ? 1 : 0; }

KRTC_API krtc_track *krtc_factory_create_audio_track(krtc_factory *factory, const char *id) {
    scoped_refptr<AudioSourceInterface> source = factory->factory->CreateAudioSource(MicrophoneOptions(factory));
    scoped_refptr<AudioTrackInterface> track = factory->factory->CreateAudioTrack(id, source.get());
    return track ? new krtc_track{track, nullptr} : nullptr;
}

KRTC_API krtc_track *krtc_factory_create_test_video_track(krtc_factory *factory, const char *id, int width, int height, int fps) {
    auto source = make_ref_counted<TestVideoSource>(width, height, fps > 0 ? fps : 15);
    scoped_refptr<VideoTrackInterface> track = factory->factory->CreateVideoTrack(source, id);
    return track ? new krtc_track{track, nullptr} : nullptr;
}

namespace {
/** libwebrtc initializes the device module only for the first call; listing devices before that needs it too. */
void EnsureInitialized(AudioDeviceModule *adm) {
    if (!adm->Initialized()) adm->Init();
}
}  // namespace

KRTC_API int krtc_factory_audio_device_count(krtc_factory *f, int output) {
    if (f->synthetic_audio) return 0;
    return f->worker->BlockingCall([&] {
        EnsureInitialized(f->adm.get());
        return std::max(0, static_cast<int>(output ? f->adm->PlayoutDevices() : f->adm->RecordingDevices()));
    });
}

KRTC_API char *krtc_factory_audio_device_name(krtc_factory *f, int output, int index) {
    if (f->synthetic_audio || index < 0) return nullptr;
    char name[kAdmMaxDeviceNameSize] = {0};
    char guid[kAdmMaxGuidSize] = {0};
    int32_t r = f->worker->BlockingCall([&] {
        EnsureInitialized(f->adm.get());
        return output ? f->adm->PlayoutDeviceName(static_cast<uint16_t>(index), name, guid)
                      : f->adm->RecordingDeviceName(static_cast<uint16_t>(index), name, guid);
    });
    return r == 0 ? Dup(name) : nullptr;
}

KRTC_API int krtc_factory_set_audio_device(krtc_factory *f, int output, int index) {
    if (f->synthetic_audio || index < 0) return -1;
    return f->worker->BlockingCall([&] {
        AudioDeviceModule *adm = f->adm.get();
        EnsureInitialized(adm);
        const auto i = static_cast<uint16_t>(index);
        if (output) {
            const bool playing = adm->Playing();
            if (playing) adm->StopPlayout();
            int32_t r = adm->SetPlayoutDevice(i);
            if (playing && adm->InitPlayout() == 0) adm->StartPlayout();
            return static_cast<int>(r);
        }
        const bool recording = adm->Recording();
        if (recording) adm->StopRecording();
        int32_t r = adm->SetRecordingDevice(i);
        if (recording && adm->InitRecording() == 0) adm->StartRecording();
        return static_cast<int>(r);
    });
}

KRTC_API int krtc_factory_audio_device(krtc_factory *f, int output) {
    if (f->synthetic_audio) return -1;
    return f->worker->BlockingCall([&] { return static_cast<int>(output ? f->adm->GetPlayoutDevice() : f->adm->GetRecordingDevice()); });
}

KRTC_API krtc_video_source *krtc_factory_create_video_source(krtc_factory *) {
    return new krtc_video_source{make_ref_counted<krtc::PushVideoSource>(), nullptr};
}

KRTC_API krtc_video_source *krtc_factory_create_screencast_source(krtc_factory *) {
    return new krtc_video_source{make_ref_counted<krtc::PushVideoSource>(true), nullptr};
}

KRTC_API char *krtc_screen_sources(void) {
    std::string out;
    for (const auto &s : krtc::ScreenSources()) {
        std::string title = s.title;
        std::replace(title.begin(), title.end(), '\n', ' ');
        out += std::to_string(s.id) + (s.window ? " w " : " s ") + title + "\n";
    }
    return Dup(out);
}

KRTC_API krtc_video_source *krtc_factory_create_screen_source(krtc_factory *, int64_t id, int window, int fps, int max_width, int max_height) {
    auto source = make_ref_counted<krtc::PushVideoSource>(true);
    std::function<void()> stop = krtc::StartScreen(id, window != 0, fps > 0 ? fps : 15, max_width, max_height, source.get());
    if (!stop) return nullptr;
    return new krtc_video_source{source, std::move(stop)};
}

KRTC_API int krtc_camera_count(void) { return krtc::CameraCount(); }

KRTC_API char *krtc_camera_name(int index) { return Dup(krtc::CameraName(index)); }

KRTC_API krtc_video_source *krtc_factory_create_camera_source(krtc_factory *, int index, int width, int height, int fps) {
    auto source = make_ref_counted<krtc::PushVideoSource>();
    std::function<void()> stop = krtc::StartCamera(index, width, height, fps > 0 ? fps : 30, source.get());
    if (!stop) return nullptr;
    return new krtc_video_source{source, std::move(stop)};
}

namespace {
VideoRotation Rotation(int degrees) {
    switch (degrees) {
        case 90: return kVideoRotation_90;
        case 180: return kVideoRotation_180;
        case 270: return kVideoRotation_270;
        default: return kVideoRotation_0;
    }
}
}  // namespace

KRTC_API void krtc_video_source_push_i420(krtc_video_source *s, int width, int height, const uint8_t *y, int stride_y,
                                          const uint8_t *u, int stride_u, const uint8_t *v, int stride_v, int rotation,
                                          int64_t timestamp_us) {
    scoped_refptr<I420Buffer> buf = I420Buffer::Copy(width, height, y, stride_y, u, stride_u, v, stride_v);
    s->source->Push(VideoFrame::Builder()
                        .set_video_frame_buffer(buf)
                        .set_rotation(Rotation(rotation))
                        .set_timestamp_us(timestamp_us > 0 ? timestamp_us : TimeMicros())
                        .build());
}

KRTC_API void krtc_video_source_push_argb(krtc_video_source *s, int width, int height, const uint32_t *argb, int rotation, int64_t timestamp_us) {
    scoped_refptr<I420Buffer> buf = I420Buffer::Create(width, height);
    libyuv::ARGBToI420(reinterpret_cast<const uint8_t *>(argb), width * 4, buf->MutableDataY(), buf->StrideY(), buf->MutableDataU(),
                       buf->StrideU(), buf->MutableDataV(), buf->StrideV(), width, height);
    s->source->Push(VideoFrame::Builder()
                        .set_video_frame_buffer(buf)
                        .set_rotation(Rotation(rotation))
                        .set_timestamp_us(timestamp_us > 0 ? timestamp_us : TimeMicros())
                        .build());
}

KRTC_API void krtc_video_source_push_rgba(krtc_video_source *s, int width, int height, const uint8_t *rgba, int stride, int rotation, int64_t timestamp_us) {
    scoped_refptr<I420Buffer> buffer = I420Buffer::Create(width, height);
    libyuv::ABGRToI420(rgba, stride, buffer->MutableDataY(), buffer->StrideY(), buffer->MutableDataU(), buffer->StrideU(),
                       buffer->MutableDataV(), buffer->StrideV(), width, height);
    s->source->Push(VideoFrame::Builder()
                        .set_video_frame_buffer(buffer)
                        .set_rotation(Rotation(rotation))
                        .set_timestamp_us(timestamp_us > 0 ? timestamp_us : TimeMicros())
                        .build());
}

namespace krtc {
void PushBgra(PushVideoSource *source, const uint8_t *bgra, int stride, int width, int height, int maxWidth, int maxHeight, int64_t timestampUs) {
    scoped_refptr<I420Buffer> full = I420Buffer::Create(width, height);
    libyuv::ARGBToI420(bgra, stride, full->MutableDataY(), full->StrideY(), full->MutableDataU(), full->StrideU(),
                       full->MutableDataV(), full->StrideV(), width, height);
    double scale = 1.0;
    if (maxWidth > 0) scale = std::min(scale, static_cast<double>(maxWidth) / width);
    if (maxHeight > 0) scale = std::min(scale, static_cast<double>(maxHeight) / height);
    scoped_refptr<I420Buffer> out = full;
    if (scale < 1.0) {
        out = I420Buffer::Create(std::max(2, static_cast<int>(width * scale)) & ~1, std::max(2, static_cast<int>(height * scale)) & ~1);
        out->ScaleFrom(*full);
    }
    source->Push(VideoFrame::Builder().set_video_frame_buffer(out).set_timestamp_us(timestampUs > 0 ? timestampUs : TimeMicros()).build());
}
}  // namespace krtc

KRTC_API krtc_track *krtc_factory_create_video_track(krtc_factory *factory, const char *id, krtc_video_source *s) {
    scoped_refptr<VideoTrackInterface> track = factory->factory->CreateVideoTrack(s->source, id);
    if (track && s->source->is_screencast()) track->set_content_hint(VideoTrackInterface::ContentHint::kDetailed);
    return track ? new krtc_track{track, nullptr} : nullptr;
}

KRTC_API void krtc_video_source_release(krtc_video_source *s) { delete s; }

namespace krtc {
void PushAudioSource::AddSink(AudioTrackSinkInterface *sink) {
    std::lock_guard<std::mutex> g(lock_);
    sinks_.push_back(sink);
}

void PushAudioSource::RemoveSink(AudioTrackSinkInterface *sink) {
    std::lock_guard<std::mutex> g(lock_);
    sinks_.erase(std::remove(sinks_.begin(), sinks_.end(), sink), sinks_.end());
}

void PushAudioSource::Push(const int16_t *samples, size_t frames, int channels, int sampleRate) {
    if (channels <= 0 || sampleRate <= 0) return;
    std::lock_guard<std::mutex> g(lock_);
    if (channels != channels_ || sampleRate != rate_) {
        pending_.clear(); // format changed: start the 10 ms framing over
        channels_ = channels;
        rate_ = sampleRate;
    }
    pending_.insert(pending_.end(), samples, samples + frames * channels);
    const size_t chunk = static_cast<size_t>(sampleRate / 100) * channels;
    size_t at = 0;
    for (; pending_.size() - at >= chunk; at += chunk) {
        for (auto *sink : sinks_) sink->OnData(pending_.data() + at, 16, sampleRate, channels, chunk / channels);
    }
    pending_.erase(pending_.begin(), pending_.begin() + at);
    if (pending_.size() > chunk * 50) pending_.clear(); // nobody consuming for half a second: drop, do not grow
}

void PushFloat(PushAudioSource *source, const float *samples, size_t frames, int channels, int sampleRate) {
    std::vector<int16_t> pcm(frames * channels);
    for (size_t i = 0; i < pcm.size(); i++) pcm[i] = static_cast<int16_t>(std::clamp(samples[i], -1.0f, 1.0f) * 32767.0f);
    source->Push(pcm.data(), frames, channels, sampleRate);
}
}  // namespace krtc

KRTC_API krtc_audio_source *krtc_factory_create_audio_source(krtc_factory *) {
    return new krtc_audio_source{make_ref_counted<krtc::PushAudioSource>(), nullptr};
}

KRTC_API krtc_audio_source *krtc_factory_create_system_audio_source(krtc_factory *) {
    auto source = make_ref_counted<krtc::PushAudioSource>();
    std::function<void()> stop = krtc::StartSystemAudio(source.get());
    if (!stop) return nullptr;
    return new krtc_audio_source{source, std::move(stop)};
}

KRTC_API void krtc_audio_source_push(krtc_audio_source *s, const int16_t *samples, int frames, int channels, int sample_rate) {
    if (frames > 0) s->source->Push(samples, static_cast<size_t>(frames), channels, sample_rate);
}

KRTC_API krtc_track *krtc_factory_create_audio_track_from(krtc_factory *factory, const char *id, krtc_audio_source *s) {
    scoped_refptr<AudioTrackInterface> track = factory->factory->CreateAudioTrack(id, s->source.get());
    return track ? new krtc_track{track, nullptr} : nullptr;
}

KRTC_API void krtc_audio_source_release(krtc_audio_source *s) { delete s; }

KRTC_API krtc_pc *krtc_pc_create(krtc_factory *factory, krtc_event_fn on_event, void *user) {
    auto *p = new krtc_pc();
    p->on_event = on_event;
    p->user = user;
    PeerConnectionInterface::RTCConfiguration config;
    config.sdp_semantics = SdpSemantics::kUnifiedPlan;
    PeerConnectionDependencies deps(p);
    auto result = factory->factory->CreatePeerConnectionOrError(config, std::move(deps));
    if (!result.ok()) {
        delete p;
        return nullptr;
    }
    p->pc = result.MoveValue();
    return p;
}

KRTC_API void krtc_pc_set_remote_description(krtc_pc *pc, const char *type, const char *sdp, krtc_done_fn done, void *user) {
    std::string error;
    auto desc = Parse(type, sdp, &error);
    if (!desc) {
        done(user, nullptr, error.c_str());
        return;
    }
    pc->pc->SetRemoteDescription(std::move(desc), make_ref_counted<DoneObserver>(done, user));
}

KRTC_API void krtc_pc_create_answer(krtc_pc *pc, krtc_done_fn done, void *user) {
    pc->pc->CreateAnswer(make_ref_counted<AnswerObserver>(done, user).get(), PeerConnectionInterface::RTCOfferAnswerOptions());
}

KRTC_API void krtc_pc_set_local_description(krtc_pc *pc, const char *type, const char *sdp, krtc_done_fn done, void *user) {
    std::string error;
    auto desc = Parse(type, sdp, &error);
    if (!desc) {
        done(user, nullptr, error.c_str());
        return;
    }
    pc->pc->SetLocalDescription(std::move(desc), make_ref_counted<DoneObserver>(done, user));
}

KRTC_API char *krtc_pc_local_description(krtc_pc *pc) {
    const SessionDescriptionInterface *d = pc->pc->local_description();
    std::string sdp;
    return d && d->ToString(&sdp) ? Dup(sdp) : nullptr;
}

KRTC_API char *krtc_pc_remote_description(krtc_pc *pc) {
    const SessionDescriptionInterface *d = pc->pc->remote_description();
    std::string sdp;
    return d && d->ToString(&sdp) ? Dup(sdp) : nullptr;
}

KRTC_API void krtc_pc_get_stats(krtc_pc *pc, krtc_done_fn done, void *user) {
    pc->pc->GetStats(make_ref_counted<StatsCallback>(done, user).get());
}

KRTC_API int krtc_pc_transceiver_count(krtc_pc *pc) { return static_cast<int>(pc->pc->GetTransceivers().size()); }

KRTC_API krtc_transceiver *krtc_pc_transceiver_at(krtc_pc *pc, int index) {
    auto all = pc->pc->GetTransceivers();
    if (index < 0 || index >= static_cast<int>(all.size())) return nullptr;
    return new krtc_transceiver{all[index]};
}

KRTC_API void krtc_pc_close(krtc_pc *pc) { pc->pc->Close(); }

KRTC_API krtc_data_channel *krtc_pc_create_data_channel(krtc_pc *pc, const char *label, int ordered, int max_retransmits,
                                                        int max_packet_lifetime, const char *protocol, int negotiated, int id) {
    DataChannelInit init;
    init.ordered = ordered != 0;
    if (max_retransmits >= 0) init.maxRetransmits = max_retransmits;
    if (max_packet_lifetime >= 0) init.maxRetransmitTime = max_packet_lifetime;
    init.protocol = protocol ? protocol : "";
    init.negotiated = negotiated != 0;
    init.id = id;
    auto result = pc->pc->CreateDataChannelOrError(label ? label : "", &init);
    return result.ok() ? new krtc_data_channel(result.MoveValue()) : nullptr;
}

KRTC_API void krtc_pc_set_data_channel_handler(krtc_pc *pc, krtc_data_channel_fn fn, void *user) {
    pc->data_channel_user = user;
    pc->on_data_channel = fn;
}

KRTC_API void krtc_data_channel_set_observer(krtc_data_channel *c, krtc_data_channel_event_fn fn, void *user) {
    if (fn) {
        c->user = user;
        c->fn = fn;
        if (!c->registered) {
            c->registered = true;
            c->dc->RegisterObserver(c); // delivers queued messages now
        }
    } else {
        if (c->registered) {
            c->registered = false;
            c->dc->UnregisterObserver(); // synchronous: no callbacks after this returns
        }
        c->fn = nullptr;
        c->user = nullptr;
    }
}

KRTC_API char *krtc_data_channel_label(krtc_data_channel *c) { return Dup(c->dc->label()); }
KRTC_API char *krtc_data_channel_protocol(krtc_data_channel *c) { return Dup(c->dc->protocol()); }
KRTC_API int krtc_data_channel_id(krtc_data_channel *c) { return c->dc->id(); }
KRTC_API int krtc_data_channel_ordered(krtc_data_channel *c) { return c->dc->ordered() ? 1 : 0; }
KRTC_API int krtc_data_channel_max_retransmits(krtc_data_channel *c) { return c->dc->maxRetransmitsOpt().value_or(-1); }
KRTC_API int krtc_data_channel_max_packet_lifetime(krtc_data_channel *c) { return c->dc->maxPacketLifeTime().value_or(-1); }
KRTC_API int krtc_data_channel_negotiated(krtc_data_channel *c) { return c->dc->negotiated() ? 1 : 0; }
KRTC_API int krtc_data_channel_state(krtc_data_channel *c) { return static_cast<int>(c->dc->state()); }
KRTC_API uint64_t krtc_data_channel_buffered_amount(krtc_data_channel *c) { return c->dc->buffered_amount(); }

KRTC_API int krtc_data_channel_send(krtc_data_channel *c, const uint8_t *data, int length, int binary) {
    if (length < 0) return -1;
    return c->dc->Send(DataBuffer(CopyOnWriteBuffer(data, static_cast<size_t>(length)), binary != 0)) ? 0 : -1;
}

KRTC_API void krtc_data_channel_close(krtc_data_channel *c) { c->dc->Close(); }
KRTC_API void krtc_data_channel_release(krtc_data_channel *c) { delete c; }

KRTC_API void krtc_pc_release(krtc_pc *pc) {
    if (!pc) return;
    pc->pc->Close();
    pc->pc = nullptr;
    delete pc;
}

KRTC_API char *krtc_transceiver_mid(krtc_transceiver *t) {
    std::optional<std::string> mid = t->transceiver->mid();
    return mid ? Dup(*mid) : nullptr;
}

KRTC_API int krtc_transceiver_direction(krtc_transceiver *t) {
    switch (t->transceiver->direction()) {
        case RtpTransceiverDirection::kSendRecv: return KRTC_DIRECTION_SENDRECV;
        case RtpTransceiverDirection::kSendOnly: return KRTC_DIRECTION_SENDONLY;
        case RtpTransceiverDirection::kRecvOnly: return KRTC_DIRECTION_RECVONLY;
        case RtpTransceiverDirection::kInactive: return KRTC_DIRECTION_INACTIVE;
        default: return KRTC_DIRECTION_STOPPED;
    }
}

KRTC_API int krtc_transceiver_set_direction(krtc_transceiver *t, int direction) {
    RtpTransceiverDirection d;
    switch (direction) {
        case KRTC_DIRECTION_SENDRECV: d = RtpTransceiverDirection::kSendRecv; break;
        case KRTC_DIRECTION_SENDONLY: d = RtpTransceiverDirection::kSendOnly; break;
        case KRTC_DIRECTION_RECVONLY: d = RtpTransceiverDirection::kRecvOnly; break;
        case KRTC_DIRECTION_INACTIVE: d = RtpTransceiverDirection::kInactive; break;
        default: return -1;
    }
    return t->transceiver->SetDirectionWithError(d).ok() ? 0 : -1;
}

KRTC_API int krtc_transceiver_set_sender_track(krtc_transceiver *t, krtc_track *track) {
    return t->transceiver->sender()->SetTrack(track ? track->track.get() : nullptr) ? 0 : -1;
}

KRTC_API krtc_track *krtc_transceiver_receiver_track(krtc_transceiver *t) {
    return new krtc_track{t->transceiver->receiver()->track(), nullptr};
}

KRTC_API int krtc_transceiver_prefer_codec(krtc_factory *f, krtc_transceiver *t, const char *mime) {
    const MediaType kind = t->transceiver->media_type();
    std::vector<RtpCodecCapability> codecs = f->factory->GetRtpReceiverCapabilities(kind).codecs;
    std::stable_partition(codecs.begin(), codecs.end(), [&](const RtpCodecCapability &c) { return c.mime_type() == mime; });
    return t->transceiver->SetCodecPreferences(codecs).ok() ? 0 : -1;
}

KRTC_API int krtc_transceiver_set_send_codec(krtc_transceiver *t, const char *mime) {
    auto sender = t->transceiver->sender();
    RtpParameters params = sender->GetParameters();
    auto it = std::find_if(params.codecs.begin(), params.codecs.end(), [&](const RtpCodecParameters &c) { return c.mime_type() == mime; });
    if (it == params.codecs.end()) return -1;
    for (auto &encoding : params.encodings) encoding.codec = *it;
    return sender->SetParameters(params).ok() ? 0 : -1;
}

KRTC_API void krtc_transceiver_release(krtc_transceiver *t) { delete t; }

KRTC_API char *krtc_track_id(krtc_track *track) { return Dup(track->track->id()); }

KRTC_API int krtc_track_is_video(krtc_track *track) {
    return track->track->kind() == MediaStreamTrackInterface::kVideoKind ? 1 : 0;
}

KRTC_API int krtc_track_enabled(krtc_track *track) { return track->track->enabled() ? 1 : 0; }

KRTC_API void krtc_track_set_enabled(krtc_track *track, int enabled) { track->track->set_enabled(enabled != 0); }

KRTC_API void krtc_track_set_volume(krtc_track *track, double volume) {
    if (track->track->kind() != MediaStreamTrackInterface::kAudioKind) return;
    AudioSourceInterface *source = static_cast<AudioTrackInterface *>(track->track.get())->GetSource();
    if (source) source->SetVolume(std::clamp(volume, 0.0, 10.0));
}

KRTC_API void krtc_track_video_stats(krtc_track *track, int64_t *frames, int *width, int *height) {
    if (track->track->kind() != MediaStreamTrackInterface::kVideoKind) {
        *frames = 0; *width = 0; *height = 0;
        return;
    }
    if (!track->counter) {
        track->counter = std::make_unique<FrameCounter>();
        static_cast<VideoTrackInterface *>(track->track.get())->AddOrUpdateSink(track->counter.get(), VideoSinkWants());
    }
    *frames = track->counter->frames.load();
    *width = track->counter->width.load();
    *height = track->counter->height.load();
}

KRTC_API void krtc_track_set_frame_sink(krtc_track *track, krtc_frame_fn fn, void *user) {
    if (track->track->kind() != MediaStreamTrackInterface::kVideoKind) return;
    auto *video = static_cast<VideoTrackInterface *>(track->track.get());
    if (track->sink) {
        video->RemoveSink(track->sink.get()); // synchronous: no callback runs after this returns
        track->sink.reset();
    }
    if (!fn) return;
    track->sink = std::make_unique<ArgbSink>(fn, user);
    VideoSinkWants wants;
    wants.rotation_applied = true;
    video->AddOrUpdateSink(track->sink.get(), wants);
}

KRTC_API void krtc_track_release(krtc_track *track) { delete track; }

KRTC_API void krtc_free(void *p) { std::free(p); }
