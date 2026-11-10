package krtc.native

import cnames.structs.krtc_data_channel
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import krtc.core.RTCDataChannel
import krtc.core.RTCDataChannelMessage
import krtc.core.RTCDataChannelState
import krtc.native.shim.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

private val dataChannelCallback = staticCFunction { user: COpaquePointer?, event: Int, value: Int, data: CPointer<UByteVar>?, length: Int ->
    user!!.asStableRef<NativeDataChannel>().get().onEvent(event, value, data, length)
}

/** [RTCDataChannel] on a libwebrtc data channel. Events arrive on libwebrtc's signaling thread. */
internal class NativeDataChannel(private val handle: CPointer<krtc_data_channel>) : RTCDataChannel {
    private val lock = SynchronizedObject()
    private var released = false
    private val self = StableRef.create(this)
    private val state = MutableStateFlow(RTCDataChannelState.entries[krtc_data_channel_state(handle)])

    // Messages wait here until collected (also those arriving before anyone collects); each goes to one collector.
    // A collector that falls far behind loses the oldest.
    private val messages = Channel<RTCDataChannelMessage>(MESSAGE_BUFFER, BufferOverflow.DROP_OLDEST)

    override val label: String = krtc_data_channel_label(handle)!!.let { p -> p.toKString().also { krtc_free(p) } }
    override val protocol: String = krtc_data_channel_protocol(handle)!!.let { p -> p.toKString().also { krtc_free(p) } }
    override val ordered: Boolean = krtc_data_channel_ordered(handle) != 0
    override val maxRetransmits: Int? = krtc_data_channel_max_retransmits(handle).takeIf { it >= 0 }
    override val maxPacketLifeTime: Int? = krtc_data_channel_max_packet_lifetime(handle).takeIf { it >= 0 }
    override val negotiated: Boolean = krtc_data_channel_negotiated(handle) != 0
    override val id: Int? get() = synchronized(lock) { if (released) null else krtc_data_channel_id(handle).takeIf { it >= 0 } }
    override val readyState: StateFlow<RTCDataChannelState> = state.asStateFlow()
    override val bufferedAmount: Long get() = synchronized(lock) { if (released) 0 else krtc_data_channel_buffered_amount(handle).toLong() }
    override val onmessage: Flow<RTCDataChannelMessage> = messages.receiveAsFlow()

    init {
        krtc_data_channel_set_observer(handle, dataChannelCallback, self.asCPointer())
    }

    fun onEvent(event: Int, value: Int, data: CPointer<UByteVar>?, length: Int) {
        when (event) {
            KRTC_DATA_CHANNEL_STATE.toInt() -> {
                val s = RTCDataChannelState.entries.getOrElse(value) { RTCDataChannelState.CLOSED }
                state.value = s
                if (s == RTCDataChannelState.CLOSED) messages.close()
            }
            KRTC_DATA_CHANNEL_MESSAGE.toInt() -> {
                val bytes = if (data != null && length > 0) data.readBytes(length) else ByteArray(0)
                messages.trySend(if (value != 0) RTCDataChannelMessage.Binary(bytes) else RTCDataChannelMessage.Text(bytes.decodeToString()))
            }
        }
    }

    override fun send(data: String) = send(data.encodeToByteArray(), binary = false)

    override fun send(data: ByteArray) = send(data, binary = true)

    private fun send(data: ByteArray, binary: Boolean) {
        check(state.value == RTCDataChannelState.OPEN) { "data channel '$label' is ${state.value.value}" }
        val result = synchronized(lock) {
            if (released) -1
            else if (data.isEmpty()) krtc_data_channel_send(handle, null, 0, if (binary) 1 else 0)
            else data.usePinned { krtc_data_channel_send(handle, it.addressOf(0).reinterpret(), data.size, if (binary) 1 else 0) }
        }
        check(result == 0) { "data channel '$label' refused the message (queue full or too large)" }
    }

    override fun close() {
        synchronized(lock) { if (!released) krtc_data_channel_close(handle) }
    }

    /** Detaches from libwebrtc when the peer connection goes away (never from inside an event callback). */
    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
        }
        krtc_data_channel_set_observer(handle, null, null)
        messages.close()
        krtc_data_channel_release(handle)
        self.dispose()
    }

    private companion object {
        const val MESSAGE_BUFFER = 4096
    }
}
