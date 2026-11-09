package krtc.sfu.signaling

import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import krtc.sfu.Sfu
import krtc.sfu.room.SignalSink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import krtc.sfu.platform.logger
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP + WebSocket signaling.
 *
 * `GET /ws?room=<room>&name=<name>` — JSON messages:
 * - server → client: `welcome`, `offer {version, sdp, mids}`, `peer-joined`, `peer-left`, `speaking`;
 * - client → server: `answer {version, sdp}`.
 *
 * `GET /` serves a test client, `GET /metrics` the counters.
 */
public fun startSignaling(sfu: Sfu, port: Int): EmbeddedServer<*, *> {
    val log = logger("krtc.sfu.signaling")
    return embeddedServer(CIO, port = port) {
        install(WebSockets) {
            pingPeriod = 15.seconds
            timeout = 30.seconds
        }
        routing {
            get("/") { call.respondText(INDEX_HTML, ContentType.Text.Html) }
            get("/metrics") { call.respondText(sfu.metricsJson().toString(), ContentType.Application.Json) }
            webSocket("/ws") {
                val room = call.request.queryParameters["room"]?.takeIf { it.isNotBlank() }?.take(64)
                val name = call.request.queryParameters["name"]?.takeIf { it.isNotBlank() } ?: "guest"
                if (room == null) {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "room is required"))
                    return@webSocket
                }
                // Bounded outbox: a client that cannot keep up with signaling is disconnected
                // rather than allowed to grow server memory.
                val outbox = Channel<String>(capacity = 256)
                val wsSession = this
                val sink = object : SignalSink {
                    override fun send(message: String) {
                        if (outbox.trySend(message).isFailure) outbox.close()
                    }
                    override fun close() {
                        outbox.close()
                    }
                }
                val participant = sfu.join(room, name, sink)
                val writer = launch {
                    for (m in outbox) send(Frame.Text(m))
                    wsSession.close(CloseReason(CloseReason.Codes.GOING_AWAY, "closed by server"))
                }
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val msg = runCatching { Json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                        when (msg["type"]?.jsonPrimitive?.content) {
                            "answer" -> {
                                val version = msg["version"]?.jsonPrimitive?.long ?: continue
                                val sdp = msg["sdp"]?.jsonPrimitive?.content ?: continue
                                sfu.onAnswer(participant, version, sdp)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    log.debug("signaling error for $participant: $t")
                } finally {
                    sfu.leave(participant)
                    outbox.close()
                    writer.cancel()
                }
            }
        }
    }
}
