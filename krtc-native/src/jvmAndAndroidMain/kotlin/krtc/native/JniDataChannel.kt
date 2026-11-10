package krtc.native

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import krtc.core.RTCDataChannel
import krtc.core.RTCDataChannelMessage
import krtc.core.RTCDataChannelState

/** [RTCDataChannel] on a libwebrtc data channel through JNI. Events arrive on libwebrtc's signaling thread. */
internal class NativeDataChannel(private val handle: Long) : RTCDataChannel {
    private val lock = Any()
    private var released = false
    private val state = MutableStateFlow(RTCDataChannelState.entries[Jni.dataChannelState(handle)])

    // Messages wait here until collected (also those arriving before anyone collects); each goes to one collector.
    // A collector that falls far behind loses the oldest.
    private val messages = Channel<RTCDataChannelMessage>(MESSAGE_BUFFER, BufferOverflow.DROP_OLDEST)

    override val label: String = Jni.dataChannelLabel(handle)
    override val protocol: String = Jni.dataChannelProtocol(handle)
    override val ordered: Boolean = Jni.dataChannelOrdered(handle)
    override val maxRetransmits: Int? = Jni.dataChannelMaxRetransmits(handle).takeIf { it >= 0 }
    override val maxPacketLifeTime: Int? = Jni.dataChannelMaxPacketLifetime(handle).takeIf { it >= 0 }
    override val negotiated: Boolean = Jni.dataChannelNegotiated(handle)
    override val id: Int? get() = synchronized(lock) { if (released) null else Jni.dataChannelId(handle).takeIf { it >= 0 } }
    override val readyState: StateFlow<RTCDataChannelState> = state.asStateFlow()
    override val bufferedAmount: Long get() = synchronized(lock) { if (released) 0 else Jni.dataChannelBufferedAmount(handle) }
    override val onmessage: Flow<RTCDataChannelMessage> = messages.receiveAsFlow()

    private val sink: Long = Jni.dataChannelSetObserver(handle, ::onEvent)

    private fun onEvent(event: Int, value: Int, data: ByteArray?) {
        when (event) {
            Jni.DATA_CHANNEL_STATE -> {
                val s = RTCDataChannelState.entries.getOrElse(value) { RTCDataChannelState.CLOSED }
                state.value = s
                if (s == RTCDataChannelState.CLOSED) messages.close()
            }
            Jni.DATA_CHANNEL_MESSAGE -> {
                val bytes = data ?: ByteArray(0)
                messages.trySend(if (value != 0) RTCDataChannelMessage.Binary(bytes) else RTCDataChannelMessage.Text(bytes.decodeToString()))
            }
        }
    }

    override fun send(data: String) = send(data.encodeToByteArray(), binary = false)

    override fun send(data: ByteArray) = send(data, binary = true)

    private fun send(data: ByteArray, binary: Boolean) {
        check(state.value == RTCDataChannelState.OPEN) { "data channel '$label' is ${state.value.value}" }
        val result = synchronized(lock) { if (released) -1 else Jni.dataChannelSend(handle, data, binary) }
        check(result == 0) { "data channel '$label' refused the message (queue full or too large)" }
    }

    override fun close() {
        synchronized(lock) { if (!released) Jni.dataChannelClose(handle) }
    }

    /** Detaches from libwebrtc when the peer connection goes away (never from inside an event callback). */
    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
        }
        Jni.dataChannelSetObserver(handle, null)
        Jni.dataChannelSinkRelease(sink)
        messages.close()
        Jni.dataChannelRelease(handle)
    }

    private companion object {
        const val MESSAGE_BUFFER = 4096
    }
}
