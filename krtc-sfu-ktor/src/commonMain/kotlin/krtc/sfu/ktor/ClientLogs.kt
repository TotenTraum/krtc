package krtc.sfu.ktor

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * One batch of a client's log, as sent to [krtcClientLogs]:
 * `{"client": id, "app": "...", "platform": "...", "entries": [{"t": epochMillis, "l": "I", "g": tag, "m": message}]}`.
 * [client] is 1..64 of `A-Z a-z 0-9 . _ -`.
 */
public class ClientLogBatch(
    public val client: String,
    public val app: String,
    public val platform: String,
    public val entries: List<ClientLogEntry>,
)

/** [level]: one letter (D, I, W, E). */
public class ClientLogEntry(public val timeMillis: Long, public val level: String, public val tag: String, public val message: String)

/** Where accepted batches go. May be called from several requests at once. */
public fun interface ClientLogStore {
    public suspend fun store(batch: ClientLogBatch)
}

/**
 * Receives client logs (e.g. krtc-meet's) at [path]: POST with a [ClientLogBatch] as JSON. Answers 204 when stored,
 * 400 for a malformed batch, 413 above [maxBodyBytes], 429 when one address sends more than [requestsPerMinute].
 * There is no authentication: put it behind the host's own (Ktor `authenticate { }`) if the server is public.
 */
public fun Route.krtcClientLogs(
    store: ClientLogStore,
    path: String = "/logs",
    maxBodyBytes: Long = 1L shl 20,
    requestsPerMinute: Int = 120,
) {
    val limiter = RateLimiter(requestsPerMinute)
    post(path) {
        val length = call.request.contentLength()
        if (length != null && length > maxBodyBytes) return@post call.respond(HttpStatusCode.PayloadTooLarge)
        if (!limiter.allow(call.request.origin.remoteHost)) return@post call.respond(HttpStatusCode.TooManyRequests)
        val body = call.receiveChannel().readRemaining(maxBodyBytes + 1).readByteArray()
        if (body.size > maxBodyBytes) return@post call.respond(HttpStatusCode.PayloadTooLarge)
        val batch = parseClientLogBatch(body.decodeToString()) ?: return@post call.respond(HttpStatusCode.BadRequest)
        store.store(batch)
        call.respond(HttpStatusCode.NoContent)
    }
}

private val clientId = Regex("[A-Za-z0-9._-]{1,64}")
private const val MAX_ENTRIES = 5_000
private const val MAX_MESSAGE_CHARS = 64 * 1024

/** Null if [text] is not a valid batch. Over-long fields are cut, not rejected. */
public fun parseClientLogBatch(text: String): ClientLogBatch? = runCatching {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
    val client = root["client"]?.jsonPrimitive?.contentOrNull?.takeIf { clientId.matches(it) } ?: return null
    val entries = (root["entries"] as? JsonArray ?: return null).take(MAX_ENTRIES).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        ClientLogEntry(
            timeMillis = o["t"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
            level = o["l"]?.jsonPrimitive?.contentOrNull?.take(1) ?: "I",
            tag = o["g"]?.jsonPrimitive?.contentOrNull?.take(64) ?: "",
            message = o["m"]?.jsonPrimitive?.contentOrNull?.take(MAX_MESSAGE_CHARS) ?: "",
        )
    }
    ClientLogBatch(
        client = client,
        app = root["app"]?.jsonPrimitive?.contentOrNull?.take(128) ?: "",
        platform = root["platform"]?.jsonPrimitive?.contentOrNull?.take(128) ?: "",
        entries = entries,
    )
}.getOrNull()

/**
 * Writes batches to `<dir>/<yyyy-MM-dd>/<client>.log` (UTC date of arrival), one line per entry:
 * `2026-11-13T02:30:33.510Z I [call] message` (newlines in messages are indented continuation lines). A client stops
 * being written for the day once its file reaches [maxBytesPerClientPerDay].
 */
public class FileClientLogStore(
    private val dir: String,
    private val maxBytesPerClientPerDay: Long = 50L shl 20,
) : ClientLogStore {
    private val lock = Mutex()

    @OptIn(ExperimentalTime::class)
    override suspend fun store(batch: ClientLogBatch) {
        val day = isoDate(Clock.System.now().toEpochMilliseconds())
        val dayDir = Path(dir, day)
        val file = Path(dayDir, batch.client + ".log")
        lock.withLock {
            SystemFileSystem.createDirectories(dayDir)
            val size = SystemFileSystem.metadataOrNull(file)?.size ?: 0L
            if (size >= maxBytesPerClientPerDay) return
            val text = buildString {
                if (size == 0L) append("# ").append(batch.app).append(" on ").append(batch.platform).append('\n')
                for (e in batch.entries) {
                    append(isoTime(e.timeMillis)).append(' ').append(e.level).append(" [").append(e.tag).append("] ")
                    append(e.message.replace("\n", "\n    ")).append('\n')
                }
            }
            SystemFileSystem.sink(file, append = true).buffered().use { it.writeString(text) }
        }
    }
}

/** At most [perMinute] requests a minute from one address (a window per address, forgotten after a minute). */
private class RateLimiter(private val perMinute: Int) {
    private val lock = Mutex()
    private val windows = HashMap<String, Pair<Long, Int>>()

    @OptIn(ExperimentalTime::class)
    suspend fun allow(address: String): Boolean = lock.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        if (windows.size > 10_000) windows.entries.removeAll { now - it.value.first > 60_000 }
        val (start, count) = windows[address]?.takeIf { now - it.first < 60_000 } ?: (now to 0)
        if (count >= perMinute) return@withLock false
        windows[address] = start to count + 1
        true
    }
}

/** `yyyy-MM-dd` (UTC) of [millis] since the epoch. */
internal fun isoDate(millis: Long): String {
    val days = floorDiv(millis, 86_400_000L)
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    val z = days + 719_468
    val era = floorDiv(z, 146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

/** `yyyy-MM-ddTHH:mm:ss.SSSZ` (UTC). */
internal fun isoTime(millis: Long): String {
    val ms = millis - floorDiv(millis, 86_400_000L) * 86_400_000L
    val h = ms / 3_600_000
    val min = ms / 60_000 % 60
    val s = ms / 1_000 % 60
    val milli = ms % 1_000
    fun two(v: Long) = v.toString().padStart(2, '0')
    return "${isoDate(millis)}T${two(h)}:${two(min)}:${two(s)}.${milli.toString().padStart(3, '0')}Z"
}

private fun floorDiv(a: Long, b: Long): Long = if (a >= 0) a / b else -((-a + b - 1) / b)
