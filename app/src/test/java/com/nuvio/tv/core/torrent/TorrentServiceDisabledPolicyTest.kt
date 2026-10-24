package com.nuvio.tv.core.torrent

import android.content.Context
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TorrentServiceDisabledPolicyTest {
    @Test fun `direct backend start fails before reading settings or touching native engine`() = runTest {
        val context = mockk<Context>(); val settings = mockk<TorrentSettings>()
        val service = TorrentService(context, settings)
        try { service.startStream("a".repeat(40), null); fail("P2P must be rejected") }
        catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("disabled")) }
        assertEquals(TorrentState.Idle, service.state.value)
        confirmVerified(context, settings)
    }
    @Test fun `cache action never creates an engine when local P2P is forbidden`() = runTest {
        val context = mockk<Context>(); val settings = mockk<TorrentSettings>()
        val service = TorrentService(context, settings)
        try { service.clearCache(); fail("Cache action must not create a local engine") }
        catch (_: IllegalStateException) { }
        assertEquals(TorrentState.Idle, service.state.value)
        confirmVerified(context, settings)
    }
}
