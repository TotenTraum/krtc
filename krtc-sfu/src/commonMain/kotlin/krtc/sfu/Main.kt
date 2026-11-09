package krtc.sfu

import krtc.sfu.platform.availableProcessors
import krtc.sfu.platform.environment
import krtc.sfu.signaling.startSignaling

/**
 * Runs the SFU (same entry point on the JVM and as a Kotlin/Native executable).
 * Options, or environment variables KRTC_*:
 *   --http-port 8080 (or PORT)  --udp-port 50000  --announce 127.0.0.1  --shards <cores>
 */
public fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).filter { it.size == 2 }.associate { (k, v) -> k.removePrefix("--") to v }
    fun opt(name: String, env: String): String? = opts[name] ?: environment(env)
    val config = SfuConfig(
        udpPort = opt("udp-port", "KRTC_UDP_PORT")?.toInt() ?: 50000,
        httpPort = (opt("http-port", "KRTC_HTTP_PORT") ?: environment("PORT"))?.toInt() ?: 8080,
        announcedAddress = opt("announce", "KRTC_ANNOUNCE") ?: "127.0.0.1",
        shards = opt("shards", "KRTC_SHARDS")?.toInt() ?: availableProcessors(),
        simulatedLossPercent = opt("simulate-loss", "KRTC_SIMULATE_LOSS")?.toInt() ?: 0,
    )
    val sfu = Sfu(config)
    val server = startSignaling(sfu, config.httpPort)
    println("krtc-sfu: http://localhost:${config.httpPort}/  (UDP ${sfu.udp.localPort}, announced ${config.announcedAddress})")
    server.start(wait = true)
}
