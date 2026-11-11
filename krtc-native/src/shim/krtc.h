/*
 * krtc native shim: a flat C ABI over libwebrtc (ADR-0002).
 *
 * All C++ (and libwebrtc's C++ standard library) stays behind this header. Handles are opaque;
 * functions returning a handle return a new reference the caller releases with the matching
 * *_release function. Strings returned as `char *` are freed with krtc_free.
 *
 * Threading: functions may be called from any thread. Callbacks run on libwebrtc's signaling
 * thread and must not block or call back into the peer connection synchronously.
 */
#ifndef KRTC_H
#define KRTC_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct krtc_factory krtc_factory;
typedef struct krtc_pc krtc_pc;
typedef struct krtc_transceiver krtc_transceiver;
typedef struct krtc_track krtc_track;
typedef struct krtc_video_source krtc_video_source;
typedef struct krtc_data_channel krtc_data_channel;
typedef struct krtc_audio_source krtc_audio_source;

/* Completion of an asynchronous operation. `error` is NULL on success; `sdp` is set by create_answer. */
typedef void (*krtc_done_fn)(void *user, const char *sdp, const char *error);

enum {
    KRTC_EVENT_CONNECTION_STATE = 1, /* state: 0 new, 1 connecting, 2 connected, 3 disconnected, 4 failed, 5 closed */
    KRTC_EVENT_TRACK = 2,            /* transceiver and track are new references owned by the callee */
};
typedef void (*krtc_event_fn)(void *user, int event, int state, krtc_transceiver *transceiver, krtc_track *track);

enum {
    KRTC_DIRECTION_SENDRECV = 0,
    KRTC_DIRECTION_SENDONLY = 1,
    KRTC_DIRECTION_RECVONLY = 2,
    KRTC_DIRECTION_INACTIVE = 3,
    KRTC_DIRECTION_STOPPED = 4,
};

enum {
    KRTC_AUDIO_SYNTHETIC = 0, /* no sound hardware: PCM in/out through krtc_factory_push_audio / pull_audio */
    KRTC_AUDIO_PLATFORM = 1,  /* the system microphone and speaker */
};

/* Factory: owns libwebrtc's network, worker and signaling threads.
 * krtc_factory_create() = synthetic audio + platform video codecs. */
krtc_factory *krtc_factory_create(void);
krtc_factory *krtc_factory_create_with(int audio_mode, int platform_video_codecs);
void krtc_factory_release(krtc_factory *factory);

/* Synthetic audio (48 kHz mono 16-bit). Pushed PCM is sent as the microphone; without it a test tone
 * (if enabled) or silence is sent. Pulled PCM is the decoded mix of remote audio. Return sample counts. */
void krtc_factory_set_test_tone(krtc_factory *factory, int enabled);
int krtc_factory_push_audio(krtc_factory *factory, const int16_t *samples, int count);
int krtc_factory_pull_audio(krtc_factory *factory, int16_t *out, int count);
/* RMS level (0..1) of the remote audio mix over the last 10 ms (synthetic audio only). */
double krtc_factory_output_level(krtc_factory *factory);

/* Platform audio devices (KRTC_AUDIO_PLATFORM; none with synthetic audio). output: 1 speakers/headphones, 0
 * microphones. Names are freed with krtc_free. Switching works during a call: capture or playout restarts on the
 * new device. Returns 0 on success. */
int krtc_factory_audio_device_count(krtc_factory *factory, int output);
char *krtc_factory_audio_device_name(krtc_factory *factory, int output, int index);
int krtc_factory_set_audio_device(krtc_factory *factory, int output, int index);
/* The device in use (-1 if unknown, e.g. the OS default). */
int krtc_factory_audio_device(krtc_factory *factory, int output);

/* Video codecs this factory can send, as "video/VP8 video/H264 ..." (free with krtc_free). */
char *krtc_factory_video_codecs(krtc_factory *factory);

/* Local tracks. The test video track generates frames internally (no camera needed). */
krtc_track *krtc_factory_create_audio_track(krtc_factory *factory, const char *id);
krtc_track *krtc_factory_create_test_video_track(krtc_factory *factory, const char *id, int width, int height, int fps);

/* Video sources. A pushed source takes frames from the application; a camera source captures (Apple:
 * AVFoundation, Linux: V4L2, Windows: DirectShow; NULL where unavailable, e.g. Android, where the app captures
 * and pushes, or when the user denies camera access; on Apple the first call may show the OS permission prompt
 * and wait for it).
 * Releasing a source stops its camera; tracks made from it keep their own reference. */
krtc_video_source *krtc_factory_create_video_source(krtc_factory *factory);
int krtc_camera_count(void);
char *krtc_camera_name(int index); /* free with krtc_free */
krtc_video_source *krtc_factory_create_camera_source(krtc_factory *factory, int index, int width, int height, int fps);
/* I420 planes; rotation 0/90/180/270 (clockwise, applied by the receiver); timestamp_us 0 = now. */
void krtc_video_source_push_i420(krtc_video_source *source, int width, int height,
                                 const uint8_t *y, int stride_y, const uint8_t *u, int stride_u, const uint8_t *v, int stride_v,
                                 int rotation, int64_t timestamp_us);
/* 32-bit ARGB pixels (0xAARRGGBB as native-endian ints), width × height, rows contiguous. */
void krtc_video_source_push_argb(krtc_video_source *source, int width, int height, const uint32_t *argb, int rotation, int64_t timestamp_us);
/* RGBA bytes (R,G,B,A in memory, e.g. Android's RGBA_8888) with a row stride in bytes. */
void krtc_video_source_push_rgba(krtc_video_source *source, int width, int height, const uint8_t *rgba, int stride, int rotation, int64_t timestamp_us);

/* Screen sharing. Sources the shim can capture itself, one per line as "<id> <s|w> <title>" (s: display, w: window;
 * free with krtc_free): macOS displays and windows (ScreenCaptureKit), the Linux X11 display, iOS this app's screen
 * (ReplayKit); none on Android, where the app captures with MediaProjection into a screencast source.
 * A screen source is marked as screen content, and tracks made from it get the "detail" content hint, so the
 * encoder keeps resolution and sharpness and gives up frame rate first. Frames are scaled down to fit max_width ×
 * max_height (0 = no limit). NULL if capture is unavailable or not permitted (macOS asks for Screen Recording
 * permission on first use and returns NULL until it is granted). */
char *krtc_screen_sources(void);
krtc_video_source *krtc_factory_create_screen_source(krtc_factory *factory, int64_t id, int window, int fps, int max_width, int max_height);
krtc_video_source *krtc_factory_create_screencast_source(krtc_factory *factory);

krtc_track *krtc_factory_create_video_track(krtc_factory *factory, const char *id, krtc_video_source *source);

/* Audio sources apart from the factory's audio device: PCM pushed by the application, or the system's sound for a
 * screen share (macOS ScreenCaptureKit, Linux PulseAudio/PipeWire monitor, Windows WASAPI loopback, iOS this app's
 * sound during ReplayKit capture; NULL where unavailable or not permitted, e.g. Android, where the app captures with
 * AudioPlaybackCapture and pushes). Pushed PCM: 16-bit interleaved, any rate and channel count. Releasing a source
 * stops its capture; tracks keep their own reference. */
krtc_audio_source *krtc_factory_create_audio_source(krtc_factory *factory);
krtc_audio_source *krtc_factory_create_system_audio_source(krtc_factory *factory);
void krtc_audio_source_push(krtc_audio_source *source, const int16_t *samples, int frames, int channels, int sample_rate);
krtc_track *krtc_factory_create_audio_track_from(krtc_factory *factory, const char *id, krtc_audio_source *source);
void krtc_audio_source_release(krtc_audio_source *source);
void krtc_video_source_release(krtc_video_source *source);

/* Peer connection (Unified Plan). */
krtc_pc *krtc_pc_create(krtc_factory *factory, krtc_event_fn on_event, void *user);
void krtc_pc_set_remote_description(krtc_pc *pc, const char *type, const char *sdp, krtc_done_fn done, void *user);
void krtc_pc_create_answer(krtc_pc *pc, krtc_done_fn done, void *user);
void krtc_pc_set_local_description(krtc_pc *pc, const char *type, const char *sdp, krtc_done_fn done, void *user);
char *krtc_pc_local_description(krtc_pc *pc);
char *krtc_pc_remote_description(krtc_pc *pc);
/* The W3C stats report as JSON (an object of RTCStats keyed by id), delivered in `sdp` of `done`. */
void krtc_pc_get_stats(krtc_pc *pc, krtc_done_fn done, void *user);
int krtc_pc_transceiver_count(krtc_pc *pc);
krtc_transceiver *krtc_pc_transceiver_at(krtc_pc *pc, int index);
void krtc_pc_close(krtc_pc *pc);
void krtc_pc_release(krtc_pc *pc);

/* Data channels (W3C RTCDataChannel). max_retransmits / max_packet_lifetime: -1 for none; id: -1 to let the
 * transport pick (in-band DCEP), or the stream id of a negotiated channel. NULL on invalid parameters. */
krtc_data_channel *krtc_pc_create_data_channel(krtc_pc *pc, const char *label, int ordered, int max_retransmits,
                                               int max_packet_lifetime, const char *protocol, int negotiated, int id);
/* Channels the remote side opens (`datachannel` events); the channel is a new reference owned by the callee. */
typedef void (*krtc_data_channel_fn)(void *user, krtc_data_channel *channel);
void krtc_pc_set_data_channel_handler(krtc_pc *pc, krtc_data_channel_fn fn, void *user);

enum {
    KRTC_DATA_CHANNEL_STATE = 1,   /* value: 0 connecting, 1 open, 2 closing, 3 closed */
    KRTC_DATA_CHANNEL_MESSAGE = 2, /* value: 1 binary, 0 text (UTF-8); data/length valid during the call only */
    KRTC_DATA_CHANNEL_BUFFERED_AMOUNT = 3,
};
typedef void (*krtc_data_channel_event_fn)(void *user, int event, int value, const uint8_t *data, int length);
/* Messages received before an observer is set are queued and delivered when it is set. NULL removes it; after
 * that returns no more events arrive. */
void krtc_data_channel_set_observer(krtc_data_channel *dc, krtc_data_channel_event_fn fn, void *user);
char *krtc_data_channel_label(krtc_data_channel *dc);
char *krtc_data_channel_protocol(krtc_data_channel *dc);
int krtc_data_channel_id(krtc_data_channel *dc); /* -1 until the DTLS role is known */
int krtc_data_channel_ordered(krtc_data_channel *dc);
int krtc_data_channel_max_retransmits(krtc_data_channel *dc);     /* -1 if not set */
int krtc_data_channel_max_packet_lifetime(krtc_data_channel *dc); /* -1 if not set */
int krtc_data_channel_negotiated(krtc_data_channel *dc);
int krtc_data_channel_state(krtc_data_channel *dc);
uint64_t krtc_data_channel_buffered_amount(krtc_data_channel *dc);
/* 0 on success; -1 if the channel is not open, the send queue is full or the message too large. */
int krtc_data_channel_send(krtc_data_channel *dc, const uint8_t *data, int length, int binary);
void krtc_data_channel_close(krtc_data_channel *dc);
void krtc_data_channel_release(krtc_data_channel *dc);

/* Transceivers. */
char *krtc_transceiver_mid(krtc_transceiver *t);
int krtc_transceiver_direction(krtc_transceiver *t);
int krtc_transceiver_set_direction(krtc_transceiver *t, int direction);      /* 0 on success */
int krtc_transceiver_set_sender_track(krtc_transceiver *t, krtc_track *track); /* NULL clears; 0 on success */
krtc_track *krtc_transceiver_receiver_track(krtc_transceiver *t);
/* Moves codecs whose MIME type equals `mime` (e.g. "video/H264") to the front (setCodecPreferences); 0 on success. */
int krtc_transceiver_prefer_codec(krtc_factory *factory, krtc_transceiver *t, const char *mime);
/* Sends every encoding with the negotiated codec whose MIME type equals `mime` (RTCRtpEncodingParameters.codec);
 * call after negotiation. 0 on success, -1 if that codec was not negotiated or the sender refused it. */
int krtc_transceiver_set_send_codec(krtc_transceiver *t, const char *mime);
void krtc_transceiver_release(krtc_transceiver *t);

/* Tracks. */
char *krtc_track_id(krtc_track *track);
int krtc_track_is_video(krtc_track *track);
int krtc_track_enabled(krtc_track *track);
void krtc_track_set_enabled(krtc_track *track, int enabled);
/* Video tracks: frames delivered so far and the last frame size (attaches a counting sink on first use). */
void krtc_track_video_stats(krtc_track *track, int64_t *frames, int *width, int *height);
/* Video tracks: every frame, rotated upright, as ARGB (0xAARRGGBB ints, width × height, rows contiguous), on a
 * libwebrtc thread; the pixels are valid during the call only. NULL removes the sink. */
typedef void (*krtc_frame_fn)(void *user, int width, int height, const uint32_t *argb, int64_t timestamp_us);
void krtc_track_set_frame_sink(krtc_track *track, krtc_frame_fn fn, void *user);
void krtc_track_release(krtc_track *track);

void krtc_free(void *p);

#ifdef __cplusplus
}
#endif

#endif /* KRTC_H */
