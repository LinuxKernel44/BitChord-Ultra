package com.music.bitchord

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.data.sources.EclipseIds
import com.music.bitchord.data.sources.EclipseSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.sources.eclipse.EclipseClient
import com.music.bitchord.data.sources.eclipse.EclipseContent
import com.music.bitchord.data.sources.eclipse.EclipseManifest
import com.music.bitchord.data.sources.eclipse.EclipseNotice
import com.music.bitchord.data.sources.eclipse.EclipseNotices
import com.music.bitchord.data.sources.eclipse.EclipseSearchResponse
import com.music.bitchord.playback.StreamContainer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Eclipse addon protocol against a real HTTP server: what goes on the wire
 * (settings as query parameters, `skip`, ISRC lookups) and what is done with
 * what comes back (exact matching, notices, HLS, encrypted streams).
 */
class EclipseSourceTest {

    private lateinit var server: MockWebServer
    private val routes = mutableMapOf<String, MockResponse>()
    private val seen = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                return routes[request.requestUrl?.encodedPath.orEmpty()] ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        DeviceCodecs.forced = true
        AppSettings.meteredConnection.value = false
    }

    @After
    fun tearDown() {
        server.shutdown()
        DeviceCodecs.forced = null
        AppSettings.meteredConnection.value = null
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun manifest(resources: String = """"search","stream"""", extra: String = "") = json(
        """{"id":"com.test.eclipse","name":"Test Eclipse","version":"1.2.0","resources":[$resources]$extra}""",
    )

    private fun config(
        settings: Map<String, String> = emptyMap(),
        cellular: Map<String, String> = emptyMap(),
    ) = SourceConfig(
        kind = SourceKind.ECLIPSE,
        baseUrl = server.url("/").toString().trimEnd('/'),
        eclipseSettings = settings,
        eclipseCellularSettings = cellular,
    )

    private fun source(config: SourceConfig = config()) = EclipseSource(config)

    private fun requestsFor(path: String) =
        synchronized(seen) { seen.filter { it.requestUrl?.encodedPath == path } }

    // ── Manifest ──────────────────────────────────────────────────────────

    @Test
    fun `a manifest with search is healthy and reports its name and version`() = runBlocking {
        routes["/manifest.json"] = manifest()
        val health = source().health()
        assertTrue(health is SourceHealth.Ok)
        assertEquals("Test Eclipse v1.2.0", (health as SourceHealth.Ok).detail)
    }

    @Test
    fun `a manifest that cannot search is rejected with a reason`() = runBlocking {
        routes["/manifest.json"] = manifest(""""catalog"""")
        val health = source().health()
        assertTrue(health is SourceHealth.Rejected)
    }

    @Test
    fun `an unreachable address is unreachable rather than rejected`() = runBlocking {
        routes["/manifest.json"] = MockResponse().setResponseCode(503)
        assertTrue(source().health() is SourceHealth.Unreachable)
    }

    @Test
    fun `manifest parsing reads settings catalogs and resource flags`() {
        val parsed = EclipseClient.json.decodeFromString<EclipseManifest>(
            """{"id":"a.b","name":"X","version":"1","resources":["search","stream","isrc","resolve","catalog","settings","notifications"],
               "catalogs":[{"id":"hot","type":"track","name":"Hot"}],
               "settings":[{"key":"quality","label":"Quality","type":"select","default":"high","options":[{"value":"high","label":"High"},{"value":"lossless","label":"Lossless"}],"perNetwork":true},
                           {"key":"explicit","label":"Explicit","type":"toggle","default":true},
                           {"key":"limit","type":"number","default":10,"min":1,"max":50}]}""",
        )
        assertTrue(parsed.resolvesIsrc && parsed.resolves && parsed.sendsNotices)
        assertEquals(listOf("hot"), parsed.rows.map { it.id })
        assertEquals("high", parsed.settings[0].defaultValue)
        assertTrue(parsed.settings[0].perNetwork)
        assertEquals("true", parsed.settings[1].defaultValue)
        assertEquals("10", parsed.settings[2].defaultValue)
    }

    // ── Settings on the wire ──────────────────────────────────────────────

    @Test
    fun `defaults are sent until the listener changes a value`() = runBlocking {
        routes["/manifest.json"] = manifest(
            extra = ""","settings":[{"key":"region","type":"text","default":"US"},{"key":"hq","type":"toggle","default":false}]""",
        )
        routes["/search"] = json("""{"tracks":[]}""")
        source(config(settings = mapOf("region" to "FR"))).search("x")
        val url = requestsFor("/search").single().requestUrl!!
        assertEquals("FR", url.queryParameter("region"))
        assertEquals("false", url.queryParameter("hq"))
        assertEquals("x", url.queryParameter("q"))
    }

    @Test
    fun `a perNetwork value follows the connection`() = runBlocking {
        routes["/manifest.json"] = manifest(
            extra = ""","settings":[{"key":"quality","type":"select","default":"high","perNetwork":true,
                "options":[{"value":"high","label":"High"},{"value":"low","label":"Low"}]}]""",
        )
        routes["/search"] = json("""{"tracks":[]}""")
        val cfg = config(settings = mapOf("quality" to "high"), cellular = mapOf("quality" to "low"))

        AppSettings.meteredConnection.value = true
        source(cfg).search("on mobile")
        assertEquals("low", requestsFor("/search").last().requestUrl!!.queryParameter("quality"))

        AppSettings.meteredConnection.value = false
        source(cfg).search("on wifi")
        assertEquals("high", requestsFor("/search").last().requestUrl!!.queryParameter("quality"))
    }

    // ── Search ────────────────────────────────────────────────────────────

    @Test
    fun `search rows carry the isrc and a track key into Song`() = runBlocking {
        routes["/manifest.json"] = manifest()
        routes["/search"] = json(
            """{"tracks":[{"id":"t1","title":"Song","artist":"Band","album":"LP","duration":185,"isrc":"USUG12400123","explicit":true}]}""",
        )
        val src = source()
        val song = src.search("song").single()
        assertEquals("Song", song.title)
        assertEquals("3:05", song.durationText)
        assertEquals("USUG12400123", song.isrc)
        assertEquals(true, song.isExplicit)
        assertEquals(src.configId to "t1", com.music.bitchord.data.sources.SourceRegistry.parseTrackKey(song.videoId))
    }

    @Test
    fun `search returns albums artists and playlists as browse rows`() = runBlocking {
        routes["/manifest.json"] = manifest()
        routes["/search"] = json(
            """{"tracks":[],"albums":[{"id":"al:1","title":"LP","artist":"Band","year":2024}],
               "artists":[{"id":"ar1","name":"Band","genres":["Rock"]}],
               "playlists":[{"id":"p1","title":"Mix","creator":"me"}]}""",
        )
        val src = source()
        val items = EclipseContent.browseItems(src, src.searchEverything("x").getOrThrow())
        assertEquals(3, items.size)
        assertTrue(items.any { it.browseId == EclipseIds.of(src.configId, "album", "al:1") })
        assertTrue(items.first { it.title == "LP" }.subtitle.contains("2024"))
    }

    // ── Exact matching ────────────────────────────────────────────────────

    @Test
    fun `an isrc is resolved to the addon's id and streamed`() = runBlocking {
        routes["/manifest.json"] = manifest(""""search","stream","isrc"""")
        routes["/resolve-isrc"] = json("""{"trackId":"abc"}""")
        routes["/stream/abc"] = json("""{"url":"https://cdn.example.com/a.flac","format":"flac"}""")
        val result = source().exact(TrackMatcher.Target("Song", "Band", isrc = "USUG12400123"), StreamRequest.Lossless)
        assertTrue(result is EclipseSource.Exact.Found)
        assertEquals("USUG12400123", requestsFor("/resolve-isrc").single().requestUrl!!.queryParameter("isrc"))
        assertEquals("https://cdn.example.com/a.flac", (result as EclipseSource.Exact.Found).stream.url)
    }

    @Test
    fun `resolve is asked with title artist and duration in milliseconds`() = runBlocking {
        routes["/manifest.json"] = manifest(""""search","stream","resolve"""")
        routes["/resolve"] = json("""{"item":{"id":"r9","type":"track","title":"Song","artist":"Band"}}""")
        routes["/stream/r9"] = json("""{"url":"https://cdn.example.com/r.mp3"}""")
        val result = source().exact(TrackMatcher.Target("Song", "Band", durationSec = 181), StreamRequest.Best)
        assertTrue(result is EclipseSource.Exact.Found)
        val url = requestsFor("/resolve").single().requestUrl!!
        assertEquals("Song", url.queryParameter("title"))
        assertEquals("Band", url.queryParameter("artist"))
        assertEquals("181000", url.queryParameter("durationMs"))
    }

    @Test
    fun `a null item from resolve is a miss and not an error`() = runBlocking {
        routes["/manifest.json"] = manifest(""""search","stream","resolve"""")
        routes["/resolve"] = json("""{"item":null}""")
        val result = source().exact(TrackMatcher.Target("Song", "Band"), StreamRequest.Best)
        assertTrue(result is EclipseSource.Exact.Missing)
        assertTrue("no search fallback after a resolve miss", requestsFor("/search").isEmpty())
    }

    @Test
    fun `an addon with no resolve endpoint falls back to search matching`() = runBlocking {
        routes["/manifest.json"] = manifest()
        val result = source().exact(TrackMatcher.Target("Song", "Band", isrc = "X"), StreamRequest.Best)
        assertTrue(result is EclipseSource.Exact.Unsupported)
    }

    @Test
    fun `a 404 from resolve-isrc is not held rather than a fault`() = runBlocking {
        routes["/manifest.json"] = manifest(""""search","stream","isrc"""")
        val result = source().exact(TrackMatcher.Target("Song", "Band", isrc = "X"), StreamRequest.Best)
        assertTrue(result is EclipseSource.Exact.Missing)
    }

    // ── Stream ────────────────────────────────────────────────────────────

    @Test
    fun `an hls stream is declared so the player builds the right media source`() = runBlocking {
        routes["/manifest.json"] = manifest()
        routes["/stream/h"] = json(
            """{"url":"https://cdn.example.com/play/h","manifest":"hls","codec":"flac","container":"fmp4","encrypted":false}""",
        )
        val stream = source().stream("h", StreamRequest.Lossless)
        assertNotNull(stream)
        assertEquals("application/x-mpegURL", StreamContainer.manifestMimeOf("https://cdn.example.com/play/h"))
        assertEquals("flac", stream!!.format.codec)
    }

    @Test
    fun `an encrypted stream is skipped`() = runBlocking {
        routes["/manifest.json"] = manifest()
        routes["/stream/e"] = json("""{"url":"https://cdn.example.com/e.m4a","encrypted":true}""")
        assertNull(source().stream("e", StreamRequest.Best))
    }

    @Test
    fun `a stream missing at the addon falls back to the search row's own url`() = runBlocking {
        routes["/manifest.json"] = manifest()
        routes["/search"] = json("""{"tracks":[{"id":"s1","title":"T","artist":"A","streamURL":"https://cdn.example.com/direct.mp3"}]}""")
        val src = source()
        src.search("t")
        assertEquals("https://cdn.example.com/direct.mp3", src.stream("s1", StreamRequest.Best)?.url)
    }

    @Test
    fun `a preparing 503 is retried then played`() = runBlocking {
        routes["/manifest.json"] = manifest()
        var calls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                return when (request.requestUrl?.encodedPath) {
                    "/manifest.json" -> manifest()
                    "/stream/p" -> if (calls++ < 1) {
                        MockResponse().setResponseCode(503).setHeader("Retry-After", "1")
                            .setBody("""{"notice":{"id":"prep-p","level":"info","message":"Preparing the song"}}""")
                    } else {
                        json("""{"url":"https://cdn.example.com/p.mp3"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val stream = source().stream("p", StreamRequest.Best)
        assertEquals("https://cdn.example.com/p.mp3", stream?.url)
        assertEquals(2, requestsFor("/stream/p").size)
        assertEquals("Preparing the song", EclipseNotices.current.value?.notice?.message)
    }

    // ── Catalogs and pages ────────────────────────────────────────────────

    @Test
    fun `a catalogue row is requested with skip`() = runBlocking {
        routes["/manifest.json"] = manifest(""""search","stream","catalog"""")
        routes["/catalog/hot"] = json(
            """{"items":[{"id":"1","type":"track","title":"A","artist":"B","isrc":"I","durationMs":181000,"hiRes":true}]}""",
        )
        val items = source().catalog("hot", skip = 100).getOrThrow()
        assertEquals("100", requestsFor("/catalog/hot").single().requestUrl!!.queryParameter("skip"))
        assertEquals(181, items.single().asTrack().durationSec)
    }

    @Test
    fun `catalogue items become shelf cards of the right kind`() {
        val src = source()
        val items = EclipseClient.json.decodeFromString<com.music.bitchord.data.sources.eclipse.EclipseCatalogResponse>(
            """{"items":[{"id":"t","type":"track","title":"Song","artist":"A"},
                         {"id":"a","type":"album","title":"LP","artist":"A","year":"2020"},
                         {"id":"r","type":"artist","name":"Band"},
                         {"id":"p","type":"playlist","title":"Mix"}]}""",
        ).items
        val shelf = EclipseContent.shelf(src, com.music.bitchord.data.sources.eclipse.EclipseCatalog("row", "track", "Row"), items)!!
        assertEquals(4, shelf.items.size)
        assertNotNull(shelf.items[0].videoId)
        assertEquals(EclipseIds.of(src.configId, "album", "a"), shelf.items[1].browseId)
        assertEquals(EclipseIds.of(src.configId, "artist", "r"), shelf.items[2].browseId)
        assertEquals("Band", shelf.items[2].title)
    }

    @Test
    fun `an album page lists its tracks with the album id and artwork`() = runBlocking {
        routes["/manifest.json"] = manifest()
        routes["/album/al1"] = json(
            """{"id":"al1","title":"LP","artist":"Band","artworkURL":"https://i/x.png","year":"2024",
               "tracks":[{"id":"t1","title":"One","duration":100},{"id":"t2","title":"Two","artist":"Guest"}]}""",
        )
        val src = source()
        val id = EclipseIds.of(src.configId, "album", "al1")
        val page = EclipseContent.albumPage(src, id, src.album("al1").getOrThrow())
        assertEquals(2, page.songs.size)
        assertEquals("Band", page.songs[0].artist)
        assertEquals("Guest", page.songs[1].artist)
        assertEquals(id, page.songs[0].albumId)
        assertEquals("https://i/x.png", page.songs[0].thumbnailUrl)
    }

    // ── Ids and notices ───────────────────────────────────────────────────

    @Test
    fun `browse ids round-trip even when the addon's id contains colons`() {
        val id = EclipseIds.of("cfg-1", "album", "spotify:album:123")
        assertEquals(EclipseIds.Ref("cfg-1", "album", "spotify:album:123"), EclipseIds.parse(id))
        assertNull(EclipseIds.parse("UC123"))
        assertNull(EclipseIds.parse("eclipse:only:two"))
    }

    @Test
    fun `a notice replaces the one on show when it has the same id`() {
        EclipseNotices.post("A", EclipseNotice(message = "first", id = "n1"), enabled = true)
        val first = EclipseNotices.current.value!!
        EclipseNotices.post("A", EclipseNotice(message = "second", id = "n1", progress = 0.5), enabled = true)
        val second = EclipseNotices.current.value!!
        assertEquals("second", second.notice.message)
        assertTrue(second.serial > first.serial)
    }

    @Test
    fun `identical notices inside a minute are shown once and a disabled addon shows none`() {
        EclipseNotices.dismiss(EclipseNotices.current.value ?: return)
        EclipseNotices.post("Dedupe", EclipseNotice(message = "slow down"), enabled = true)
        val shown = EclipseNotices.current.value!!
        EclipseNotices.post("Dedupe", EclipseNotice(message = "slow down"), enabled = true)
        assertEquals(shown.serial, EclipseNotices.current.value!!.serial)

        EclipseNotices.post("Quiet", EclipseNotice(message = "muted"), enabled = false)
        assertFalse(EclipseNotices.current.value!!.notice.message == "muted")
    }

    @Test
    fun `notice limits follow the protocol`() {
        val long = EclipseNotice(message = "x".repeat(300), title = "t".repeat(100), duration = 99.0, level = "error")
        assertEquals(120, long.text.length)
        assertEquals(40, long.heading.length)
        assertEquals(10.0, long.displaySeconds, 0.0)
        assertEquals(6.0, EclipseNotice(message = "m", level = "warning").displaySeconds, 0.0)
        assertEquals(4.0, EclipseNotice(message = "m").displaySeconds, 0.0)
    }

    @Test
    fun `a search response tolerates every array being absent`() {
        val parsed = EclipseClient.json.decodeFromString<EclipseSearchResponse>("""{}""")
        assertTrue(parsed.tracks.isEmpty() && parsed.albums.isEmpty() && parsed.artists.isEmpty() && parsed.playlists.isEmpty())
    }
}
