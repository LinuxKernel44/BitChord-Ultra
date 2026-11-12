package com.music.bitchord

import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * YouTube Music switched off as a source of audio: nothing may be streamed from
 * it, the quality ceilings stop deciding which sources are reached, and the
 * switch is honoured rather than silently re-enabled.
 */
class YouTubeOffTest {

    private val before = SourceRegistry.configs.value
    private val wifiBefore = AppSettings.audioQualityWifi.value

    private val addon = SourceConfig(kind = SourceKind.ADDON, label = "a", baseUrl = "https://a.example.com")
    private val youtube = SourceConfig(kind = SourceKind.YOUTUBE)

    @Before
    fun setUp() {
        SourceRegistry.configs.value = listOf(addon, SourceConfig(kind = SourceKind.JIOSAAVN), youtube)
        // Publishes, which builds the instances and tells the stream resolver.
        SourceRegistry.setEnabled(youtube.id, true)
        AppSettings.meteredConnection.value = false
    }

    @After
    fun tearDown() {
        SourceRegistry.configs.value = before
        SourceRegistry.setEnabled(youtube.id, true)
        SourceRegistry.configs.value = before
        StreamResolver.audioEnabled = true
        AppSettings.audioQualityWifi.value = wifiBefore
        AppSettings.meteredConnection.value = null
    }

    @Test
    fun `youtube can be switched off and the switch is kept`() {
        SourceRegistry.setEnabled(youtube.id, false)
        assertFalse(SourceRegistry.youtubeEnabled())
        assertFalse(SourceRegistry.config(youtube.id)!!.enabled)
        assertFalse(SourceRegistry.active().any { it.kind == SourceKind.YOUTUBE })
    }

    @Test
    fun `a stored disabled youtube is no longer forced back on at start-up`() {
        val stored = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = false))
        val after = SourceRegistry.sourcesForInit(stored, forceJioSaavnOff = false)
        assertFalse(after.single { it.kind == SourceKind.YOUTUBE }.enabled)
    }

    @Test
    fun `a fresh install still has youtube on`() {
        val after = SourceRegistry.sourcesForInit(emptyList(), forceJioSaavnOff = true)
        assertTrue(after.single { it.kind == SourceKind.YOUTUBE }.enabled)
    }

    @Test
    fun `the stream resolver refuses every youtube audio request while it is off`() = runBlocking {
        SourceRegistry.setEnabled(youtube.id, false)
        assertFalse(StreamResolver.audioEnabled)
        try {
            StreamResolver.resolve("dQw4w9WgXcQ")
            fail("expected the resolver to refuse")
        } catch (expected: StreamResolver.AudioDisabledException) {
            assertTrue(expected.message!!.contains("turned off"))
        }
        try {
            StreamResolver.resolveForDownload("dQw4w9WgXcQ", maxKbps = 160)
            fail("expected downloads to be refused too")
        } catch (expected: StreamResolver.AudioDisabledException) {
        }
    }

    @Test
    fun `switching it back on lets the resolver through again`() {
        SourceRegistry.setEnabled(youtube.id, false)
        SourceRegistry.setEnabled(youtube.id, true)
        assertTrue(StreamResolver.audioEnabled)
        assertTrue(SourceRegistry.youtubeEnabled())
    }

    @Test
    fun `the refusal is not a permanent verdict, so the player stays instead of skipping`() {
        val refusal: Throwable = StreamResolver.AudioDisabledException()
        assertFalse(refusal is StreamResolver.PermanentlyUnplayableException)
    }

    @Test
    fun `a low ceiling reaches only youtube normally, and every source once it is off`() {
        AppSettings.audioQualityWifi.value = AudioQuality.LOW

        val withYouTube = SourceRegistry.activeForPlayback().map { it.kind }
        assertEquals(listOf(SourceKind.YOUTUBE), withYouTube)

        SourceRegistry.setEnabled(youtube.id, false)
        val without = SourceRegistry.activeForPlayback().map { it.kind }
        assertTrue(SourceKind.ADDON in without)
        assertFalse(SourceKind.YOUTUBE in without)
    }

    @Test
    fun `every enabled source counts as outranking youtube once it is off`() {
        assertTrue(SourceResolver.canSubstituteForYouTube())
        SourceRegistry.setEnabled(youtube.id, false)
        assertTrue(SourceResolver.canSubstituteForYouTube())
    }

    @Test
    fun `the no-source marker is remembered per track`() {
        SourceRegistry.noSourceFor("abc")
        assertTrue(SourceRegistry.isNoSource("abc"))
        assertFalse(SourceRegistry.isNoSource("other"))
        assertFalse(SourceRegistry.isNoSource(null))
    }
}
