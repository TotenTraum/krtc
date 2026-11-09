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
#include <string>
#include <thread>

#include "api/audio/create_audio_device_module.h"
#include "api/audio_codecs/builtin_audio_decoder_factory.h"
#include "api/audio_codecs/builtin_audio_encoder_factory.h"
#include "api/create_peerconnection_factory.h"
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

struct krtc_factory {
    std::unique_ptr<Thread> network, worker, signaling;
    scoped_refptr<AudioDeviceModule> adm;
    bool synthetic_audio = false;
    scoped_refptr<PeerConnectionFactoryInterface> factory;
};

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

struct krtc_track {
    scoped_refptr<MediaStreamTrackInterface> track;
    std::unique_ptr<FrameCounter> counter;
    ~krtc_track() {
        if (counter && track->kind() == MediaStreamTrackInterface::kVideoKind) {
            static_cast<VideoTrackInterface *>(track.get())->RemoveSink(counter.get());
        }
    }
};

struct krtc_transceiver {
    scoped_refptr<RtpTransceiverInterface> transceiver;
};

struct krtc_pc : public PeerConnectionObserver {
    krtc_event_fn on_event = nullptr;
    void *user = nullptr;
    scoped_refptr<PeerConnectionInterface> pc;

    void OnSignalingChange(PeerConnectionInterface::SignalingState) override {}
    void OnDataChannel(scoped_refptr<DataChannelInterface>) override {}
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
    k->factory = CreatePeerConnectionFactory(
        k->network.get(), k->worker.get(), k->signaling.get(), k->adm,
        CreateBuiltinAudioEncoderFactory(), CreateBuiltinAudioDecoderFactory(),
        std::move(encoders), std::move(decoders), nullptr, nullptr);
    if (!k->factory) {
        delete k;
        return nullptr;
    }
    return k;
}

KRTC_API void krtc_factory_release(krtc_factory *factory) {
    if (!factory) return;
    factory->factory = nullptr;
    factory->adm = nullptr;
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

KRTC_API krtc_track *krtc_factory_create_audio_track(krtc_factory *factory, const char *id) {
    scoped_refptr<AudioSourceInterface> source = factory->factory->CreateAudioSource(AudioOptions());
    scoped_refptr<AudioTrackInterface> track = factory->factory->CreateAudioTrack(id, source.get());
    return track ? new krtc_track{track, nullptr} : nullptr;
}

KRTC_API krtc_track *krtc_factory_create_test_video_track(krtc_factory *factory, const char *id, int width, int height, int fps) {
    auto source = make_ref_counted<TestVideoSource>(width, height, fps > 0 ? fps : 15);
    scoped_refptr<VideoTrackInterface> track = factory->factory->CreateVideoTrack(source, id);
    return track ? new krtc_track{track, nullptr} : nullptr;
}

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

KRTC_API void krtc_track_release(krtc_track *track) { delete track; }

KRTC_API void krtc_free(void *p) { std::free(p); }
