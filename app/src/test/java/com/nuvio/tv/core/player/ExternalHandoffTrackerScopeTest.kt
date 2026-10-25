package com.nuvio.tv.core.player

import android.content.Context
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ExternalHandoffTrackerScopeTest {
    private val context = mockk<Context>()
    private val settings = mockk<PlayerSettingsDataStore>()
    private val metadata = ExternalPlaybackMetadata("tt42", "movie", "Fixture", null, null, null,
        "tt42", null, null, null, null, 1)
    private fun tracker() = ExternalPlaybackTracker(context, mockk(), mockk(), mockk(), mockk(), settings,
        mockk(), mockk(), mockk(), mockk(), mockk())

    @Test fun `stale source guard rejects before settings tracking or Android handoff`() = runTest {
        val tracker = tracker()
        assertFalse(tracker.launchPlayer(metadata, "https://example.invalid/file", null, null,
            context = context, launchStillCurrent = { false }))
        assertNull(tracker.pendingMetadata)
        verify { settings wasNot Called; context wasNot Called }
    }
    @Test fun `profile change during settings suspension cannot begin tracking`() = runTest {
        var current = true
        every { settings.playerSettings } returns flow { current = false; emit(PlayerSettings()) }
        val tracker = tracker()
        assertFalse(tracker.launchPlayer(metadata, "https://example.invalid/file", null, null,
            context = context, launchStillCurrent = { current }))
        assertNull(tracker.pendingMetadata)
        verify { context wasNot Called }
    }
    @Test fun `cancellation before tracking propagates without launch metadata`() = runTest {
        every { settings.playerSettings } returns flow { throw CancellationException("cancelled") }
        val tracker = tracker()
        try {
            tracker.launchPlayer(metadata, "https://example.invalid/file", null, null,
                context = context, launchStillCurrent = { true })
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertNull(tracker.pendingMetadata)
        verify { context wasNot Called }
    }
}
