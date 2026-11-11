package krtc.native

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JNI entry points of `libkrtc_jni` (src/shim/krtc_jni.cpp), one per krtc.h function; handles are `Long`.
 *
 * Loading: the `krtc.native.library` system property (absolute path) if set; otherwise the library packed in
 * this jar under `krtc/native/<os>-<arch>/` (desktop JVM); otherwise `System.loadLibrary` (Android's jniLibs).
 */
internal object Jni {
    init {
        load()
    }

    /** Peer connection events (KRTC_EVENT_*), on a libwebrtc thread. */
    fun interface Listener {
        fun onEvent(event: Int, state: Int, transceiver: Long, track: Long)
    }

    /** Video frames of a track, on a libwebrtc thread; [argb] is reused for the next frame of the same size. */
    fun interface FrameListener {
        fun onFrame(width: Int, height: Int, argb: IntArray, timestampMicros: Long)
    }

    /** Completion of one asynchronous call, on a libwebrtc thread: [result] or [error]. */
    /** Data channel events (KRTC_DATA_CHANNEL_*): state changes, messages (data set), buffered amount changes. */
    fun interface DataChannelListener {
        fun onDataEvent(event: Int, value: Int, data: ByteArray?)
    }

    fun interface Done {
        fun complete(result: String?, error: String?)
    }

    @JvmStatic external fun factoryCreate(audio: Int, platformCodecs: Int): Long
    @JvmStatic external fun factoryRelease(factory: Long)
    @JvmStatic external fun factorySetTestTone(factory: Long, enabled: Boolean)
    @JvmStatic external fun factoryPushAudio(factory: Long, samples: ShortArray, count: Int): Int
    @JvmStatic external fun factoryPullAudio(factory: Long, out: ShortArray): Int
    @JvmStatic external fun factoryOutputLevel(factory: Long): Double
    @JvmStatic external fun factoryVideoCodecs(factory: Long): String
    @JvmStatic external fun factoryCreateAudioTrack(factory: Long, id: String): Long
    @JvmStatic external fun factoryCreateTestVideoTrack(factory: Long, id: String, width: Int, height: Int, fps: Int): Long

    @JvmStatic external fun factoryCreateVideoSource(factory: Long): Long
    @JvmStatic external fun factoryCreateCameraSource(factory: Long, index: Int, width: Int, height: Int, fps: Int): Long
    @JvmStatic external fun factoryCreateVideoTrack(factory: Long, id: String, source: Long): Long
    @JvmStatic external fun cameraCount(): Int
    @JvmStatic external fun cameraName(index: Int): String?
    @JvmStatic external fun videoSourcePushI420(
        source: Long, width: Int, height: Int, y: ByteArray, strideY: Int, u: ByteArray, strideU: Int, v: ByteArray, strideV: Int,
        rotation: Int, timestampMicros: Long,
    )
    @JvmStatic external fun videoSourcePushArgb(source: Long, width: Int, height: Int, argb: IntArray, rotation: Int, timestampMicros: Long)
    @JvmStatic external fun videoSourceRelease(source: Long)

    @JvmStatic external fun pcCreate(factory: Long, listener: Listener): Long
    @JvmStatic external fun pcSetRemoteDescription(pc: Long, type: String, sdp: String, done: Done)
    @JvmStatic external fun pcCreateAnswer(pc: Long, done: Done)
    @JvmStatic external fun pcSetLocalDescription(pc: Long, type: String, sdp: String, done: Done)
    @JvmStatic external fun pcLocalDescription(pc: Long): String?
    @JvmStatic external fun pcRemoteDescription(pc: Long): String?
    @JvmStatic external fun pcGetStats(pc: Long, done: Done)
    @JvmStatic external fun pcTransceiverCount(pc: Long): Int
    @JvmStatic external fun pcTransceiverAt(pc: Long, index: Int): Long
    @JvmStatic external fun pcClose(pc: Long)
    @JvmStatic external fun pcRelease(pc: Long)

    @JvmStatic external fun pcCreateDataChannel(
        pc: Long, label: String, ordered: Boolean, maxRetransmits: Int, maxPacketLifetime: Int, protocol: String, negotiated: Boolean, id: Int,
    ): Long
    @JvmStatic external fun dataChannelSetObserver(dc: Long, listener: DataChannelListener?): Long
    @JvmStatic external fun dataChannelSinkRelease(sink: Long)
    @JvmStatic external fun dataChannelLabel(dc: Long): String
    @JvmStatic external fun dataChannelProtocol(dc: Long): String
    @JvmStatic external fun dataChannelId(dc: Long): Int
    @JvmStatic external fun dataChannelOrdered(dc: Long): Boolean
    @JvmStatic external fun dataChannelMaxRetransmits(dc: Long): Int
    @JvmStatic external fun dataChannelMaxPacketLifetime(dc: Long): Int
    @JvmStatic external fun dataChannelNegotiated(dc: Long): Boolean
    @JvmStatic external fun dataChannelState(dc: Long): Int
    @JvmStatic external fun dataChannelBufferedAmount(dc: Long): Long
    @JvmStatic external fun dataChannelSend(dc: Long, data: ByteArray, binary: Boolean): Int
    @JvmStatic external fun dataChannelClose(dc: Long)
    @JvmStatic external fun dataChannelRelease(dc: Long)

    /** Jni.Listener event carrying a data channel the remote side opened (handle in `transceiver`). */
    const val EVENT_DATA_CHANNEL = 100
    const val DATA_CHANNEL_STATE = 1
    const val DATA_CHANNEL_MESSAGE = 2

    @JvmStatic external fun transceiverMid(t: Long): String?
    @JvmStatic external fun transceiverDirection(t: Long): Int
    @JvmStatic external fun transceiverSetDirection(t: Long, direction: Int): Int
    @JvmStatic external fun transceiverSetSenderTrack(t: Long, track: Long): Int
    @JvmStatic external fun transceiverReceiverTrack(t: Long): Long
    @JvmStatic external fun transceiverPreferCodec(factory: Long, t: Long, mime: String): Int
    @JvmStatic external fun transceiverSetSendCodec(t: Long, mime: String): Int
    @JvmStatic external fun transceiverRelease(t: Long)

    @JvmStatic external fun trackId(track: Long): String
    @JvmStatic external fun trackIsVideo(track: Long): Boolean
    @JvmStatic external fun trackEnabled(track: Long): Boolean
    @JvmStatic external fun trackSetEnabled(track: Long, enabled: Boolean)
    @JvmStatic external fun trackVideoStats(track: Long, out: LongArray)
    @JvmStatic external fun trackSetFrameSink(track: Long, listener: FrameListener?): Long
    @JvmStatic external fun frameSinkRelease(sink: Long)
    @JvmStatic external fun trackRelease(track: Long)

    private fun load() {
        System.getProperty("krtc.native.library")?.let { System.load(it); return }
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase().let { if (it == "aarch64") "arm64" else if (it == "amd64" || it == "x86_64") "x64" else it }
        val (dir, file) = when {
            os.contains("mac") -> "macos-$arch" to "libkrtc_jni.dylib"
            os.contains("windows") -> "windows-$arch" to "krtc.dll"
            os.contains("linux") && System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true).not() -> "linux-$arch" to "libkrtc_jni.so"
            else -> null to null
        }
        val resource = dir?.let { Jni::class.java.getResourceAsStream("/krtc/native/$it/$file") }
        if (resource == null) {
            System.loadLibrary("krtc_jni")
            return
        }
        val target = File(Files.createTempDirectory("krtc-native").toFile(), file!!).apply { deleteOnExit() }
        resource.use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        System.load(target.absolutePath)
    }
}
