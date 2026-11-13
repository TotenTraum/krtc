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
import krtc.sfu.SfuLinkStats
import krtc.sfu.SfuMetrics
import krtc.sfu.ktor.FileClientLogStore
import krtc.sfu.ktor.krtcClientLogs
import krtc.sfu.ktor.krtcSfu
import kotlin.time.Duration.Companion.seconds

/**
 * The standalone krtc SFU: krtc-sfu + krtc-sfu-ktor + a test page, on the JVM or as a Kotlin/Native
 * executable. Options (or environment variables KRTC_*):
 *   --http-port 8080 (or PORT)  --udp-port 50000  --bind 0.0.0.0  --announce 127.0.0.1[,more]  --shards <cores>
 *   --ice-tcp true  --ice-udp true
 * `--bind 0.0.0.0` (IPv4) or `::` (IPv4 + IPv6) listens on every interface address and answers from the address
 * each client used; `--announce` lists the addresses clients should reach (public IPs behind NAT).
 *   --client-logs <dir>  accepts client logs (krtc-meet) at POST /logs into <dir>/<date>/<client>.log; off by default
 *   --simulate-loss 0  --simulate-uplink-loss 0  --simulate-downlink-kbps 0   (testing only)
 */
/** Whether a TCP listener can bind [port] on all interfaces right now. */
internal expect fun tcpPortFree(port: Int): Boolean

/** Prints [message] to stderr and exits with status 1. */
internal expect fun exitWithError(message: String): Nothing

public fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).filter { it.size == 2 }.associate { (k, v) -> k.removePrefix("--") to v }
    fun opt(name: String, env: String): String? = opts[name] ?: environment(env)
    val defaults = SfuConfig()
    val config = SfuConfig(
        udpPort = opt("udp-port", "KRTC_UDP_PORT")?.toInt() ?: defaults.udpPort,
        bindAddress = opt("bind", "KRTC_BIND") ?: defaults.bindAddress,
        announcedAddresses = (opt("announce", "KRTC_ANNOUNCE") ?: defaults.announcedAddress).split(',').map { it.trim() }.filter { it.isNotEmpty() },
        shards = opt("shards", "KRTC_SHARDS")?.toInt() ?: defaults.shards,
        simulatedLossPercent = opt("simulate-loss", "KRTC_SIMULATE_LOSS")?.toInt() ?: 0,
        simulatedUplinkLossPercent = opt("simulate-uplink-loss", "KRTC_SIMULATE_UPLINK_LOSS")?.toInt() ?: 0,
        iceTcp = opt("ice-tcp", "KRTC_ICE_TCP")?.toBooleanStrictOrNull() ?: defaults.iceTcp,
        iceUdp = opt("ice-udp", "KRTC_ICE_UDP")?.toBooleanStrictOrNull() ?: defaults.iceUdp,
        simulatedDownlinkBps = (opt("simulate-downlink-kbps", "KRTC_SIMULATE_DOWNLINK_KBPS")?.toLong() ?: 0) * 1000,
    )
    val httpPort = (opt("http-port", "KRTC_HTTP_PORT") ?: environment("PORT"))?.toInt() ?: 8080
    // Ktor binds inside a coroutine, where a busy port ends the process with an uncaught exception: check first.
    if (!tcpPortFree(httpPort)) exitWithError("krtc-sfu: HTTP port $httpPort is already in use (another krtc-sfu? use --http-port)")
    val sfu = try {
        Sfu(config)
    } catch (e: IllegalArgumentException) {
        exitWithError("krtc-sfu: ${e.message}")
    }
    val clientLogs = opt("client-logs", "KRTC_CLIENT_LOGS")
    val server = standaloneServer(sfu, httpPort, clientLogs)
    println("krtc-sfu: http://localhost:$httpPort/  (UDP ${sfu.udpPort}, announced ${config.announcedAddresses.ifEmpty { listOf(config.announcedAddress) }.joinToString()})")
    clientLogs?.let { println("krtc-sfu: client logs at POST /logs into $it") }
    server.start(wait = true)
}

/**
 * The standalone HTTP server: test page at `/`, signaling at `/ws`, counters at `/metrics`, and client logs at
 * `/logs` into [clientLogsDir] when given.
 */
public fun standaloneServer(sfu: Sfu, port: Int, clientLogsDir: String? = null): EmbeddedServer<*, *> = embeddedServer(CIO, port = port) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
    }
    routing {
        get("/") { call.respondText(INDEX_HTML, ContentType.Text.Html) }
        get("/metrics") { call.respondText(sfu.metrics().toJson(), ContentType.Application.Json) }
        get("/stats") { call.respondText(sfu.linkStats().toJson(), ContentType.Application.Json) }
        krtcSfu(sfu, "/ws")
        clientLogsDir?.let { krtcClientLogs(FileClientLogStore(it)) }
    }
}

internal fun SfuMetrics.toJson(): String = buildString {
    append('{')
    val fields = listOf(
        "participants" to participants, "rooms" to rooms, "rtpIn" to rtpIn, "rtpOut" to rtpOut,
        "bytesIn" to bytesIn, "bytesOut" to bytesOut, "rtcpIn" to rtcpIn, "rtcpOut" to rtcpOut,
        "srtpFailures" to srtpFailures, "droppedOverload" to droppedOverload, "keyframeRequests" to keyframeRequests,
        "nacksAnswered" to nacksAnswered, "nacksSent" to nacksSent, "rtxRecovered" to rtxRecovered, "dataMessagesIn" to dataMessagesIn, "dataMessagesOut" to dataMessagesOut, "dataMessagesDropped" to dataMessagesDropped, "stunRequests" to stunRequests, "stunRejected" to stunRejected,
        "dtlsConnected" to dtlsConnected, "dtlsFailed" to dtlsFailed, "unknownSource" to unknownSource,
    )
    fields.forEach { (k, v) -> append('"').append(k).append("\":").append(v).append(',') }
    append("\"shardQueueDepth\":").append(shardQueueDepth.joinToString(",", "[", "]")).append('}')
}

internal fun List<SfuLinkStats>.toJson(): String = joinToString(",", "[", "]") { s ->
    val layers = s.videoLayers.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
    "{\"participant\":\"${s.participantId}\",\"room\":\"${s.roomName}\",\"downlinkEstimateBps\":${s.downlinkEstimateBps}," +
        "\"downlinkAckedBps\":${s.downlinkAckedBps},\"downlinkLossPercent\":${(s.downlinkLossPercent * 10).toLong() / 10.0},\"videoLayers\":$layers}"
}

internal expect fun environment(name: String): String?
