package krtc.idl

// Minimal DOM base types the WebRTC IDL builds on (defined in the DOM / WebIDL standards).

/** DOM `EventTarget`. In krtc, events are exposed as `Flow` properties named after the spec's `on<event>` handlers. */
public interface EventTarget

/** DOM `Event`. */
public interface Event {
    public val type: String
    public val timeStamp: Double
}

/** DOM `EventInit`. */
public open class EventInit(
    public open val bubbles: Boolean = false,
    public open val cancelable: Boolean = false,
    public open val composed: Boolean = false,
)

/** WebIDL `DOMException`. */
public interface DOMException {
    public val name: String
    public val message: String
}
