package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.setup.*
import com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants
import com.nuvio.tv.core.streams.ServicePlaybackOrigin
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.DebridSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.just
import io.mockk.Runs
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FirstWatchPlaybackStartupTest {
    @After fun clearNativeGrant() { ConnectedServicePlaybackGrants.registry.clear() }

    @Test fun `registered Media3 listener ignores READY and accepts actual first frame only from its player`() = runTest {
        val h = Harness(this)
        h.controller.attachFirstWatchMedia3Listener(h.player, h.capture)
        h.listener.captured.onPlaybackStateChanged(Player.STATE_READY)
        runCurrent(); assertTrue(h.saved.isEmpty())
        h.listener.captured.onRenderedFirstFrame()
        runCurrent(); assertEquals(1, h.saved.size)
        val oldListener = h.listener.captured
        h.activeCapture = null
        oldListener.onRenderedFirstFrame()
        runCurrent(); assertEquals(1, h.saved.size)
    }

    @Test fun `capture binds actual selected owner and refuses revoked native grant`() = runTest {
        val h = Harness(this)
        val captured = h.controller.captureFirstWatchPlayback(h.url)
        assertNotNull(captured)
        assertEquals("owned:1", captured?.videoId)
        assertEquals("Owned selection", captured?.title)
        assertEquals(h.configuration.value?.binding, captured?.binding)
        ConnectedServicePlaybackGrants.registry.clear()
        assertNull(h.controller.captureFirstWatchPlayback(h.url))
    }

    @Test fun `old registered listener cannot report a replacement player`() = runTest {
        val h = Harness(this)
        h.controller.attachFirstWatchMedia3Listener(h.player, h.capture)
        h.activePlayer = mockk()
        h.listener.captured.onRenderedFirstFrame()
        runCurrent(); assertTrue(h.saved.isEmpty())
    }

    @Test fun `same player reused with a new capture cannot credit an old queued frame`() = runTest {
        val h = Harness(this)
        h.controller.attachFirstWatchMedia3Listener(h.player, h.capture)
        h.activeCapture = FirstWatchPlaybackCapture(h.capture.scope, h.capture.binding,
            h.capture.url, "New selection", "owned:2")
        h.listener.captured.onRenderedFirstFrame()
        runCurrent()
        assertTrue(h.saved.isEmpty())
        assertFalse(h.activeCapture!!.recorded)
    }

    @Test fun `synthetic tunneling ready cannot record but actual rendered frame can`() = runTest {
        val h = Harness(this)
        h.controller.recordFirstWatchStarted(h.capture, "exo")
        runCurrent(); assertTrue(h.saved.isEmpty())
        h.controller.recordFirstWatchStarted(h.capture, "exo", renderedFrame = true)
        runCurrent()
        assertEquals(1, h.saved.size)
        assertEquals("Owned selection", h.saved.single().title)
        assertEquals("owned:1", h.saved.single().videoId)
        assertEquals("exo", h.saved.single().engine)
    }

    @Test fun `requested media MPV startup records once and external launch stays unknown`() = runTest {
        val h = Harness(this)
        h.controller.recordFirstWatchStarted(h.capture, "external", renderedFrame = true)
        runCurrent(); assertTrue(h.saved.isEmpty())
        h.controller.recordFirstWatchStarted(h.capture, "mpv", requestedMpvProgress = true)
        h.controller.recordFirstWatchStarted(h.capture, "mpv", requestedMpvProgress = true)
        runCurrent()
        assertEquals(1, h.saved.size)
        assertEquals("mpv", h.saved.single().engine)
    }

    @Test fun `stale owner source profile or credential cannot publish a startup`() = runTest {
        for (change in listOf("owner", "source", "profile-or-credential", "configuration", "cancel", "grant")) {
            val h = Harness(this)
            h.controller.recordFirstWatchStarted(h.capture, "exo", renderedFrame = true)
            when (change) {
                "owner" -> h.activeCapture = null
                "source" -> h.url = "https://other.example/selection"
                "profile-or-credential" -> h.current = false
                "configuration" -> h.configuration.value = h.configuration.value?.copy(engine = "changed")
                "cancel" -> h.releasing = true
                "grant" -> ConnectedServicePlaybackGrants.registry.clear()
            }
            runCurrent()
            assertTrue(change, h.saved.isEmpty())
        }
    }

    private class Harness(scope: TestScope) {
        val player = mockk<ExoPlayer>()
        var activePlayer = player
        val listener = slot<Player.Listener>()
        var url = "https://owned.example/selected-file"
        var current = true
        var releasing = false
        val saved = mutableListOf<FirstWatchPlaybackObservation>()
        val configuration = MutableStateFlow<FirstWatchConfiguration?>(FirstWatchConfiguration(1,
            listOf(Addon("source", "Source", version = "1", description = null, logo = null,
                baseUrl = "https://source.example", catalogs = emptyList(), types = emptyList(), resources = emptyList())),
            DebridSettings(realDebridApiKey = "owned-key"), "internal:EXOPLAYER"))
        val capture = FirstWatchPlaybackCapture(FirstWatchWriteScope(1, 0, 0), configuration.value!!.binding,
            url, "Owned selection", "owned:1")
        var activeCapture: FirstWatchPlaybackCapture? = capture
        private val repository = mockk<FirstWatchReadinessRepository>().also { repository ->
            every { repository.isCurrent(any()) } answers { current }
            every { repository.configuration } returns configuration
            every { repository.captureScope() } returns FirstWatchWriteScope(1, 0, 0)
            coEvery { repository.recordPlayback(any(), any(), any()) } coAnswers {
                if (thirdArg<() -> Boolean>()()) { saved += secondArg<FirstWatchPlaybackObservation>(); true } else false
            }
        }
        val controller = mockk<PlayerRuntimeController>(relaxed = true).also { controller ->
            every { controller.firstWatchReadiness } returns repository
            every { controller.firstWatchCapture } answers { activeCapture }
            every { controller.currentStreamUrl } answers { url }
            every { controller.isReleasingPlayer } answers { releasing }
            every { controller.scope } returns scope
            every { controller._exoPlayer } answers { activePlayer }
            every { controller.profileId } returns 1
            every { controller.title } returns "Owned selection"
            every { controller.currentVideoId } returns "owned:1"
        }
        init {
            every { player.addListener(capture(listener)) } just Runs
            every { player.isReleased } returns false
            val registry = ConnectedServicePlaybackGrants.registry
            registry.bindLiveOrigin { ServicePlaybackOrigin(1, 0, 0) }
            val nativeScope = registry.observe(1, mapOf("realdebrid" to "owned-key"), 0, 0)
            check(registry.issue(url, "realdebrid", nativeScope))
        }
    }
}
