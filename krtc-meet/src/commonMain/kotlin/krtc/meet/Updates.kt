package krtc.meet

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A newer build than this one: its version and the page to download it from. */
data class Update(val version: String, val url: String)

/**
 * Looks for a newer build among the GitHub releases of [BuildInfo.RELEASES_REPO]. Builds from main (1.0.N) follow the
 * "nightly" pre-release; release builds follow the newest release. Null when up to date or unreachable.
 */
suspend fun checkForUpdate(current: String = BuildInfo.VERSION, http: HttpClient = HttpClient()): Update? = runCatching {
    val body = http.get("https://api.github.com/repos/${BuildInfo.RELEASES_REPO}/releases?per_page=20") {
        header("Accept", "application/vnd.github+json")
    }.bodyAsText()
    newerRelease(current, body)
}.getOrNull()

/** From the GitHub releases JSON: the newest release in [current]'s channel if it is newer than [current]. */
fun newerRelease(current: String, releasesJson: String): Update? {
    val mine = parseVersion(current) ?: return null
    val nightlyBuild = mine.first == 1 && mine.second == 0
    val releases = Json.parseToJsonElement(releasesJson) as? JsonArray ?: return null
    return releases.mapNotNull { r ->
        val o = r as? JsonObject ?: return@mapNotNull null
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val prerelease = o["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
        if ((tag == "nightly") != nightlyBuild || (prerelease && tag != "nightly")) return@mapNotNull null
        val title = o["name"]?.jsonPrimitive?.contentOrNull ?: tag
        val version = Regex("""(\d+)\.(\d+)\.(\d+)""").find(title)?.value ?: return@mapNotNull null
        val url = o["html_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        Update(version, url)
    }.filter { u -> compareVersions(parseVersion(u.version)!!, mine) > 0 }
        .maxWithOrNull { a, b -> compareVersions(parseVersion(a.version)!!, parseVersion(b.version)!!) }
}

private fun parseVersion(v: String): Triple<Int, Int, Int>? =
    Regex("""(\d+)\.(\d+)\.(\d+)""").find(v)?.destructured?.let { (a, b, c) -> Triple(a.toInt(), b.toInt(), c.toInt()) }

private fun compareVersions(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int =
    compareValuesBy(a, b, { it.first }, { it.second }, { it.third })
