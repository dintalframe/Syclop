package com.nuvio.tv.core.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ExternalPlayerHandoffTest {
    @Test fun `missing uninstalled or ineligible handler leaves loaded internal player untouched`() = runTest {
        for (reason in listOf("missing", "uninstalled", "ineligible", "unsupported_headers", "revoked_grant")) {
            val calls = mutableListOf<String>()
            val result = ExternalPlayerHandoff.run({ true }, { calls += reason; false },
                { calls += "prepare" }, { calls += "launch"; true }, { calls += "release" })
            assertFalse(result)
            assertEquals(listOf(reason), calls)
        }
    }
    @Test fun `valid handoff releases internal only after actual accepted external launch`() = runTest {
        val calls = mutableListOf<String>()
        assertTrue(ExternalPlayerHandoff.run({ true }, { calls += "preflight"; true },
            { calls += "prepare" }, { calls += "accepted"; true }, { calls += "release" }))
        assertEquals(listOf("preflight", "prepare", "accepted", "release"), calls)
    }
    @Test fun `handler removed after preflight cannot tear down internal playback`() = runTest {
        var released = false
        assertFalse(ExternalPlayerHandoff.run({ true }, { true }, {}, { false }, { released = true }))
        assertFalse(released)
    }
    @Test fun `late activity launch exception preserves internal resources`() = runTest {
        var released = false
        try {
            ExternalPlayerHandoff.run({ true }, { true }, {}, { throw SecurityException("synthetic") }, { released = true })
            fail("launch failure must propagate to the caller's fixed error handling")
        } catch (_: SecurityException) { }
        assertFalse(released)
    }
    @Test fun `changed request before preflight performs no preparation launch or release`() = runTest {
        val calls = mutableListOf<String>()
        assertFalse(ExternalPlayerHandoff.run({ false }, { calls += "preflight"; true },
            { calls += "prepare" }, { calls += "launch"; true }, { calls += "release" }))
        assertTrue(calls.isEmpty())
    }
    @Test fun `profile away and back during preflight invalidates the captured request`() = runTest {
        val originalRevision = 4L
        var revision = originalRevision
        val calls = mutableListOf<String>()
        assertFalse(ExternalPlayerHandoff.run({ revision == originalRevision }, { revision = 6L; true },
            { calls += "prepare" }, { calls += "launch"; true }, { calls += "release" }))
        assertTrue(calls.isEmpty())
    }
    @Test fun `source or account change while subtitles prepare cannot launch or stop new source`() = runTest {
        var current = true
        val calls = mutableListOf<String>()
        assertFalse(ExternalPlayerHandoff.run({ current }, { true }, { current = false },
            { calls += "launch"; true }, { calls += "release" }))
        assertTrue(calls.isEmpty())
    }
    @Test fun `source changed during accepted handoff cannot stop another internal source`() = runTest {
        var current = true
        var released = false
        assertFalse(ExternalPlayerHandoff.run({ current }, { true }, {}, { current = false; true }, { released = true }))
        assertFalse(released)
    }
    @Test fun `cancelling preparation propagates and does not release internal player`() = runTest {
        var released = false
        var launched = false
        try {
            ExternalPlayerHandoff.run({ true }, { true }, { throw CancellationException("cancelled") },
                { launched = true; true }, { released = true })
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(launched)
        assertFalse(released)
    }
    @Test fun `cancelling the launch does not run success cleanup`() = runTest {
        var released = false
        try {
            ExternalPlayerHandoff.run({ true }, { true }, {}, { throw CancellationException("cancelled") }, { released = true })
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(released)
    }
}
