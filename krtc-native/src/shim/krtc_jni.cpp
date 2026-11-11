// JNI bridge from krtc.h to the JVM and Android (ADR-0002: one JNI layer over the same C ABI).
// Kotlin side: krtc.native.Jni (jvmAndAndroidMain). Handles are passed as jlong.
//
// Callbacks arrive on libwebrtc threads; they attach as daemon threads and call into Kotlin objects held
// by global references:
//   listener.onEvent(int event, int state, long transceiver, long track)   for a peer connection
//   done.complete(String result, String error)                             once per asynchronous call
//   channel.onDataEvent(int event, int value, byte[] data)                  for a data channel
#include <jni.h>

#include <cstdint>
#include <cstdlib>

#include "krtc.h"

namespace {

JavaVM *g_vm = nullptr;
jmethodID g_on_event = nullptr;
jmethodID g_complete = nullptr;
jmethodID g_on_frame = nullptr;
jmethodID g_on_data = nullptr;

/** Peer connection event for a data channel the remote side opened (the channel handle in the 3rd argument). */
constexpr int EVENT_DATA_CHANNEL = 100;

JNIEnv *Env() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
#if defined(__ANDROID__)
    g_vm->AttachCurrentThreadAsDaemon(&env, nullptr);
#else
    g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr);
#endif
    return env;
}

jstring Str(JNIEnv *env, const char *s) { return s ? env->NewStringUTF(s) : nullptr; }

/** Takes a krtc-allocated string, frees it. */
jstring Take(JNIEnv *env, char *s) {
    jstring result = Str(env, s);
    krtc_free(s);
    return result;
}

/** Scoped UTF-8 view of a Java string. */
class Utf {
public:
    Utf(JNIEnv *env, jstring s) : env_(env), s_(s), p_(s ? env->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~Utf() { if (p_) env_->ReleaseStringUTFChars(s_, p_); }
    const char *get() const { return p_; }
private:
    JNIEnv *env_;
    jstring s_;
    const char *p_;
};

/** A peer connection plus the global reference to its Kotlin listener. */
struct Pc {
    krtc_pc *pc = nullptr;
    jobject listener = nullptr;
};

void OnEvent(void *user, int event, int state, krtc_transceiver *transceiver, krtc_track *track) {
    JNIEnv *env = Env();
    env->CallVoidMethod(static_cast<Pc *>(user)->listener, g_on_event, event, state,
                        static_cast<jlong>(reinterpret_cast<intptr_t>(transceiver)), static_cast<jlong>(reinterpret_cast<intptr_t>(track)));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void OnRemoteDataChannel(void *user, krtc_data_channel *channel) {
    JNIEnv *env = Env();
    env->CallVoidMethod(static_cast<Pc *>(user)->listener, g_on_event, EVENT_DATA_CHANNEL, 0,
                        static_cast<jlong>(reinterpret_cast<intptr_t>(channel)), static_cast<jlong>(0));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

/** A data channel's Kotlin listener: onDataEvent(int event, int value, byte[] data). */
struct DataSink {
    jobject listener = nullptr;
};

void OnDataChannelEvent(void *user, int event, int value, const uint8_t *data, int length) {
    JNIEnv *env = Env();
    jbyteArray bytes = nullptr;
    if (event == KRTC_DATA_CHANNEL_MESSAGE) {
        bytes = env->NewByteArray(length);
        if (!bytes) {
            env->ExceptionClear();
            return;
        }
        if (length > 0) env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(data));
    }
    env->CallVoidMethod(static_cast<DataSink *>(user)->listener, g_on_data, event, value, bytes);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (bytes) env->DeleteLocalRef(bytes);
}

void OnDone(void *user, const char *result, const char *error) {
    JNIEnv *env = Env();
    auto done = static_cast<jobject>(user);
    jstring r = Str(env, result);
    jstring e = Str(env, error);
    env->CallVoidMethod(done, g_complete, r, e);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (r) env->DeleteLocalRef(r);
    if (e) env->DeleteLocalRef(e);
    env->DeleteGlobalRef(done);
}

/** A track's frame sink: the Kotlin listener and an int[] reused while the frame size stays the same. */
struct FrameSink {
    jobject listener = nullptr;
    jintArray pixels = nullptr;
    int size = 0;
};

void OnFrame(void *user, int width, int height, const uint32_t *argb, int64_t timestamp_us) {
    JNIEnv *env = Env();
    auto *sink = static_cast<FrameSink *>(user);
    const int n = width * height;
    if (sink->size != n) {
        if (sink->pixels) env->DeleteGlobalRef(sink->pixels);
        jintArray local = env->NewIntArray(n);
        if (!local) {
            env->ExceptionClear();
            return;
        }
        sink->pixels = static_cast<jintArray>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        sink->size = n;
    }
    env->SetIntArrayRegion(sink->pixels, 0, n, reinterpret_cast<const jint *>(argb));
    env->CallVoidMethod(sink->listener, g_on_frame, width, height, sink->pixels, static_cast<jlong>(timestamp_us));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

template <typename T> T *H(jlong handle) { return reinterpret_cast<T *>(static_cast<intptr_t>(handle)); }
template <typename T> jlong J(T *p) { return static_cast<jlong>(reinterpret_cast<intptr_t>(p)); }

}  // namespace

#define KRTC_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_krtc_native_Jni_##name

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass listener = env->FindClass("krtc/native/Jni$Listener");
    jclass done = env->FindClass("krtc/native/Jni$Done");
    jclass frames = env->FindClass("krtc/native/Jni$FrameListener");
    jclass data = env->FindClass("krtc/native/Jni$DataChannelListener");
    if (!listener || !done || !frames || !data) return JNI_ERR;
    g_on_data = env->GetMethodID(data, "onDataEvent", "(II[B)V");
    g_on_frame = env->GetMethodID(frames, "onFrame", "(II[IJ)V");
    g_on_event = env->GetMethodID(listener, "onEvent", "(IIJJ)V");
    g_complete = env->GetMethodID(done, "complete", "(Ljava/lang/String;Ljava/lang/String;)V");
    return g_on_event && g_complete && g_on_frame && g_on_data ? JNI_VERSION_1_6 : JNI_ERR;
}

// Factory.
KRTC_JNI(jlong, factoryCreate)(JNIEnv *, jclass, jint audio, jint codecs) { return J(krtc_factory_create_with(audio, codecs)); }
KRTC_JNI(void, factoryRelease)(JNIEnv *, jclass, jlong f) { krtc_factory_release(H<krtc_factory>(f)); }
KRTC_JNI(void, factorySetTestTone)(JNIEnv *, jclass, jlong f, jboolean on) { krtc_factory_set_test_tone(H<krtc_factory>(f), on ? 1 : 0); }
KRTC_JNI(jint, factoryPushAudio)(JNIEnv *env, jclass, jlong f, jshortArray samples, jint count) {
    jshort *p = env->GetShortArrayElements(samples, nullptr);
    int n = krtc_factory_push_audio(H<krtc_factory>(f), p, count);
    env->ReleaseShortArrayElements(samples, p, JNI_ABORT);
    return n;
}
KRTC_JNI(jint, factoryPullAudio)(JNIEnv *env, jclass, jlong f, jshortArray out) {
    jshort *p = env->GetShortArrayElements(out, nullptr);
    int n = krtc_factory_pull_audio(H<krtc_factory>(f), p, env->GetArrayLength(out));
    env->ReleaseShortArrayElements(out, p, 0);
    return n;
}
KRTC_JNI(jdouble, factoryOutputLevel)(JNIEnv *, jclass, jlong f) { return krtc_factory_output_level(H<krtc_factory>(f)); }
KRTC_JNI(jstring, factoryVideoCodecs)(JNIEnv *env, jclass, jlong f) { return Take(env, krtc_factory_video_codecs(H<krtc_factory>(f))); }
KRTC_JNI(jlong, factoryCreateAudioTrack)(JNIEnv *env, jclass, jlong f, jstring id) {
    return J(krtc_factory_create_audio_track(H<krtc_factory>(f), Utf(env, id).get()));
}
KRTC_JNI(jlong, factoryCreateTestVideoTrack)(JNIEnv *env, jclass, jlong f, jstring id, jint w, jint h, jint fps) {
    return J(krtc_factory_create_test_video_track(H<krtc_factory>(f), Utf(env, id).get(), w, h, fps));
}

// Video sources and cameras.
KRTC_JNI(jlong, factoryCreateVideoSource)(JNIEnv *, jclass, jlong f) { return J(krtc_factory_create_video_source(H<krtc_factory>(f))); }
KRTC_JNI(jlong, factoryCreateCameraSource)(JNIEnv *, jclass, jlong f, jint index, jint w, jint h, jint fps) {
    return J(krtc_factory_create_camera_source(H<krtc_factory>(f), index, w, h, fps));
}
KRTC_JNI(jlong, factoryCreateVideoTrack)(JNIEnv *env, jclass, jlong f, jstring id, jlong source) {
    return J(krtc_factory_create_video_track(H<krtc_factory>(f), Utf(env, id).get(), H<krtc_video_source>(source)));
}
KRTC_JNI(jint, cameraCount)(JNIEnv *, jclass) { return krtc_camera_count(); }
KRTC_JNI(jstring, cameraName)(JNIEnv *env, jclass, jint index) { return Take(env, krtc_camera_name(index)); }
KRTC_JNI(void, videoSourcePushI420)(JNIEnv *env, jclass, jlong s, jint w, jint h, jbyteArray y, jint sy, jbyteArray u, jint su,
                                    jbyteArray v, jint sv, jint rotation, jlong ts) {
    jbyte *py = env->GetByteArrayElements(y, nullptr);
    jbyte *pu = env->GetByteArrayElements(u, nullptr);
    jbyte *pv = env->GetByteArrayElements(v, nullptr);
    krtc_video_source_push_i420(H<krtc_video_source>(s), w, h, reinterpret_cast<uint8_t *>(py), sy, reinterpret_cast<uint8_t *>(pu), su,
                                reinterpret_cast<uint8_t *>(pv), sv, rotation, ts);
    env->ReleaseByteArrayElements(v, pv, JNI_ABORT);
    env->ReleaseByteArrayElements(u, pu, JNI_ABORT);
    env->ReleaseByteArrayElements(y, py, JNI_ABORT);
}
KRTC_JNI(void, videoSourcePushArgb)(JNIEnv *env, jclass, jlong s, jint w, jint h, jintArray argb, jint rotation, jlong ts) {
    jint *p = env->GetIntArrayElements(argb, nullptr);
    krtc_video_source_push_argb(H<krtc_video_source>(s), w, h, reinterpret_cast<uint32_t *>(p), rotation, ts);
    env->ReleaseIntArrayElements(argb, p, JNI_ABORT);
}
KRTC_JNI(void, videoSourcePushRgba)(JNIEnv *env, jclass, jlong s, jint w, jint h, jbyteArray rgba, jint stride, jint rotation, jlong ts) {
    jbyte *p = env->GetByteArrayElements(rgba, nullptr);
    krtc_video_source_push_rgba(H<krtc_video_source>(s), w, h, reinterpret_cast<const uint8_t *>(p), stride, rotation, ts);
    env->ReleaseByteArrayElements(rgba, p, JNI_ABORT);
}
KRTC_JNI(void, videoSourcePushRgbaDirect)(JNIEnv *env, jclass, jlong s, jint w, jint h, jobject rgba, jint stride, jint rotation, jlong ts) {
    auto *p = static_cast<const uint8_t *>(env->GetDirectBufferAddress(rgba));
    if (p) krtc_video_source_push_rgba(H<krtc_video_source>(s), w, h, p, stride, rotation, ts);
}
KRTC_JNI(jstring, screenSources)(JNIEnv *env, jclass) { return Take(env, krtc_screen_sources()); }
KRTC_JNI(jlong, factoryCreateScreenSource)(JNIEnv *, jclass, jlong f, jlong id, jboolean window, jint fps, jint maxW, jint maxH) {
    return J(krtc_factory_create_screen_source(H<krtc_factory>(f), id, window ? 1 : 0, fps, maxW, maxH));
}
KRTC_JNI(jlong, factoryCreateScreencastSource)(JNIEnv *, jclass, jlong f) { return J(krtc_factory_create_screencast_source(H<krtc_factory>(f))); }
KRTC_JNI(jlong, factoryCreateAudioSource)(JNIEnv *, jclass, jlong f) { return J(krtc_factory_create_audio_source(H<krtc_factory>(f))); }
KRTC_JNI(jlong, factoryCreateSystemAudioSource)(JNIEnv *, jclass, jlong f) { return J(krtc_factory_create_system_audio_source(H<krtc_factory>(f))); }
KRTC_JNI(jlong, factoryCreateAudioTrackFrom)(JNIEnv *env, jclass, jlong f, jstring id, jlong s) {
    return J(krtc_factory_create_audio_track_from(H<krtc_factory>(f), Utf(env, id).get(), H<krtc_audio_source>(s)));
}
KRTC_JNI(void, audioSourcePush)(JNIEnv *env, jclass, jlong s, jshortArray samples, jint frames, jint channels, jint rate) {
    jshort *p = env->GetShortArrayElements(samples, nullptr);
    krtc_audio_source_push(H<krtc_audio_source>(s), p, frames, channels, rate);
    env->ReleaseShortArrayElements(samples, p, JNI_ABORT);
}
KRTC_JNI(void, audioSourceRelease)(JNIEnv *, jclass, jlong s) { krtc_audio_source_release(H<krtc_audio_source>(s)); }
KRTC_JNI(void, videoSourceRelease)(JNIEnv *, jclass, jlong s) { krtc_video_source_release(H<krtc_video_source>(s)); }

// Platform audio devices.
KRTC_JNI(jint, factoryAudioDeviceCount)(JNIEnv *, jclass, jlong f, jboolean output) { return krtc_factory_audio_device_count(H<krtc_factory>(f), output ? 1 : 0); }
KRTC_JNI(jstring, factoryAudioDeviceName)(JNIEnv *env, jclass, jlong f, jboolean output, jint index) {
    return Take(env, krtc_factory_audio_device_name(H<krtc_factory>(f), output ? 1 : 0, index));
}
KRTC_JNI(jint, factorySetAudioDevice)(JNIEnv *, jclass, jlong f, jboolean output, jint index) {
    return krtc_factory_set_audio_device(H<krtc_factory>(f), output ? 1 : 0, index);
}
KRTC_JNI(void, factorySetAudioProcessing)(JNIEnv *, jclass, jlong f, jboolean echo, jboolean noise, jboolean agc) {
    krtc_factory_set_audio_processing(H<krtc_factory>(f), echo ? 1 : 0, noise ? 1 : 0, agc ? 1 : 0);
}

// Peer connection.
KRTC_JNI(jlong, pcCreate)(JNIEnv *env, jclass, jlong f, jobject listener) {
    auto *p = new Pc();
    p->listener = env->NewGlobalRef(listener);
    p->pc = krtc_pc_create(H<krtc_factory>(f), OnEvent, p);
    if (!p->pc) {
        env->DeleteGlobalRef(p->listener);
        delete p;
        return 0;
    }
    krtc_pc_set_data_channel_handler(p->pc, OnRemoteDataChannel, p);
    return J(p);
}
KRTC_JNI(void, pcSetRemoteDescription)(JNIEnv *env, jclass, jlong pc, jstring type, jstring sdp, jobject done) {
    krtc_pc_set_remote_description(H<Pc>(pc)->pc, Utf(env, type).get(), Utf(env, sdp).get(), OnDone, env->NewGlobalRef(done));
}
KRTC_JNI(void, pcCreateAnswer)(JNIEnv *env, jclass, jlong pc, jobject done) {
    krtc_pc_create_answer(H<Pc>(pc)->pc, OnDone, env->NewGlobalRef(done));
}
KRTC_JNI(void, pcSetLocalDescription)(JNIEnv *env, jclass, jlong pc, jstring type, jstring sdp, jobject done) {
    krtc_pc_set_local_description(H<Pc>(pc)->pc, Utf(env, type).get(), Utf(env, sdp).get(), OnDone, env->NewGlobalRef(done));
}
KRTC_JNI(jstring, pcLocalDescription)(JNIEnv *env, jclass, jlong pc) { return Take(env, krtc_pc_local_description(H<Pc>(pc)->pc)); }
KRTC_JNI(jstring, pcRemoteDescription)(JNIEnv *env, jclass, jlong pc) { return Take(env, krtc_pc_remote_description(H<Pc>(pc)->pc)); }
KRTC_JNI(void, pcGetStats)(JNIEnv *env, jclass, jlong pc, jobject done) { krtc_pc_get_stats(H<Pc>(pc)->pc, OnDone, env->NewGlobalRef(done)); }
KRTC_JNI(jint, pcTransceiverCount)(JNIEnv *, jclass, jlong pc) { return krtc_pc_transceiver_count(H<Pc>(pc)->pc); }
KRTC_JNI(jlong, pcTransceiverAt)(JNIEnv *, jclass, jlong pc, jint i) { return J(krtc_pc_transceiver_at(H<Pc>(pc)->pc, i)); }
KRTC_JNI(void, pcClose)(JNIEnv *, jclass, jlong pc) { krtc_pc_close(H<Pc>(pc)->pc); }
KRTC_JNI(void, pcRelease)(JNIEnv *env, jclass, jlong pc) {
    Pc *p = H<Pc>(pc);
    krtc_pc_release(p->pc); // closes first, so no event arrives after this
    env->DeleteGlobalRef(p->listener);
    delete p;
}

// Data channels.
KRTC_JNI(jlong, pcCreateDataChannel)(JNIEnv *env, jclass, jlong pc, jstring label, jboolean ordered, jint maxRetransmits,
                                     jint maxLifetime, jstring protocol, jboolean negotiated, jint id) {
    return J(krtc_pc_create_data_channel(H<Pc>(pc)->pc, Utf(env, label).get(), ordered ? 1 : 0, maxRetransmits, maxLifetime,
                                         Utf(env, protocol).get(), negotiated ? 1 : 0, id));
}
/** Installs a listener (null removes); returns the sink to release with dataChannelSinkRelease once removed. */
KRTC_JNI(jlong, dataChannelSetObserver)(JNIEnv *env, jclass, jlong dc, jobject listener) {
    if (!listener) {
        krtc_data_channel_set_observer(H<krtc_data_channel>(dc), nullptr, nullptr);
        return 0;
    }
    auto *sink = new DataSink();
    sink->listener = env->NewGlobalRef(listener);
    krtc_data_channel_set_observer(H<krtc_data_channel>(dc), OnDataChannelEvent, sink);
    return J(sink);
}
KRTC_JNI(void, dataChannelSinkRelease)(JNIEnv *env, jclass, jlong handle) {
    auto *sink = H<DataSink>(handle);
    env->DeleteGlobalRef(sink->listener);
    delete sink;
}
KRTC_JNI(jstring, dataChannelLabel)(JNIEnv *env, jclass, jlong dc) { return Take(env, krtc_data_channel_label(H<krtc_data_channel>(dc))); }
KRTC_JNI(jstring, dataChannelProtocol)(JNIEnv *env, jclass, jlong dc) { return Take(env, krtc_data_channel_protocol(H<krtc_data_channel>(dc))); }
KRTC_JNI(jint, dataChannelId)(JNIEnv *, jclass, jlong dc) { return krtc_data_channel_id(H<krtc_data_channel>(dc)); }
KRTC_JNI(jboolean, dataChannelOrdered)(JNIEnv *, jclass, jlong dc) { return krtc_data_channel_ordered(H<krtc_data_channel>(dc)) != 0; }
KRTC_JNI(jint, dataChannelMaxRetransmits)(JNIEnv *, jclass, jlong dc) { return krtc_data_channel_max_retransmits(H<krtc_data_channel>(dc)); }
KRTC_JNI(jint, dataChannelMaxPacketLifetime)(JNIEnv *, jclass, jlong dc) { return krtc_data_channel_max_packet_lifetime(H<krtc_data_channel>(dc)); }
KRTC_JNI(jboolean, dataChannelNegotiated)(JNIEnv *, jclass, jlong dc) { return krtc_data_channel_negotiated(H<krtc_data_channel>(dc)) != 0; }
KRTC_JNI(jint, dataChannelState)(JNIEnv *, jclass, jlong dc) { return krtc_data_channel_state(H<krtc_data_channel>(dc)); }
KRTC_JNI(jlong, dataChannelBufferedAmount)(JNIEnv *, jclass, jlong dc) {
    return static_cast<jlong>(krtc_data_channel_buffered_amount(H<krtc_data_channel>(dc)));
}
KRTC_JNI(jint, dataChannelSend)(JNIEnv *env, jclass, jlong dc, jbyteArray data, jboolean binary) {
    jsize n = env->GetArrayLength(data);
    jbyte *p = env->GetByteArrayElements(data, nullptr);
    int r = krtc_data_channel_send(H<krtc_data_channel>(dc), reinterpret_cast<const uint8_t *>(p), n, binary ? 1 : 0);
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
    return r;
}
KRTC_JNI(void, dataChannelClose)(JNIEnv *, jclass, jlong dc) { krtc_data_channel_close(H<krtc_data_channel>(dc)); }
KRTC_JNI(void, dataChannelRelease)(JNIEnv *, jclass, jlong dc) { krtc_data_channel_release(H<krtc_data_channel>(dc)); }

// Transceivers.
KRTC_JNI(jstring, transceiverMid)(JNIEnv *env, jclass, jlong t) { return Take(env, krtc_transceiver_mid(H<krtc_transceiver>(t))); }
KRTC_JNI(jint, transceiverDirection)(JNIEnv *, jclass, jlong t) { return krtc_transceiver_direction(H<krtc_transceiver>(t)); }
KRTC_JNI(jint, transceiverSetDirection)(JNIEnv *, jclass, jlong t, jint d) { return krtc_transceiver_set_direction(H<krtc_transceiver>(t), d); }
KRTC_JNI(jint, transceiverSetSenderTrack)(JNIEnv *, jclass, jlong t, jlong track) {
    return krtc_transceiver_set_sender_track(H<krtc_transceiver>(t), H<krtc_track>(track));
}
KRTC_JNI(jlong, transceiverReceiverTrack)(JNIEnv *, jclass, jlong t) { return J(krtc_transceiver_receiver_track(H<krtc_transceiver>(t))); }
KRTC_JNI(jint, transceiverPreferCodec)(JNIEnv *env, jclass, jlong f, jlong t, jstring mime) {
    return krtc_transceiver_prefer_codec(H<krtc_factory>(f), H<krtc_transceiver>(t), Utf(env, mime).get());
}
KRTC_JNI(jint, transceiverSetSendCodec)(JNIEnv *env, jclass, jlong t, jstring mime) {
    return krtc_transceiver_set_send_codec(H<krtc_transceiver>(t), Utf(env, mime).get());
}
KRTC_JNI(void, transceiverRelease)(JNIEnv *, jclass, jlong t) { krtc_transceiver_release(H<krtc_transceiver>(t)); }

// Tracks.
KRTC_JNI(jstring, trackId)(JNIEnv *env, jclass, jlong t) { return Take(env, krtc_track_id(H<krtc_track>(t))); }
KRTC_JNI(jboolean, trackIsVideo)(JNIEnv *, jclass, jlong t) { return krtc_track_is_video(H<krtc_track>(t)) != 0; }
KRTC_JNI(jboolean, trackEnabled)(JNIEnv *, jclass, jlong t) { return krtc_track_enabled(H<krtc_track>(t)) != 0; }
KRTC_JNI(void, trackSetEnabled)(JNIEnv *, jclass, jlong t, jboolean on) { krtc_track_set_enabled(H<krtc_track>(t), on ? 1 : 0); }
KRTC_JNI(void, trackSetVolume)(JNIEnv *, jclass, jlong t, jdouble volume) { krtc_track_set_volume(H<krtc_track>(t), volume); }
KRTC_JNI(void, trackVideoStats)(JNIEnv *env, jclass, jlong t, jlongArray out) {
    int64_t frames = 0;
    int w = 0, h = 0;
    krtc_track_video_stats(H<krtc_track>(t), &frames, &w, &h);
    const jlong values[3] = {frames, w, h};
    env->SetLongArrayRegion(out, 0, 3, values);
}
/** Installs a frame listener (null removes); returns the sink to release with frameSinkRelease once replaced. */
KRTC_JNI(jlong, trackSetFrameSink)(JNIEnv *env, jclass, jlong t, jobject listener) {
    if (!listener) {
        krtc_track_set_frame_sink(H<krtc_track>(t), nullptr, nullptr);
        return 0;
    }
    auto *sink = new FrameSink();
    sink->listener = env->NewGlobalRef(listener);
    krtc_track_set_frame_sink(H<krtc_track>(t), OnFrame, sink);
    return J(sink);
}
KRTC_JNI(void, frameSinkRelease)(JNIEnv *env, jclass, jlong handle) {
    auto *sink = H<FrameSink>(handle);
    env->DeleteGlobalRef(sink->listener);
    if (sink->pixels) env->DeleteGlobalRef(sink->pixels);
    delete sink;
}
KRTC_JNI(void, trackRelease)(JNIEnv *, jclass, jlong t) { krtc_track_release(H<krtc_track>(t)); }
