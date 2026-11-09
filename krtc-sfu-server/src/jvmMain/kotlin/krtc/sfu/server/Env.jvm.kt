package krtc.sfu.server

internal actual fun environment(name: String): String? = System.getenv(name)
