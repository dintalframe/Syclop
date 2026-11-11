package com.nuvio.tv.core.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAutoNextNavigationEventsTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `pending event survives a missing collector and is delivered only once`() = runTest {
        var now = 1_000L
        val events = ExternalAutoNextNavigationEvents(maxAgeMs = 65_000L) { now }
        val first = event(requestedAtMs = now, episode = 2)

        events.publish(first)

        val delivered = events.events.first()
        assertSame(first, delivered)
        assertTrue(events.claim(delivered, activeProfileId = 1))
        assertFalse(events.claim(delivered, activeProfileId = 1))

        val recreatedCollector = async { events.events.first() }
        runCurrent()
        assertFalse(recreatedCollector.isCompleted)

        now = 2_000L
        val second = event(requestedAtMs = now, episode = 3)
        events.publish(second)

        assertSame(second, recreatedCollector.await())
        assertTrue(events.claim(second, activeProfileId = 1))
    }

    @Test
    fun `event older than the handoff window is discarded`() = runTest {
        var now = 1_000L
        val events = ExternalAutoNextNavigationEvents(maxAgeMs = 65_000L) { now }
        val stale = event(requestedAtMs = now, episode = 2)
        events.publish(stale)

        now += 65_001L

        assertFalse(events.claim(events.events.first(), activeProfileId = 1))
        val fresh = event(requestedAtMs = now, episode = 3)
        events.publish(fresh)
        assertSame(fresh, events.events.first())
        assertTrue(events.claim(fresh, activeProfileId = 1))
    }

    @Test
    fun `stale delivery cannot consume a newer pending event`() = runTest {
        var now = 1_000L
        val events = ExternalAutoNextNavigationEvents(maxAgeMs = 65_000L) { now }
        val first = event(requestedAtMs = now, episode = 2)
        events.publish(first)
        val deliveredFirst = events.events.first()

        now = 2_000L
        val second = event(requestedAtMs = now, episode = 3)
        events.publish(second)

        assertFalse(events.claim(deliveredFirst, activeProfileId = 1))
        assertSame(second, events.events.first())
        assertTrue(events.claim(second, activeProfileId = 1))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `switching viewers consumes a pending event without navigating`() = runTest {
        val now = 1_000L
        val events = ExternalAutoNextNavigationEvents(maxAgeMs = 65_000L) { now }
        val previousViewerEvent = event(requestedAtMs = now, episode = 2, profileId = 2)
        events.publish(previousViewerEvent)

        assertFalse(events.claim(events.events.first(), activeProfileId = 1))
        assertFalse(events.claim(previousViewerEvent, activeProfileId = 2))
        val currentViewer = async { events.events.first() }
        runCurrent()
        assertFalse(currentViewer.isCompleted)

        val currentViewerEvent = event(requestedAtMs = now, episode = 3, profileId = 1)
        events.publish(currentViewerEvent)
        assertSame(currentViewerEvent, currentViewer.await())
        assertTrue(events.claim(currentViewerEvent, activeProfileId = 1))
    }

    private fun event(requestedAtMs: Long, episode: Int, profileId: Int = 1) = ExternalAutoNextEpisode(
        contentId = "series-id",
        contentType = "series",
        contentName = "Series",
        poster = null,
        backdrop = null,
        logo = null,
        year = "2026",
        nextVideoId = "episode-$episode",
        nextSeason = 1,
        nextEpisode = episode,
        profileId = profileId,
        requestedAtMs = requestedAtMs
    )
}
