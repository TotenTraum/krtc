package krtc.meet.log

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sends [ClientLog] to [endpoint] (krtc-sfu-server `--client-logs`, or the host's `krtcClientLogs` route) in batches:
 * every [intervalMillis], or sooner once [batchSize] entries wait. A batch that fails goes back in front and is sent
 * again after a pause that grows to a minute; a server that answers 404 (no log receiver) is not asked again.
 */
class LogUploader(
    private val endpoint: String,
    private val client: String,
    private val app: String,
    private val platform: String,
    private val http: HttpClient = HttpClient(),
    private val intervalMillis: Long = 10_000,
    private val batchSize: Int = 200,
) {
    private var job: Job? = null

    /** False once the server said it takes no logs. */
    var accepted: Boolean = true
        private set

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            var pause = intervalMillis
            while (accepted) {
                // Wait for the interval, or less once a full batch is there.
                withTimeoutOrNull(pause) { ClientLog.waiting.first { it >= batchSize } }
                pause = if (sendOnce()) intervalMillis else minOf(pause * 2, MAX_PAUSE_MILLIS)
            }
        }
    }

    /** Sends what waits (a few batches at most), e.g. before the call ends; gives up after [timeoutMillis]. */
    suspend fun flush(timeoutMillis: Long = 3_000) {
        withTimeoutOrNull(timeoutMillis) {
            repeat(FLUSH_BATCHES) { if (ClientLog.waiting.value == 0 || !sendOnce()) return@withTimeoutOrNull }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Sends one batch; true if it was delivered or there was nothing to send. */
    private suspend fun sendOnce(): Boolean {
        if (!accepted) return false
        val entries = ClientLog.take(batchSize)
        if (entries.isEmpty()) return true
        val body = buildJsonObject {
            put("client", client)
            put("app", app)
            put("platform", platform)
            put("entries", buildJsonArray {
                for (e in entries) add(buildJsonObject {
                    put("t", JsonPrimitive(e.timeMillis))
                    put("l", e.level.letter)
                    put("g", e.tag)
                    put("m", e.message)
                })
            })
        }.toString()
        val status = runCatching {
            http.post(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.status
        }.getOrNull()
        return when {
            status != null && status.value in 200..299 -> true
            status == HttpStatusCode.NotFound -> {
                accepted = false // nothing listens there: keep the entries in the local log only
                false
            }
            // Malformed or too large for this server: sending it again would not help.
            status == HttpStatusCode.BadRequest || status == HttpStatusCode.PayloadTooLarge -> true
            else -> {
                ClientLog.putBack(entries)
                false
            }
        }
    }

    companion object {
        const val MAX_PAUSE_MILLIS: Long = 60_000
        private const val FLUSH_BATCHES = 5

        /** The log endpoint of the server behind a signaling URL: `ws://host:port` → `http://host:port/logs`. */
        fun endpointFor(serverUrl: String): String {
            val base = serverUrl.trimEnd('/')
            val http = when {
                base.startsWith("wss://") -> "https://" + base.removePrefix("wss://")
                base.startsWith("ws://") -> "http://" + base.removePrefix("ws://")
                else -> base
            }
            return "$http/logs"
        }
    }
}
