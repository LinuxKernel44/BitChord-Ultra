package com.music.bitchord.data.sources.eclipse

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonException
import com.music.bitchord.data.sources.addon.AddonNotFound
import com.music.bitchord.data.sources.addon.AddonStream
import com.music.bitchord.data.sources.addon.AddonUnavailable
import com.music.bitchord.data.sources.module.SharedCalls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * One Eclipse addon server and every question the protocol lets us ask it.
 *
 * Plain GETs returning JSON. Differences from [AddonClient], which this is
 * modelled on: there is no BitChord attestation (the Eclipse protocol has none —
 * a private token lives in the URL path), the user's settings travel as query
 * parameters on every request, a `notice` may ride on any reply at any status,
 * and a `503` with `Retry-After` on `/stream` means "preparing", to be waited
 * out for up to [PREPARE_BUDGET_MS].
 *
 * [settings] supplies the listener's values for the addon's settings form,
 * already resolved for the current network; [notify] receives every notice the
 * addon sends, with whether it came on the manifest.
 */
class EclipseClient(
    rawBaseUrl: String,
    private val settings: () -> Map<String, String> = { emptyMap() },
    private val notify: (EclipseNotice, Boolean) -> Unit = { _, _ -> },
    /** The listener's own `User-Agent` for this addon, or blank for BitChord's. See [userAgentFor]. */
    private val userAgent: () -> String = { "" },
) {
    val baseUrl: String = AddonClient.normalizeBase(rawBaseUrl)

    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manifests = SharedCalls<EclipseManifest>(MANIFEST_TTL_MS, calls, ::log)
    private val searches = SharedCalls<EclipseSearchResponse>(SEARCH_TTL_MS, calls, ::log)
    private val streams = SharedCalls<AddonStream>(STREAM_TTL_MS, calls, ::log)
    private val details = SharedCalls<JsonObject>(SEARCH_TTL_MS, calls, ::log)
    private val rows = SharedCalls<EclipseCatalogResponse>(SEARCH_TTL_MS, calls, ::log)
    private val resolved = SharedCalls<EclipseResolveResponse>(SEARCH_TTL_MS, calls, ::log)
    private val isrcs = SharedCalls<EclipseIsrcResponse>(SEARCH_TTL_MS, calls, ::log)

    @Volatile
    private var quietUntilMs = 0L

    private fun log(line: String) = TrackLog.d(TAG, line)

    // ── Questions ─────────────────────────────────────────────────────────

    /** The manifest, refetched every [MANIFEST_TTL_MS] — the protocol's "update your server and users never reinstall". */
    suspend fun manifest(): Result<EclipseManifest> = manifests.get(
        key = baseUrl,
        describe = { "▶ eclipse manifest(${AddonClient.redact(baseUrl)})" },
    ) {
        fetch<EclipseManifest>(listOf("manifest.json"), emptyMap(), fromManifest = true, withSettings = false)
            .mapCatching { manifest ->
                if (manifest.id.isBlank()) throw AddonException("That URL answered, but not with an addon manifest")
                if (!manifest.isPlayable) {
                    throw AddonException(
                        "This addon declares ${manifest.resources.joinToString(", ")} — BitChord needs search",
                    )
                }
                manifest
            }.recoverCatching { failure ->
                if (failure is AddonNotFound) throw AddonException("No manifest at that URL")
                throw failure
            }
    }

    suspend fun search(query: String): Result<EclipseSearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Result.success(EclipseSearchResponse())
        val params = settingParams(tier = null)
        return searches.get(
            key = keyOf("search", trimmed, params.toString()),
            describe = { "▶ eclipse search(${AddonClient.redact(baseUrl)}) q=\"$trimmed\"" },
        ) { fetch(listOf("search"), params + ("q" to trimmed)) }
    }

    /** A playable URL. [tier] is the app's ask (`LOSSLESS`/`HIGH`/`LOW`), folded into a declared `quality` select. */
    suspend fun stream(trackId: String, tier: String): Result<AddonStream> {
        val params = settingParams(tier)
        return streams.get(
            key = keyOf("stream", trackId, params.toString()),
            describe = { "▶ eclipse stream(${AddonClient.redact(baseUrl)}) id=$trackId tier=$tier" },
        ) { fetch(listOf("stream", trackId), params, preparing = true) }
    }

    suspend fun album(id: String): Result<EclipseAlbum> = detail("album", id)
    suspend fun artist(id: String): Result<EclipseArtist> = detail("artist", id)
    suspend fun playlist(id: String): Result<EclipsePlaylist> = detail("playlist", id)

    private suspend inline fun <reified T> detail(kind: String, id: String): Result<T> {
        val params = settingParams(tier = null)
        return details.get(
            key = keyOf(kind, id, params.toString()),
            describe = { "▶ eclipse $kind(${AddonClient.redact(baseUrl)}) id=$id" },
        ) { fetch(listOf(kind, id), params) }.mapCatching { json.decodeFromJsonElement<T>(it) }
    }

    /** One page of a catalogue row. [skip] is a multiple of 100; fewer than 100 items is the end of the row. */
    suspend fun catalog(id: String, skip: Int = 0): Result<List<EclipseItem>> {
        val params = settingParams(tier = null) + ("skip" to skip.toString())
        return rows.get(
            key = keyOf("catalog", id, params.toString()),
            describe = { "▶ eclipse catalog(${AddonClient.redact(baseUrl)}) id=$id skip=$skip" },
        ) { fetch<EclipseCatalogResponse>(listOf("catalog", id), params) }.map { it.items }
    }

    /** The addon's own track id for [isrc], or null when it does not hold the recording. */
    suspend fun resolveIsrc(isrc: String): Result<String?> {
        val params = settingParams(tier = null) + ("isrc" to isrc)
        return isrcs.get(
            key = keyOf("isrc", isrc, params.toString()),
            describe = { "▶ eclipse resolve-isrc(${AddonClient.redact(baseUrl)}) isrc=$isrc" },
        ) { fetch<EclipseIsrcResponse>(listOf("resolve-isrc"), params) }
            .map { it.resolved }
            .recoverCatching { if (it is AddonNotFound) null else throw it }
    }

    /** Exact-recording match. A null item is the protocol's normal "not here", not an error. */
    suspend fun resolve(title: String, artist: String, durationMs: Long?, isrc: String?): Result<EclipseItem?> {
        val params = LinkedHashMap(settingParams(tier = null))
        params["title"] = title
        params["artist"] = artist
        durationMs?.let { params["durationMs"] = it.toString() }
        isrc?.let { params["isrc"] = it }
        return resolved.get(
            key = keyOf("resolve", params.toString()),
            describe = { "▶ eclipse resolve(${AddonClient.redact(baseUrl)}) \"$title\" — $artist" },
        ) { fetch<EclipseResolveResponse>(listOf("resolve"), params) }
            .map { it.item?.takeIf { item -> item.id.isNotBlank() } }
            .recoverCatching { if (it is AddonNotFound) null else throw it }
    }

    // ── Settings ──────────────────────────────────────────────────────────

    /**
     * What goes on every request: each declared setting's default, then the
     * listener's values over it, then — for the one thing this app has its own
     * opinion on — `quality` folded to what this request is for.
     */
    private suspend fun settingParams(tier: String?): Map<String, String> {
        val declared = manifest().getOrNull()?.settings.orEmpty()
        val chosen = settings()
        val params = LinkedHashMap<String, String>()
        declared.forEach { setting ->
            if (setting.key.isBlank()) return@forEach
            (chosen[setting.key] ?: setting.defaultValue)?.let { params[setting.key] = it }
        }
        // Keys the addon never declared but the listener's stored values carry
        // are not sent: a setting that has left the manifest has left the form.
        if (tier != null && tier != AddonClient.TIER_LOSSLESS) {
            val options = declared.firstOrNull { it.key == QUALITY_KEY }
                ?.options.orEmpty().mapNotNull { it.stringValue }
            matchTier(tier, options)?.let { params[QUALITY_KEY] = it }
        }
        return params
    }

    /** The option that best answers a request below the top tier, matched on what it says rather than where it sits. */
    private fun matchTier(tier: String, options: List<String>): String? {
        if (options.isEmpty()) return null
        options.firstOrNull { it.equals(tier, ignoreCase = true) }?.let { return it }
        val wanted = if (tier.equals(AddonClient.TIER_LOW, ignoreCase = true)) LOW_WORDS else HIGH_WORDS
        return options.firstOrNull { option -> wanted.any { it in option.lowercase() } } ?: options.first()
    }

    // ── Transport ─────────────────────────────────────────────────────────

    private fun endpoint(segments: List<String>, params: Map<String, String>): String {
        val base = baseUrl.toHttpUrlOrNull() ?: throw AddonException("That is not a usable address")
        val builder = base.newBuilder()
        segments.forEach(builder::addPathSegment)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private suspend inline fun <reified T> fetch(
        segments: List<String>,
        params: Map<String, String>,
        fromManifest: Boolean = false,
        withSettings: Boolean = true,
        preparing: Boolean = false,
    ): Result<T> {
        val url = endpoint(segments, if (withSettings) params else emptyMap())
        return runCatching { json.decodeFromString<T>(body(url, fromManifest, preparing)) }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                if (failure !is AddonNotFound) {
                    TrackLog.w(TAG, "  ✗ eclipse call failed ${AddonClient.redact(url)}: ${failure.message}")
                }
            }
    }

    /**
     * One GET with the protocol's back-pressure honoured, and any notice in the
     * reply (whatever its status) handed to [notify].
     *
     *  - `429` is waited out like [AddonClient] does — `Retry-After` when sent.
     *  - `503` + `Retry-After` on a stream ([preparing]) is "preparing this song":
     *    wait up to [PREPARE_BUDGET_MS] in total, retrying, then give up so the
     *    next source is tried. The notice (same id each time) updates in place.
     *  - `404` is a miss, not a fault.
     */
    private suspend fun body(url: String, fromManifest: Boolean, preparing: Boolean): String = withContext(Dispatchers.IO) {
        var attempt = 0
        var waitedMs = 0L
        while (true) {
            (quietUntilMs - System.currentTimeMillis()).takeIf { it > 0 }?.let { delay(it) }
            val request = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", userAgentFor(userAgent()))
                .build()
            val wait = client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                noticeIn(text)?.let { notify(it, fromManifest) }
                when {
                    response.isSuccessful ->
                        return@withContext text.takeIf { it.isNotBlank() } ?: throw AddonException("Empty response")
                    response.code == 404 -> throw AddonNotFound()
                    response.code == 503 && preparing -> {
                        val stated = retryAfterMs(response.header("Retry-After"), attempt)
                        if (waitedMs + stated > PREPARE_BUDGET_MS || attempt >= PREPARE_RETRIES) {
                            throw AddonUnavailable("Still preparing after ${waitedMs / 1000}s")
                        }
                        waitedMs += stated
                        stated
                    }
                    response.code >= 500 -> throw AddonUnavailable("HTTP ${response.code}")
                    response.code != 429 -> throw AddonException("HTTP ${response.code}")
                    attempt >= MAX_RETRIES -> throw AddonUnavailable("This addon is rate limiting BitChord")
                    else -> retryAfterMs(response.header("Retry-After"), attempt)
                }
            }
            if (preparing) {
                delay(wait)
            } else {
                quietUntilMs = System.currentTimeMillis() + wait
            }
            attempt++
        }
        @Suppress("UNREACHABLE_CODE")
        throw AddonException("unreachable")
    }

    /** The `notice` object of a JSON reply, when there is one and it is readable. */
    internal fun noticeIn(text: String): EclipseNotice? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        root["notice"]?.let { json.decodeFromJsonElement<EclipseNotice>(it) }
    }.getOrNull()?.takeIf { it.text.isNotEmpty() }

    private fun retryAfterMs(header: String?, attempt: Int): Long {
        val stated = header?.trim()?.toDoubleOrNull()?.times(1000)?.toLong()
        return (stated ?: (BACKOFF_BASE_MS shl attempt)).coerceIn(BACKOFF_BASE_MS, BACKOFF_CAP_MS)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun clear() {
        manifests.clear(); searches.clear(); streams.clear()
        details.clear(); rows.clear(); resolved.clear(); isrcs.clear()
        quietUntilMs = 0L
    }

    /** Makes the next lookups real requests without disturbing one already in flight. */
    fun clearCompletedTrackCalls() {
        searches.clearCompleted(); streams.clearCompleted()
        resolved.clearCompleted(); isrcs.clearCompleted()
    }

    private fun keyOf(vararg parts: String) = parts.joinToString("|") { "${it.length}:$it" }

    companion object {
        private const val TAG = "BitChord"
        const val QUALITY_KEY = "quality"

        private val HIGH_WORDS = listOf("high", "320", "normal", "standard")
        private val LOW_WORDS = listOf("low", "96", "128", "min")

        const val MANIFEST_TTL_MS = 10 * 60 * 1000L
        const val SEARCH_TTL_MS = 10 * 60 * 1000L
        const val STREAM_TTL_MS = 5 * 60 * 1000L

        /** The protocol: wait up to 30 s for a song being prepared, retry a few times, then move on. */
        const val PREPARE_BUDGET_MS = 30_000L
        private const val PREPARE_RETRIES = 6
        private const val MAX_RETRIES = 2
        private const val BACKOFF_BASE_MS = 500L
        private const val BACKOFF_CAP_MS = 30_000L

        private val USER_AGENT = "BitChord/v${com.music.bitchord.BuildConfig.VERSION_NAME}"

        /**
         * The `User-Agent` to send: [custom] when the listener set one, else
         * BitChord's. Reduced to printable ASCII, since a header value with a
         * newline or a control character is not something to put on the wire —
         * and a value that reduces to nothing falls back to the default.
         */
        internal fun userAgentFor(custom: String): String =
            custom.filter { it.code in 0x20..0x7E }.trim().ifEmpty { USER_AGENT }

        internal val json = Json {
            isLenient = true
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        private val client: OkHttpClient by lazy {
            Http.client.newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(75, TimeUnit.SECONDS)
                .build()
        }
    }
}
