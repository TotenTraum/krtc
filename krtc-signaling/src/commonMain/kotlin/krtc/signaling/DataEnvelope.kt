package krtc.signaling

/**
 * Framing of room messages on the krtc-sfu data channels (ADR-0016). Every client opens two channels to the SFU:
 * [RELIABLE_LABEL] (reliable, ordered) and [LOSSY_LABEL] (unordered, no retransmissions). Each message is one
 * binary data channel message:
 *
 * ```
 * u8 version (1) | u8 id count | count × (u8 length, UTF-8 participant id) | u8 length, UTF-8 topic | payload
 * ```
 *
 * Client → SFU: the ids are the recipients (none: everyone else in the room).
 * SFU → client: the single id is the sender (none: the server itself).
 */
public object DataEnvelope {
    public const val RELIABLE_LABEL: String = "krtc"
    public const val LOSSY_LABEL: String = "krtc-lossy"

    /** Largest data channel message the SFU accepts (its SDP `a=max-message-size`). */
    public const val MAX_MESSAGE_SIZE: Int = 65_536
    private const val VERSION = 1

    public class Message(public val ids: List<String>, public val topic: String, public val payload: ByteArray)

    public fun encode(ids: List<String>, topic: String, payload: ByteArray): ByteArray {
        require(ids.size <= 255) { "at most 255 recipients" }
        val idBytes = ids.map { it.encodeToByteArray().also { b -> require(b.size <= 255) { "id too long: $it" } } }
        val topicBytes = topic.encodeToByteArray()
        require(topicBytes.size <= 255) { "topic longer than 255 bytes" }
        val out = ByteArray(2 + idBytes.sumOf { 1 + it.size } + 1 + topicBytes.size + payload.size)
        var at = 0
        out[at++] = VERSION.toByte()
        out[at++] = ids.size.toByte()
        for (b in idBytes) {
            out[at++] = b.size.toByte()
            b.copyInto(out, at)
            at += b.size
        }
        out[at++] = topicBytes.size.toByte()
        topicBytes.copyInto(out, at)
        at += topicBytes.size
        payload.copyInto(out, at)
        return out
    }

    /** Null if [data] is not a valid envelope. */
    public fun decode(data: ByteArray): Message? {
        if (data.size < 3 || data[0].toInt() != VERSION) return null
        var at = 1
        val count = data[at++].toInt() and 0xFF
        val ids = ArrayList<String>(count)
        repeat(count) {
            if (at >= data.size) return null
            val len = data[at++].toInt() and 0xFF
            if (at + len > data.size) return null
            ids += data.decodeToString(at, at + len)
            at += len
        }
        if (at >= data.size) return null
        val topicLen = data[at++].toInt() and 0xFF
        if (at + topicLen > data.size) return null
        val topic = data.decodeToString(at, at + topicLen)
        at += topicLen
        return Message(ids, topic, data.copyOfRange(at, data.size))
    }
}
