package krtc.sfu

import krtc.sfu.signaling.startSignaling

/**
 * Runs the SFU. Options (also as environment variables KRTC_*):
 *   --http-port 8080  --udp-port 50000  --announce 127.0.0.1  --shards <cores>
 */
public fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).filter { it.size == 2 }.associate { (k, v) -> k.removePrefix("--") to v }
    fun opt(name: String, env: String): String? = opts[name] ?: System.getenv(env)
    val config = SfuConfig(
        udpPort = opt("udp-port", "KRTC_UDP_PORT")?.toInt() ?: 50000,
        httpPort = opt("http-port", "KRTC_HTTP_PORT")?.toInt() ?: 8080,
        announcedAddress = opt("announce", "KRTC_ANNOUNCE") ?: "127.0.0.1",
        shards = opt("shards", "KRTC_SHARDS")?.toInt() ?: Runtime.getRuntime().availableProcessors(),
    )
    val sfu = Sfu(config)
    val server = startSignaling(sfu, config.httpPort)
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(500, 1000); sfu.close() })
    println("krtc-sfu: http://localhost:${config.httpPort}/  (UDP ${sfu.udp.localPort}, announced ${config.announcedAddress})")
    server.start(wait = true)
}
