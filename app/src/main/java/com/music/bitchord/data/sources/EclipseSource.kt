package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonException
import com.music.bitchord.data.sources.addon.AddonNotFound
import com.music.bitchord.data.sources.addon.AddonStream
import com.music.bitchord.data.sources.addon.AddonUnavailable
import com.music.bitchord.data.sources.eclipse.EclipseAlbum
import com.music.bitchord.data.sources.eclipse.EclipseArtist
import com.music.bitchord.data.sources.eclipse.EclipseClient
import com.music.bitchord.data.sources.eclipse.EclipseItem
import com.music.bitchord.data.sources.eclipse.EclipseManifest
import com.music.bitchord.data.sources.eclipse.EclipseNotice
import com.music.bitchord.data.sources.eclipse.EclipseNotices
import com.music.bitchord.data.sources.eclipse.EclipsePlaylist
import com.music.bitchord.data.sources.eclipse.EclipseSearchResponse
import com.music.bitchord.data.sources.eclipse.EclipseTrack
import com.music.bitchord.playback.StreamContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale

/**
 * A [MusicSource] backed by one Eclipse Music addon (https://eclipsemusic.app/docs).
 *
 * Playback side, in the order the protocol describes for default playback:
 * `/resolve-isrc` when the recording's ISRC is known, `/resolve` for an exact
 * title-and-artist match, and only for an addon that offers neither, a search
 * ranked by [TrackMatcher]. Then `/stream`. Content side: the addon's own
 * albums, artists, playlists and catalogue rows, which the UI shows next to
 * YouTube Music's.
 *
 * Encrypted streams are skipped (no DRM here), HLS and DASH are passed to the
 * player with their transport declared, and a notice the addon sends goes to
 * [EclipseNotices].
 */
class EclipseSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = config.kind
    override val displayName: String get() = config.displayName

    private val live: SourceConfig get() = SourceRegistry.config(config.id) ?: config

    @Volatile
    private var lastManifest: EclipseManifest? = null

    private val client = EclipseClient(
        rawBaseUrl = config.baseUrl,
        settings = ::currentSettings,
        notify = ::onNotice,
    )

    /** Search rows by the addon's own id: the duration and `streamURL` a bare id can't recover. */
    private val rows = Collections.synchronizedMap(
        object : LinkedHashMap<String, EclipseTrack>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, EclipseTrack>) = size > MAX_ROWS
        },
    )

    // ── Settings and notices ──────────────────────────────────────────────

    /** The form values in force: Wi-Fi/default ones, with the cellular overrides on a metered connection. */
    private fun currentSettings(): Map<String, String> {
        val stored = live
        val cellular = AppSettings.meteredConnection.value == true
        return if (cellular) stored.eclipseSettings + stored.eclipseCellularSettings else stored.eclipseSettings
    }

    private fun onNotice(notice: EclipseNotice, fromManifest: Boolean) {
        // The listener's switch exists only when the addon declares `notifications`.
        val switchable = lastManifest?.sendsNotices == true
        val enabled = !switchable || live.eclipseNotices
        EclipseNotices.post(displayName, notice, enabled, fromManifest)
    }

    /** The manifest, cached by the client; also what the settings sheet and Home draw from. */
    suspend fun manifest(): Result<EclipseManifest> =
        client.manifest().onSuccess { lastManifest = it }

    // ── Health ────────────────────────────────────────────────────────────

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank()) return@withContext SourceHealth.Rejected("An addon URL is required")
        manifest().fold(
            onSuccess = { m ->
                SourceHealth.Ok(
                    listOfNotNull(
                        m.displayName.takeIf { it.isNotBlank() },
                        m.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                    ).joinToString(" ").ifBlank { null },
                )
            },
            onFailure = { failure ->
                val reason = failure.message ?: "Could not reach the addon"
                if (failure is AddonException) {
                    SourceHealth.Rejected(reason)
                } else {
                    TrackLog.w(TAG, "eclipse addon unreachable for ${config.displayName}: $reason")
                    SourceHealth.Unreachable(reason)
                }
            },
        )
    }

    // ── Search ────────────────────────────────────────────────────────────

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val answer = client.search(query).getOrElse { failure ->
            TrackLog.w(TAG, "${config.displayName}: search failed — ${failure.message}")
            return@withContext emptyList()
        }
        answer.tracks.asSequence()
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .take(limit)
            .map(::songOf)
            .toList()
    }

    /** Everything a search returned — tracks, albums, artists, playlists — for the search screen. */
    suspend fun searchEverything(query: String): Result<EclipseSearchResponse> =
        withContext(Dispatchers.IO) { client.search(query) }

    // ── Stream ────────────────────────────────────────────────────────────

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            val tier = request.tier
            val result = client.stream(trackId, tier)
            val answer = result.getOrNull()
            if (answer == null) {
                val failure = result.exceptionOrNull()
                if (failure !is AddonNotFound) {
                    TrackLog.w(TAG, "${config.displayName}: stream failed for $trackId — ${failure?.message}")
                }
                return@withContext fromRow(trackId, tier)
            }
            val url = answer.url.ifBlank { null } ?: return@withContext fromRow(trackId, tier)
            openable(url, answer, trackId, tier)
        }

    /** The row's own `streamURL`, when `/stream` had nothing — the protocol lets an addon skip `/stream` for a row. */
    private fun fromRow(trackId: String, tier: String): SourceStream? {
        val row = rows[trackId] ?: return null
        val url = row.streamURL?.ifBlank { null } ?: return null
        return openable(url, AddonStream(url = url, format = row.format), trackId, tier)
    }

    private fun openable(url: String, answer: AddonStream, trackId: String, tier: String): SourceStream? {
        if (ModuleSource.malformed(url)) {
            TrackLog.w(TAG, "${config.displayName}: malformed URL for $trackId; skipping it")
            return null
        }
        if (answer.isEncrypted) {
            TrackLog.w(TAG, "${config.displayName}: $trackId is encrypted and BitChord has no key — passing")
            return null
        }
        val format = AddonStreamFormat.of(answer, url, tier)
        if (ModuleSource.unplayable(format, atmosAllowed = com.music.bitchord.data.sources.DeviceCodecs.atmosWanted)) {
            TrackLog.w(TAG, "${config.displayName}: ${format.summary} cannot be played on this device — passing")
            return null
        }
        // HLS and DASH say what they are up front, so the player builds the
        // right media source instead of sniffing a playlist as a file.
        answer.transport?.let { StreamContainer.declare(url, it) }
        return SourceStream(url = url, format = format, durationSec = rows[trackId]?.durationSec)
    }

    // ── Exact matching ────────────────────────────────────────────────────

    /** What [exact] found. */
    sealed interface Exact {
        /** This addon has no resolve endpoint: fall back to searching it. */
        data object Unsupported : Exact

        /** The addon answered that it does not hold the recording. Move on to the next source. */
        data object Missing : Exact

        data class Found(val stream: SourceStream) : Exact
    }

    /**
     * The recording [target] names, matched exactly by the addon rather than
     * guessed at by [TrackMatcher]: `/resolve-isrc` when an ISRC is known and
     * the addon offers it, then `/resolve`. An id the addon returns is an exact
     * match by contract, so it is streamed without further scoring.
     */
    suspend fun exact(target: TrackMatcher.Target, request: StreamRequest): Exact = withContext(Dispatchers.IO) {
        val m = manifest().getOrNull() ?: return@withContext Exact.Unsupported
        val isrc = target.isrc?.takeIf { it.isNotBlank() }
        var asked = false

        if (isrc != null && m.resolvesIsrc) {
            asked = true
            val id = client.resolveIsrc(isrc).getOrNull()
            if (id != null) streamOf(id, request)?.let { return@withContext Exact.Found(it) }
        }
        if (m.resolves) {
            asked = true
            val item = client.resolve(
                title = target.title,
                artist = target.artist,
                durationMs = target.durationSec?.let { it * 1000L },
                isrc = isrc,
            ).getOrNull()
            if (item != null) streamOf(item.id, request)?.let { return@withContext Exact.Found(it) }
        }
        if (asked) Exact.Missing else Exact.Unsupported
    }

    private suspend fun streamOf(trackId: String, request: StreamRequest) =
        stream(trackId, request)?.copy(sourceConfigId = configId)

    // ── Content: albums, artists, playlists, catalogue rows ───────────────

    suspend fun album(id: String): Result<EclipseAlbum> = withContext(Dispatchers.IO) { client.album(id) }
    suspend fun artist(id: String): Result<EclipseArtist> = withContext(Dispatchers.IO) { client.artist(id) }
    suspend fun playlist(id: String): Result<EclipsePlaylist> = withContext(Dispatchers.IO) { client.playlist(id) }
    suspend fun catalog(id: String, skip: Int = 0): Result<List<EclipseItem>> =
        withContext(Dispatchers.IO) { client.catalog(id, skip) }

    /** A track as the rest of the app knows it. [albumId] and [artistId] are browse ids, when the page that listed it knew them. */
    fun songOf(track: EclipseTrack, albumId: String? = null, artistId: String? = null): Song {
        rows[track.id] = track
        val tier = ModuleSource.qualityTier(
            "${track.format} ${if (track.hiRes == true) "HI_RES_LOSSLESS" else ""}",
        )
        return Song(
            videoId = SourceRegistry.trackKey(config.id, track.id),
            title = track.title,
            artist = track.artist,
            thumbnailUrl = track.artwork,
            durationText = track.durationSec?.let { "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}" },
            artistId = artistId,
            albumId = albumId,
            albumName = track.album.ifBlank { null },
            sourceQuality = tier,
            sourceHiRes = track.hiRes == true,
            isExplicit = track.explicit,
            isrc = track.cleanIsrc,
        )
    }

    private val StreamRequest.tier: String
        get() = when (this) {
            is StreamRequest.Lossless -> AddonClient.TIER_LOSSLESS
            is StreamRequest.Best -> AddonClient.TIER_HIGH
            is StreamRequest.Capped ->
                if (maxKbps <= ModuleSource.LOW_CEILING_KBPS) AddonClient.TIER_LOW else AddonClient.TIER_HIGH
        }

    fun release() {
        rows.clear()
        client.clear()
    }

    fun clearCompletedTrackCalls() = client.clearCompletedTrackCalls()

    private companion object {
        const val TAG = "BitChord"
        const val MAX_ROWS = 256
    }
}

/**
 * Browse ids for an Eclipse addon's pages, in the form `eclipse:<configId>:<type>:<id>`.
 *
 * The addon's own id is last and may itself contain colons, so parsing splits
 * into at most four parts. The config id is a UUID and never does.
 */
object EclipseIds {
    const val PREFIX = "eclipse:"

    const val ALBUM = "album"
    const val ARTIST = "artist"
    const val PLAYLIST = "playlist"

    fun of(configId: String, type: String, id: String) = "$PREFIX$configId:$type:$id"

    fun isEclipse(browseId: String) = browseId.startsWith(PREFIX)

    data class Ref(val configId: String, val type: String, val id: String)

    fun parse(browseId: String): Ref? {
        if (!isEclipse(browseId)) return null
        val parts = browseId.removePrefix(PREFIX).split(":", limit = 3)
        if (parts.size != 3 || parts.any { it.isBlank() }) return null
        return Ref(parts[0], parts[1], parts[2])
    }
}
