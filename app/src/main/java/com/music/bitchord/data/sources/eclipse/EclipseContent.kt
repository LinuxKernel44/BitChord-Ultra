package com.music.bitchord.data.sources.eclipse

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.EclipseIds
import com.music.bitchord.data.sources.EclipseSource
import com.music.bitchord.data.sources.SourceRegistry

/**
 * Turns what an Eclipse addon says into the models the rest of the app draws:
 * search rows, Home shelves, Explore buttons and the pages behind them.
 *
 * Pure mapping — no network — so it is covered by plain unit tests.
 */
object EclipseContent {

    /** A catalogue row opens as a page of its own; its browse id carries the row's. */
    const val CATALOG = "catalog"

    /** The most a "see all" page pulls: five pages of the protocol's fixed 100. */
    const val MAX_PAGES = 5
    const val PAGE_SIZE = 100

    fun catalogId(configId: String, rowId: String) = EclipseIds.of(configId, CATALOG, rowId)

    // ── Search ────────────────────────────────────────────────────────────

    /** Albums, artists and playlists of a search answer, as rows that open their pages. */
    fun browseItems(source: EclipseSource, answer: EclipseSearchResponse): List<BrowseItem> {
        val cfg = source.configId
        val from = source.displayName
        return buildList {
            answer.artists.filter { it.id.isNotBlank() && it.name.isNotBlank() }.forEach {
                add(BrowseItem(EclipseIds.of(cfg, EclipseIds.ARTIST, it.id), it.name, from, it.artworkURL, BrowseType.ARTIST))
            }
            answer.albums.filter { it.id.isNotBlank() && it.title.isNotBlank() }.forEach {
                add(
                    BrowseItem(
                        EclipseIds.of(cfg, EclipseIds.ALBUM, it.id), it.title,
                        listOf(it.artist, it.yearText, from).filterNot { s -> s.isNullOrBlank() }.joinToString(" · "),
                        it.artworkURL, BrowseType.ALBUM,
                    ),
                )
            }
            answer.playlists.filter { it.id.isNotBlank() && it.title.isNotBlank() }.forEach {
                add(
                    BrowseItem(
                        EclipseIds.of(cfg, EclipseIds.PLAYLIST, it.id), it.title,
                        listOf(it.creator, from).filter { s -> s.isNotBlank() }.joinToString(" · "),
                        it.artworkURL, BrowseType.PLAYLIST,
                    ),
                )
            }
        }
    }

    // ── Catalogue rows ────────────────────────────────────────────────────

    /** One catalogue row as a Home shelf, or null when the addon gave it nothing. */
    fun shelf(source: EclipseSource, row: EclipseCatalog, items: List<EclipseItem>): HomeShelf? {
        val cards = items.mapNotNull { shelfItem(source, it) }
        if (cards.isEmpty()) return null
        return HomeShelf(
            title = row.name.ifBlank { row.id },
            items = cards,
            subtitle = source.displayName,
            // A track row can be opened whole; the other kinds are already cards to open.
            moreBrowseId = if (row.type.equals("track", ignoreCase = true) && items.size >= PAGE_SIZE) {
                catalogId(source.configId, row.id)
            } else {
                null
            },
        )
    }

    fun shelfItem(source: EclipseSource, item: EclipseItem): ShelfItem? {
        if (item.id.isBlank() || item.heading.isBlank()) return null
        val cfg = source.configId
        return when (item.kind) {
            "album" -> ShelfItem(
                title = item.heading,
                subtitle = listOf(item.artist, item.year.asQueryValue()).filterNot { it.isNullOrBlank() }.joinToString(" · "),
                thumbnailUrl = item.artworkURL, videoId = null,
                browseId = EclipseIds.of(cfg, EclipseIds.ALBUM, item.id),
            )
            "artist" -> ShelfItem(
                title = item.heading, subtitle = "", thumbnailUrl = item.artworkURL, videoId = null,
                browseId = EclipseIds.of(cfg, EclipseIds.ARTIST, item.id),
            )
            "playlist" -> ShelfItem(
                title = item.heading, subtitle = item.creator, thumbnailUrl = item.artworkURL, videoId = null,
                browseId = EclipseIds.of(cfg, EclipseIds.PLAYLIST, item.id),
            )
            else -> ShelfItem(
                title = item.heading, subtitle = item.artist, thumbnailUrl = item.artworkURL,
                videoId = SourceRegistry.trackKey(cfg, item.id), browseId = null,
            )
        }
    }

    /** Explore: one section per addon, one button per catalogue row. */
    fun exploreSection(source: EclipseSource, rows: List<EclipseCatalog>): MoodGenreSection? {
        if (rows.isEmpty()) return null
        return MoodGenreSection(
            title = source.displayName,
            items = rows.map { MoodGenre(title = it.name.ifBlank { it.id }, browseId = catalogId(source.configId, it.id), params = null) },
        )
    }

    // ── Pages ─────────────────────────────────────────────────────────────

    /** What a detail page needs, whichever kind of page it is. */
    data class Page(
        val title: String,
        val subtitle: String,
        val thumbnailUrl: String?,
        val description: String?,
        val songs: List<Song>,
        val sections: List<HomeShelf> = emptyList(),
    )

    fun albumPage(source: EclipseSource, browseId: String, album: EclipseAlbum) = Page(
        title = album.title,
        subtitle = listOf(album.artist, album.yearText).filterNot { it.isNullOrBlank() }.joinToString(" · "),
        thumbnailUrl = album.artworkURL,
        description = album.description.ifBlank { null },
        songs = album.tracks.filter { it.id.isNotBlank() }.map { track ->
            source.songOf(track, albumId = browseId).let {
                it.copy(
                    albumName = it.albumName ?: album.title,
                    thumbnailUrl = it.thumbnailUrl ?: album.artworkURL,
                    artist = it.artist.ifBlank { album.artist },
                )
            }
        },
    )

    fun playlistPage(source: EclipseSource, playlist: EclipsePlaylist) = Page(
        title = playlist.title,
        subtitle = playlist.creator,
        thumbnailUrl = playlist.artworkURL,
        description = playlist.description.ifBlank { null },
        songs = playlist.tracks.filter { it.id.isNotBlank() }.map { source.songOf(it) },
    )

    fun artistPage(source: EclipseSource, browseId: String, artist: EclipseArtist): Page {
        val cfg = source.configId
        val albums = artist.albums.filter { it.id.isNotBlank() && it.title.isNotBlank() }.map {
            ShelfItem(
                title = it.title, subtitle = it.yearText.orEmpty(), thumbnailUrl = it.artworkURL, videoId = null,
                browseId = EclipseIds.of(cfg, EclipseIds.ALBUM, it.id),
            )
        }
        return Page(
            title = artist.name,
            subtitle = artist.genres.joinToString(" · "),
            thumbnailUrl = artist.artworkURL,
            description = artist.bio.ifBlank { null },
            songs = artist.topTracks.filter { it.id.isNotBlank() }.map { source.songOf(it, artistId = browseId) },
            sections = if (albums.isEmpty()) emptyList() else listOf(HomeShelf(title = "Albums", items = albums)),
        )
    }

    /** A whole catalogue row, as tracks (for a track row) or as the cards of a shelf. */
    fun catalogPage(source: EclipseSource, row: EclipseCatalog?, items: List<EclipseItem>) = Page(
        title = row?.name.orEmpty(),
        subtitle = source.displayName,
        thumbnailUrl = items.firstNotNullOfOrNull { it.artworkURL },
        description = null,
        songs = items.filter { it.kind == "track" && it.id.isNotBlank() }.map { source.songOf(it.asTrack()) },
    )
}
