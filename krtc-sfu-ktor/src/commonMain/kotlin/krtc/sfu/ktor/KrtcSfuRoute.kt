package krtc.sfu.ktor

import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import krtc.signaling.ServerMessage
import krtc.signaling.SignalingProtocol
import krtc.sfu.JoinDeniedException
import krtc.sfu.JoinRequest
import krtc.sfu.SignalSink
import krtc.sfu.Sfu
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Builds the [JoinRequest] for an incoming WebSocket. The default reads `room` and `name` query
 * parameters and passes an `Authorization: Bearer` token (or `token` query parameter) as credentials,
 * which the [Sfu]'s JoinPolicy can check.
 */
public typealias JoinRequestFactory = (ApplicationCall) -> JoinRequest?

public val DefaultJoinRequest: JoinRequestFactory = { call ->
    val q = call.request.queryParameters
    val room = q["room"]?.takeIf { it.isNotBlank() }?.take(64)
    room?.let {
        JoinRequest(
            room = it,
            displayName = q["name"]?.takeIf { n -> n.isNotBlank() } ?: "guest",
            credentials = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim() ?: q["token"],
        )
    }
}

/**
 * Serves krtc signaling over WebSocket at [path] for [sfu]: one connection per participant, JSON
 * messages from krtc-signaling. Requires the Ktor `WebSockets` plugin to be installed.
 *
 * ```
 * install(WebSockets)
 * routing { krtcSfu(sfu, "/rtc") }
 * ```
 *
 * A slow client whose outbound queue fills up ([outboxCapacity]) is disconnected rather than allowed
 * to grow server memory.
 */
public fun Route.krtcSfu(
    sfu: Sfu,
    path: String = "/ws",
    outboxCapacity: Int = 256,
    joinRequest: JoinRequestFactory = DefaultJoinRequest,
) {
    webSocket(path) { serve(sfu, joinRequest(call), outboxCapacity) }
}

/** Runs one krtc signaling session on an already accepted WebSocket (for hosts with custom routing). */
public suspend fun DefaultWebSocketServerSession.serve(sfu: Sfu, request: JoinRequest?, outboxCapacity: Int = 256) {
    if (request == null) {
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "room is required"))
        return
    }
    val outbox = Channel<String>(outboxCapacity)
    var closeReason: String? = null
    val sink = object : SignalSink {
        override fun send(message: ServerMessage) {
            if (outbox.trySend(SignalingProtocol.encode(message)).isFailure) close("signaling backlog")
        }

        override fun close(reason: String) {
            closeReason = closeReason ?: reason
            outbox.close()
        }
    }
    val participant = try {
        sfu.join(request, sink)
    } catch (e: JoinDeniedException) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.reason.take(120)))
        return
    }
    val ws = this
    val writer = launch {
        for (m in outbox) send(Frame.Text(m))
        ws.close(CloseReason(CloseReason.Codes.GOING_AWAY, (closeReason ?: "closed by server").take(120)))
    }
    try {
        for (frame in incoming) {
            if (frame is Frame.Text) sfu.onMessage(participant, frame.readText())
        }
    } finally {
        sfu.leave(participant, closeReason ?: "disconnected")
        outbox.close()
        writer.cancel()
    }
}
