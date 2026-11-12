package com.music.bitchord.data.sources.eclipse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire shapes of the Eclipse Music addon protocol (https://eclipsemusic.app/docs).
 *
 * Everything is optional with a default and unknown keys are ignored, because
 * the protocol says an addon "returns only what it has" and extends freely. The
 * `/stream` answer is not modelled here: it is identical to the one the existing
 * addon protocol uses, so [com.music.bitchord.data.sources.addon.AddonStream]
 * reads it.
 */

// ── Manifest ─────────────────────────────────────────────────────────────

@Serializable
data class EclipseManifest(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("icon") val icon: String? = null,
    @SerialName("types") val types: List<String> = emptyList(),
    /** `music` (default), `audiobook` or `podcast`. Carried, not acted on. */
    @SerialName("contentType") val contentType: String = "",
    @SerialName("resources") val resources: List<String> = emptyList(),
    @SerialName("settings") val settings: List<EclipseSetting> = emptyList(),
    @SerialName("catalogs") val catalogs: List<EclipseCatalog> = emptyList(),
    @SerialName("notice") val notice: EclipseNotice? = null,
) {
    fun declares(resource: String): Boolean =
        resources.any { it.equals(resource, ignoreCase = true) }

    /** An addon that declares nothing is given the benefit of the doubt, as `search` is the one requirement. */
    val isPlayable: Boolean get() = resources.isEmpty() || declares("search")

    val displayName: String get() = name.ifBlank { id }

    /** Whether the addon exposes `/resolve-isrc`. */
    val resolvesIsrc: Boolean get() = declares("isrc")

    /** Whether the addon exposes `/resolve`. */
    val resolves: Boolean get() = declares("resolve")

    /** Whether the addon wants to show notices (and so the listener can switch them off). */
    val sendsNotices: Boolean get() = declares("notifications")

    /**
     * The `/album`, `/artist`, `/playlist` endpoints. The docs gate them behind
     * `catalog` — the same word as the catalogue rows — so either reading counts.
     */
    val hasDetailPages: Boolean get() = declares("catalog") || declares("album") || declares("artist") || declares("playlist")

    /** Catalogue rows the addon offers for Home and Explore. */
    val rows: List<EclipseCatalog> get() = if (declares("catalog") || catalogs.isNotEmpty()) catalogs.filter { it.id.isNotBlank() } else emptyList()
}

/** One catalogue row: `type` is `track`, `album`, `artist` or `playlist`. */
@Serializable
data class EclipseCatalog(
    @SerialName("id") val id: String = "",
    @SerialName("type") val type: String = "track",
    @SerialName("name") val name: String = "",
)

/**
 * One field of the settings form the host draws from the manifest.
 *
 * [type] is `select`, `toggle`, `text` or `number`. [default] is a bare JSON
 * scalar, and what goes on the wire is always its string form (`"true"` for a
 * toggle).
 */
@Serializable
data class EclipseSetting(
    @SerialName("key") val key: String = "",
    @SerialName("label") val label: String = "",
    @SerialName("help") val help: String = "",
    @SerialName("type") val type: String = "text",
    @SerialName("default") val default: JsonElement? = null,
    @SerialName("options") val options: List<EclipseOption> = emptyList(),
    @SerialName("perNetwork") val perNetwork: Boolean = false,
    @SerialName("maxLength") val maxLength: Int? = null,
    @SerialName("placeholder") val placeholder: String = "",
    @SerialName("min") val min: Double? = null,
    @SerialName("max") val max: Double? = null,
    @SerialName("step") val step: Double? = null,
) {
    val defaultValue: String? get() = default.asQueryValue()

    val kind: Kind
        get() = when (type.lowercase()) {
            "select" -> if (options.isNotEmpty()) Kind.SELECT else Kind.TEXT
            "toggle", "boolean", "switch" -> Kind.TOGGLE
            "number" -> Kind.NUMBER
            else -> Kind.TEXT
        }

    val title: String get() = label.ifBlank { key }

    enum class Kind { SELECT, TOGGLE, TEXT, NUMBER }
}

@Serializable
data class EclipseOption(
    @SerialName("value") val value: JsonElement? = null,
    @SerialName("label") val label: JsonElement? = null,
) {
    val stringValue: String? get() = value.asQueryValue()
    val labelText: String? get() = label.asQueryValue() ?: stringValue
}

// ── Notices ──────────────────────────────────────────────────────────────

/**
 * Something an addon wants the listener to see. May ride on any JSON reply, at
 * any HTTP status. [id] lets a later notice replace an earlier one; [progress]
 * is 0–1 and [duration] 2–10 seconds.
 */
@Serializable
data class EclipseNotice(
    @SerialName("message") val message: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("level") val level: String = "info",
    @SerialName("id") val id: String? = null,
    @SerialName("progress") val progress: Double? = null,
    @SerialName("duration") val duration: Double? = null,
) {
    val severity: Level
        get() = when (level.lowercase()) {
            "success" -> Level.SUCCESS
            "warning", "warn" -> Level.WARNING
            "error" -> Level.ERROR
            else -> Level.INFO
        }

    /** Display seconds: the addon's, clamped to 2–10, else 4 (6 for warnings and errors). */
    val displaySeconds: Double
        get() = duration?.coerceIn(2.0, 10.0)
            ?: if (severity == Level.WARNING || severity == Level.ERROR) 6.0 else 4.0

    /** Plain text, one line, within the protocol's 120-character limit. */
    val text: String get() = message.replace(Regex("\\s+"), " ").trim().take(120)

    val heading: String get() = title.replace(Regex("\\s+"), " ").trim().take(40)

    enum class Level { INFO, SUCCESS, WARNING, ERROR }
}

// ── Content ──────────────────────────────────────────────────────────────

/** One search answer. Every array is optional. */
@Serializable
data class EclipseSearchResponse(
    @SerialName("tracks") val tracks: List<EclipseTrack> = emptyList(),
    @SerialName("albums") val albums: List<EclipseAlbum> = emptyList(),
    @SerialName("artists") val artists: List<EclipseArtist> = emptyList(),
    @SerialName("playlists") val playlists: List<EclipsePlaylist> = emptyList(),
)

@Serializable
data class EclipseTrack(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("artist") val artist: String = "",
    @SerialName("album") val album: String = "",
    /** Seconds, in search and detail rows. */
    @SerialName("duration") val duration: Double? = null,
    /** Milliseconds, in catalogue and resolve rows. */
    @SerialName("durationMs") val durationMs: Double? = null,
    @SerialName("artworkURL") val artworkURL: String? = null,
    @SerialName("isrc") val isrc: String? = null,
    @SerialName("format") val format: String = "",
    @SerialName("streamURL") val streamURL: String? = null,
    @SerialName("explicit") val explicit: Boolean? = null,
    @SerialName("hiRes") val hiRes: Boolean? = null,
) {
    val durationSec: Int?
        get() = duration?.takeIf { it > 0 }?.toInt()
            ?: durationMs?.takeIf { it > 0 }?.let { (it / 1000).toInt() }

    val artwork: String? get() = artworkURL?.ifBlank { null }

    val cleanIsrc: String? get() = isrc?.trim()?.ifBlank { null }
}

@Serializable
data class EclipseAlbum(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("artist") val artist: String = "",
    @SerialName("artworkURL") val artworkURL: String? = null,
    @SerialName("trackCount") val trackCount: Int? = null,
    /** A string or a number, both accepted by the protocol. */
    @SerialName("year") val year: JsonElement? = null,
    @SerialName("description") val description: String = "",
    @SerialName("tracks") val tracks: List<EclipseTrack> = emptyList(),
) {
    val yearText: String? get() = year.asQueryValue()
}

@Serializable
data class EclipseArtist(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("artworkURL") val artworkURL: String? = null,
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("bio") val bio: String = "",
    @SerialName("topTracks") val topTracks: List<EclipseTrack> = emptyList(),
    @SerialName("albums") val albums: List<EclipseAlbum> = emptyList(),
)

@Serializable
data class EclipsePlaylist(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("artworkURL") val artworkURL: String? = null,
    @SerialName("creator") val creator: String = "",
    @SerialName("trackCount") val trackCount: Int? = null,
    @SerialName("tracks") val tracks: List<EclipseTrack> = emptyList(),
)

/**
 * An item of a catalogue row or a `/resolve` answer. `type` says which of the
 * four kinds it is; the fields overlap, so one class carries all of them.
 */
@Serializable
data class EclipseItem(
    @SerialName("id") val id: String = "",
    @SerialName("type") val type: String = "track",
    @SerialName("title") val title: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("artist") val artist: String = "",
    @SerialName("album") val album: String = "",
    @SerialName("isrc") val isrc: String? = null,
    @SerialName("artworkURL") val artworkURL: String? = null,
    @SerialName("durationMs") val durationMs: Double? = null,
    @SerialName("duration") val duration: Double? = null,
    @SerialName("explicit") val explicit: Boolean? = null,
    @SerialName("hiRes") val hiRes: Boolean? = null,
    @SerialName("year") val year: JsonElement? = null,
    @SerialName("trackCount") val trackCount: Int? = null,
    @SerialName("creator") val creator: String = "",
) {
    val kind: String get() = type.lowercase().ifBlank { "track" }

    /** Artists carry a `name`, everything else a `title`. */
    val heading: String get() = title.ifBlank { name }

    fun asTrack() = EclipseTrack(
        id = id, title = heading, artist = artist, album = album,
        duration = duration, durationMs = durationMs, artworkURL = artworkURL,
        isrc = isrc, explicit = explicit, hiRes = hiRes,
    )
}

@Serializable
data class EclipseCatalogResponse(
    @SerialName("items") val items: List<EclipseItem> = emptyList(),
)

/** `/resolve-isrc` answers `{"trackId": …}` or `{"id": …}`; null or 404 means not held. */
@Serializable
data class EclipseIsrcResponse(
    @SerialName("trackId") val trackId: JsonElement? = null,
    @SerialName("id") val id: JsonElement? = null,
) {
    val resolved: String? get() = trackId.asQueryValue() ?: id.asQueryValue()
}

/** `/resolve` answers `{"item": {…}}`, or `{"item": null}` for a miss. */
@Serializable
data class EclipseResolveResponse(
    @SerialName("item") val item: EclipseItem? = null,
)

/** A JSON scalar as the string that would go in a query parameter; null for null, absent or composite. */
internal fun JsonElement?.asQueryValue(): String? {
    if (this == null || this is JsonNull) return null
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.content.ifBlank { null }
}
