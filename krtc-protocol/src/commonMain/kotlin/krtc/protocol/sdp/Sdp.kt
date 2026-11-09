package krtc.protocol.sdp

/**
 * Session Description Protocol (RFC 8866) as used by JSEP (RFC 9429).
 * The model is generic and order-preserving: anything not modelled explicitly survives a round trip.
 */
public data class SdpAttribute(val name: String, val value: String? = null) {
    override fun toString(): String = if (value == null) "a=$name" else "a=$name:$value"
}

/** Non-attribute line such as `c=IN IP4 0.0.0.0` or `b=AS:64`. */
public data class SdpLine(val type: Char, val value: String) {
    override fun toString(): String = "$type=$value"
}

public enum class Direction(public val sdp: String) {
    SENDRECV("sendrecv"), SENDONLY("sendonly"), RECVONLY("recvonly"), INACTIVE("inactive");

    public fun reversed(): Direction = when (this) {
        SENDONLY -> RECVONLY
        RECVONLY -> SENDONLY
        else -> this
    }

    public companion object {
        public fun of(sdp: String): Direction? = entries.firstOrNull { it.sdp == sdp }
    }
}

public interface HasAttributes {
    public val attributes: MutableList<SdpAttribute>

    public fun attribute(name: String): String? = attributes.firstOrNull { it.name == name }?.let { it.value ?: "" }
    public fun attributes(name: String): List<String> = attributes.filter { it.name == name }.map { it.value ?: "" }
    public fun hasAttribute(name: String): Boolean = attributes.any { it.name == name }
}

public class MediaDescription(
    public var type: String,
    public var port: Int,
    public var proto: String,
    public val formats: MutableList<String>,
    public val lines: MutableList<SdpLine> = mutableListOf(),
    override val attributes: MutableList<SdpAttribute> = mutableListOf(),
) : HasAttributes {
    public val mid: String? get() = attribute("mid")

    public val direction: Direction
        get() = attributes.firstNotNullOfOrNull { Direction.of(it.name) } ?: Direction.SENDRECV

    /** SSRCs announced with `a=ssrc:<id> ...`, in order of appearance. */
    public val ssrcs: List<Long>
        get() = attributes("ssrc").mapNotNull { it.substringBefore(' ').toLongOrNull() }.distinct()

    /** Payload type → `encoding/clock[/channels]` from `a=rtpmap`. */
    public val rtpMaps: Map<Int, String>
        get() = attributes("rtpmap").mapNotNull { v ->
            val pt = v.substringBefore(' ').toIntOrNull() ?: return@mapNotNull null
            pt to v.substringAfter(' ')
        }.toMap()

    /** Header extension id → URI from `a=extmap`. */
    public val extensions: Map<Int, String>
        get() = attributes("extmap").mapNotNull { v ->
            val id = v.substringBefore(' ').substringBefore('/').toIntOrNull() ?: return@mapNotNull null
            id to v.substringAfter(' ').substringBefore(' ')
        }.toMap()

    override fun toString(): String = buildString {
        append("m=").append(type).append(' ').append(port).append(' ').append(proto)
        for (f in formats) append(' ').append(f)
        append("\r\n")
        for (l in lines) append(l).append("\r\n")
        for (a in attributes) append(a).append("\r\n")
    }
}

public class SessionDescription(
    /** Session-level non-attribute lines in order (v=, o=, s=, t=, c=, b=, ...). */
    public val lines: MutableList<SdpLine> = mutableListOf(),
    override val attributes: MutableList<SdpAttribute> = mutableListOf(),
    public val media: MutableList<MediaDescription> = mutableListOf(),
) : HasAttributes {

    /** Media-level attribute with session-level fallback (ICE credentials, fingerprint, setup). */
    public fun effective(m: MediaDescription, name: String): String? = m.attribute(name) ?: attribute(name)

    public fun iceUfrag(m: MediaDescription = media.first()): String? = effective(m, "ice-ufrag")
    public fun icePwd(m: MediaDescription = media.first()): String? = effective(m, "ice-pwd")

    /** `a=fingerprint` as (hash function, uppercase hex with colons). */
    public fun fingerprint(m: MediaDescription = media.first()): Pair<String, String>? =
        effective(m, "fingerprint")?.let { it.substringBefore(' ').lowercase() to it.substringAfter(' ').uppercase() }

    public fun setup(m: MediaDescription = media.first()): String? = effective(m, "setup")

    override fun toString(): String = buildString {
        for (l in lines) append(l).append("\r\n")
        for (a in attributes) append(a).append("\r\n")
        for (m in media) append(m)
    }

    public companion object {
        public fun parse(text: String): SessionDescription {
            val sd = SessionDescription()
            var current: MediaDescription? = null
            for (raw in text.split('\n')) {
                val line = raw.trimEnd('\r')
                if (line.length < 2 || line[1] != '=') continue
                val type = line[0]
                val value = line.substring(2)
                when (type) {
                    'm' -> {
                        val parts = value.split(' ')
                        require(parts.size >= 3) { "bad m= line: $line" }
                        current = MediaDescription(
                            type = parts[0],
                            port = parts[1].substringBefore('/').toInt(),
                            proto = parts[2],
                            formats = parts.drop(3).toMutableList(),
                        ).also { sd.media += it }
                    }
                    'a' -> {
                        val colon = value.indexOf(':')
                        val attr = if (colon < 0) SdpAttribute(value) else SdpAttribute(value.substring(0, colon), value.substring(colon + 1))
                        (current?.attributes ?: sd.attributes) += attr
                    }
                    else -> (current?.lines ?: sd.lines) += SdpLine(type, value)
                }
            }
            return sd
        }
    }
}
