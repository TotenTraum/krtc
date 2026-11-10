// JNI bridge from krtc.h to the JVM and Android (ADR-0002: one JNI layer over the same C ABI).
// Kotlin side: krtc.native.Jni (jvmAndAndroidMain). Handles are passed as jlong.
//
// Callbacks arrive on libwebrtc threads; they attach as daemon threads and call into Kotlin objects held
// by global references:
//   listener.onEvent(int event, int state, long transceiver, long track)   for a peer connection
//   done.complete(String result, String error)                             once per asynchronous call
#include <jni.h>

#include <cstdint>
#include <cstdlib>

#include "krtc.h"

namespace {

JavaVM *g_vm = nullptr;
jmethodID g_on_event = nullptr;
jmethodID g_complete = nullptr;

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
    if (!listener || !done) return JNI_ERR;
    g_on_event = env->GetMethodID(listener, "onEvent", "(IIJJ)V");
    g_complete = env->GetMethodID(done, "complete", "(Ljava/lang/String;Ljava/lang/String;)V");
    return g_on_event && g_complete ? JNI_VERSION_1_6 : JNI_ERR;
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
KRTC_JNI(void, trackVideoStats)(JNIEnv *env, jclass, jlong t, jlongArray out) {
    int64_t frames = 0;
    int w = 0, h = 0;
    krtc_track_video_stats(H<krtc_track>(t), &frames, &w, &h);
    const jlong values[3] = {frames, w, h};
    env->SetLongArrayRegion(out, 0, 3, values);
}
KRTC_JNI(void, trackRelease)(JNIEnv *, jclass, jlong t) { krtc_track_release(H<krtc_track>(t)); }
