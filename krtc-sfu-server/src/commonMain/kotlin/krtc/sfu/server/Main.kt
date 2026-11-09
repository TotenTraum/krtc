package krtc.sfu.server

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
import krtc.sfu.Sfu
import krtc.sfu.SfuConfig
import krtc.sfu.SfuMetrics
import krtc.sfu.ktor.krtcSfu
import kotlin.time.Duration.Companion.seconds

/**
 * The standalone krtc SFU: krtc-sfu + krtc-sfu-ktor + a test page, on the JVM or as a Kotlin/Native
 * executable. Options (or environment variables KRTC_*):
 *   --http-port 8080 (or PORT)  --udp-port 50000  --announce 127.0.0.1  --shards <cores>  --simulate-loss 0
 */
public fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).filter { it.size == 2 }.associate { (k, v) -> k.removePrefix("--") to v }
    fun opt(name: String, env: String): String? = opts[name] ?: environment(env)
    val defaults = SfuConfig()
    val config = SfuConfig(
        udpPort = opt("udp-port", "KRTC_UDP_PORT")?.toInt() ?: defaults.udpPort,
        announcedAddress = opt("announce", "KRTC_ANNOUNCE") ?: defaults.announcedAddress,
        shards = opt("shards", "KRTC_SHARDS")?.toInt() ?: defaults.shards,
        simulatedLossPercent = opt("simulate-loss", "KRTC_SIMULATE_LOSS")?.toInt() ?: 0,
    )
    val httpPort = (opt("http-port", "KRTC_HTTP_PORT") ?: environment("PORT"))?.toInt() ?: 8080
    val sfu = Sfu(config)
    val server = standaloneServer(sfu, httpPort)
    println("krtc-sfu: http://localhost:$httpPort/  (UDP ${sfu.udpPort}, announced ${config.announcedAddress})")
    server.start(wait = true)
}

/** The standalone HTTP server: test page at `/`, signaling at `/ws`, counters at `/metrics`. */
public fun standaloneServer(sfu: Sfu, port: Int): EmbeddedServer<*, *> = embeddedServer(CIO, port = port) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
    }
    routing {
        get("/") { call.respondText(INDEX_HTML, ContentType.Text.Html) }
        get("/metrics") { call.respondText(sfu.metrics().toJson(), ContentType.Application.Json) }
        krtcSfu(sfu, "/ws")
    }
}

internal fun SfuMetrics.toJson(): String = buildString {
    append('{')
    val fields = listOf(
        "participants" to participants, "rooms" to rooms, "rtpIn" to rtpIn, "rtpOut" to rtpOut,
        "bytesIn" to bytesIn, "bytesOut" to bytesOut, "rtcpIn" to rtcpIn, "rtcpOut" to rtcpOut,
        "srtpFailures" to srtpFailures, "droppedOverload" to droppedOverload, "keyframeRequests" to keyframeRequests,
        "nacksAnswered" to nacksAnswered, "stunRequests" to stunRequests, "stunRejected" to stunRejected,
        "dtlsConnected" to dtlsConnected, "dtlsFailed" to dtlsFailed, "unknownSource" to unknownSource,
    )
    fields.forEach { (k, v) -> append('"').append(k).append("\":").append(v).append(',') }
    append("\"shardQueueDepth\":").append(shardQueueDepth.joinToString(",", "[", "]")).append('}')
}

internal expect fun environment(name: String): String?
