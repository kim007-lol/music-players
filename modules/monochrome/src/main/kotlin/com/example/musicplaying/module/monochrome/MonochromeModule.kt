// MonochromeModule.kt

package com.example.musicplaying.module.monochrome

import com.example.musicplaying.module.AccountState
import com.example.musicplaying.module.CatalogTrack
import com.example.musicplaying.module.DiagnosticStep
import com.example.musicplaying.module.HttpRequest
import com.example.musicplaying.module.MatchedTrack
import com.example.musicplaying.module.ModuleChoice
import com.example.musicplaying.module.ModuleHost
import com.example.musicplaying.module.ModuleInfo
import com.example.musicplaying.module.ResolveResult
import com.example.musicplaying.module.SignInSpec
import com.example.musicplaying.module.SourceModule
import com.example.musicplaying.module.SourceStream
import com.example.musicplaying.module.StreamFormat
import com.example.musicplaying.module.TrackMatcher
import com.example.musicplaying.module.TrackQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Lossless FLAC from Monochrome, the way its own web app plays now (2026-10): the track streamer at
 * tracks.monochrome.st. A song is searched by artist and title, matched like every module matches,
 * and streamed as one FLAC file with byte ranges. No account, no challenge, no DRM.
 *
 * It replaces everything MusicPlaying had for Monochrome (the Unified Playback API behind a Turnstile
 * challenge, the Amazon and legacy TIDAL-proxy instances), which had all stopped answering.
 */
class MonochromeModule : SourceModule {

    override val info = ModuleInfo(
        id = Id,
        name = "Monochrome",
        version = "2.0",
        description = "Lossless FLAC for every song Monochrome has, no account needed, with every Audio Lab effect",
    )

    private lateinit var host: ModuleHost
    private val json = Json { ignoreUnknownKeys = true }
    private val matches = ConcurrentHashMap<String, CatalogTrack>()
    private val misses = ConcurrentHashMap<String, Long>()

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override val signIn: SignInSpec? = null

    override val choices: List<ModuleChoice> = emptyList()

    override fun accountState(): AccountState =
        host.storage.get("unreachable")?.let { AccountState.Expired(it) } ?: AccountState.SignedIn("No account needed")

    override fun onSignInHeader(name: String, value: String, url: String): Boolean = false

    override suspend fun verifyAccount(): AccountState {
        val response = runCatching { host.http(HttpRequest("$Base/search/tracks?q=test&limit=1", headers = headers)) }.getOrNull()
        return if (response?.ok == true) {
            host.storage.set("unreachable", null)
            AccountState.SignedIn("No account needed · server reachable")
        } else {
            val reason = "Monochrome's server didn't answer (${response?.code ?: "offline"})"
            host.storage.set("unreachable", reason)
            AccountState.Expired(reason)
        }
    }

    override fun signOut() {
        matches.clear()
        misses.clear()
    }

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        diagnose(track, picks) {}

    override suspend fun diagnose(track: TrackQuery, picks: Map<String, String>, report: (DiagnosticStep) -> Unit): ResolveResult {
        val key = "${track.title}|${track.artists.joinToString(",")}"
        val missedAt = misses[key]
        if (missedAt != null && System.currentTimeMillis() - missedAt < MissMemoryMs) {
            report(DiagnosticStep("Find the song", false, "Not on Monochrome"))
            return ResolveResult.NotFound("Not on Monochrome")
        }

        report(DiagnosticStep("Find the song", null, "Searching Monochrome…"))
        val found = matches[key] ?: run {
            val query = "${track.artists.firstOrNull().orEmpty()} ${TrackMatcher.core(track.title)}".trim()
            val response = runCatching {
                host.http(HttpRequest("$Base/search/tracks?q=${enc(query)}&limit=8", headers = headers))
            }.getOrElse {
                report(DiagnosticStep("Find the song", false, "Monochrome's server didn't answer"))
                return ResolveResult.Unavailable("Monochrome's server didn't answer")
            }
            if (!response.ok) {
                report(DiagnosticStep("Find the song", false, "Monochrome answered ${response.code}"))
                return ResolveResult.Unavailable("Monochrome answered ${response.code}")
            }
            host.storage.set("unreachable", null)
            val best = TrackMatcher.ranked(track, tracksIn(response.text)).firstOrNull()
            if (best == null) {
                misses[key] = System.currentTimeMillis()
                report(DiagnosticStep("Find the song", false, "No matching recording of “${track.title}”"))
                return ResolveResult.NotFound("No matching recording on Monochrome")
            }
            matches[key] = best.track
            best.track
        }
        report(DiagnosticStep("Find the song", true, "${found.title} · ${found.artist}"))

        // the head of the file: it should open as FLAC, and its STREAMINFO says how fine it is
        report(DiagnosticStep("Stream", null, "Opening the stream…"))
        val url = "$Base/track/${found.id}"
        val head = runCatching { host.http(HttpRequest(url, headers = streamHeaders + ("Range" to "bytes=0-41"))) }.getOrNull()
        if (head == null || !head.ok) {
            report(DiagnosticStep("Stream", false, "The stream answered ${head?.code ?: "nothing"}"))
            return ResolveResult.Unavailable("Monochrome's stream answered ${head?.code ?: "nothing"}")
        }
        val info = flacInfo(head.body)
        if (info == null) {
            report(DiagnosticStep("Stream", false, "Not a FLAC file"))
            return ResolveResult.Unavailable("Monochrome's stream isn't FLAC")
        }
        report(DiagnosticStep("Stream", true, info.label))

        return ResolveResult.Found(
            SourceStream(
                url = url,
                format = StreamFormat.Progressive,
                headers = streamHeaders,
                sourceLabel = "Monochrome",
                qualityLabel = info.shortLabel,
                codec = "FLAC",
                matched = MatchedTrack(
                    id = found.id,
                    title = found.title,
                    artist = found.artist,
                    album = found.album,
                    durationMs = found.durationMs,
                    confidence = TrackMatcher.score(track, found),
                ),
            )
        )
    }

    internal fun tracksIn(text: String): List<CatalogTrack> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val tracks = root["tracks"] as? JsonArray ?: return emptyList()
        return tracks.mapNotNull { element ->
            val t = element as? JsonObject ?: return@mapNotNull null
            if ((t["playable"] as? JsonPrimitive)?.booleanOrNull == false) return@mapNotNull null
            val id = t.string("trackId") ?: t.string("id") ?: return@mapNotNull null
            val artists = (t["artistNames"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            // the streamer gives milliseconds; older answers gave seconds
            val duration = (t["duration"] as? JsonPrimitive)?.longOrNull?.let { if (it > 10_000) it else it * 1000 }
            CatalogTrack(
                id = id,
                title = t.string("title") ?: return@mapNotNull null,
                artist = artists.joinToString(", "),
                album = t.string("albumTitle") ?: t.string("releaseTitle"),
                durationMs = duration,
                isrc = t.string("isrc"),
            )
        }
    }

    /** what a FLAC file's STREAMINFO says: its bit depth and sample rate */
    internal class FlacInfo(val bitsPerSample: Int, val sampleRate: Int) {
        val hiRes: Boolean get() = bitsPerSample > 16 || sampleRate > 48_000
        private val rate: String get() = if (sampleRate % 1000 == 0) "${sampleRate / 1000}" else String.format("%.1f", sampleRate / 1000.0)
        val shortLabel: String get() = if (hiRes) "Hi-Res FLAC" else "FLAC"
        val label: String get() = "$shortLabel · $bitsPerSample-bit / $rate kHz"
    }

    /**
     * "fLaC", then the STREAMINFO block: 4 bytes of header, then (after the block and frame
     * sizes, 10 bytes) 20 bits of sample rate, 3 of channels - 1, 5 of bits per sample - 1.
     */
    internal fun flacInfo(bytes: ByteArray): FlacInfo? {
        if (bytes.size < 4 || bytes.copyOfRange(0, 4).decodeToString() != "fLaC") return null
        if (bytes.size < 4 + 4 + 18) return FlacInfo(16, 44_100)
        val b = bytes.copyOfRange(8, 26).map { it.toInt() and 0xff }
        val sampleRate = (b[10] shl 12) or (b[11] shl 4) or (b[12] shr 4)
        val bits = (((b[12] and 0x01) shl 4) or (b[13] shr 4)) + 1
        return if (sampleRate in 8_000..768_000 && bits in 8..32) FlacInfo(bits, sampleRate) else FlacInfo(16, 44_100)
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    companion object {
        const val Id = "monochrome"
        private const val Base = "https://tracks.monochrome.st"
        private const val MissMemoryMs = 30L * 60 * 1000
        private const val UserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        private val headers = mapOf("Accept" to "application/json", "User-Agent" to UserAgent)
        private val streamHeaders = mapOf("User-Agent" to UserAgent)
    }
}
