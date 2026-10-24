package com.nuvio.tv.core.debrid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class DebridManualCredentialSaverTest {
    @Test fun `Clear cancels late validation and commits before closing while other accounts survive`() = runTest {
        val h = Harness(this); h.saver.submit("premiumize", "new-pm-key") { h.saved++ }; runCurrent()
        assertTrue(h.busy)
        h.saver.submit("premiumize", "") { assertEquals("", h.keys[1 to "premiumize"]); h.saved++ }
        runCurrent(); assertEquals(1, h.saved); assertFalse(h.busy)
        h.response.complete(true); runCurrent()
        assertEquals("", h.keys[1 to "premiumize"])
        assertEquals("rd-original", h.keys[1 to "realdebrid"])
        assertEquals("ad-original", h.keys[1 to "alldebrid"])
        assertEquals(1, h.saved)
    }

    @Test fun `Cancel prevents a noncooperative validation from saving or closing a new dialog`() = runTest {
        val h = Harness(this); h.saver.submit("premiumize", "new-pm-key") { h.saved++ }; runCurrent()
        h.saver.cancel(); h.response.complete(true); runCurrent()
        assertEquals("pm-original", h.keys[1 to "premiumize"]); assertEquals(0, h.saved); assertFalse(h.busy)
    }

    @Test fun `profile away and back invalidates the originating manual operation`() = runTest {
        val h = Harness(this); h.saver.submit("premiumize", "new-pm-key") { h.saved++ }; runCurrent()
        h.profile = 2; h.guard.observe(2); h.profile = 1; h.guard.observe(1)
        h.response.complete(true); runCurrent()
        assertEquals("pm-original", h.keys[1 to "premiumize"]); assertEquals(0, h.saved)
    }

    @Test fun `newer credential is not overwritten by a late validation`() = runTest {
        val h = Harness(this); h.saver.submit("premiumize", "new-pm-key") { h.saved++ }; runCurrent()
        h.keys[1 to "premiumize"] = "replacement-pm-key"
        h.response.complete(true); runCurrent()
        assertEquals("replacement-pm-key", h.keys[1 to "premiumize"]); assertEquals(0, h.saved)
    }

    @Test fun `failed clear stays open and reports persistence failure`() = runTest {
        val h = Harness(this); h.failCommit = true
        h.saver.submit("premiumize", "") { h.saved++ }; runCurrent()
        assertEquals("pm-original", h.keys[1 to "premiumize"]); assertEquals(0, h.saved)
        assertEquals(listOf(DebridManualCredentialSaver.Failure.INVALID_KEY), h.errors); assertFalse(h.busy)
    }

    @Test fun `successful manual key has no registration dependency and closes only after commit`() = runTest {
        val h = Harness(this); h.response.complete(true)
        h.saver.submit("premiumize", "  new-pm-key  ") { assertEquals("new-pm-key", h.keys[1 to "premiumize"]); h.saved++ }
        runCurrent(); assertEquals(1, h.saved); assertFalse(h.busy)
    }

    private class Harness(scope: TestScope) {
        var profile = 1; val guard = DebridAuthProfileGuard(1)
        val keys = mutableMapOf((1 to "premiumize") to "pm-original", (1 to "realdebrid") to "rd-original", (1 to "alldebrid") to "ad-original")
        val response = CompletableDeferred<Boolean>()
        var busy = false; var saved = 0; var failCommit = false
        val errors = mutableListOf<DebridManualCredentialSaver.Failure>()
        val saver = DebridManualCredentialSaver(scope, { guard.capture(profile) }, { guard.valid(it, profile) },
            { pid, provider -> keys[pid to provider].orEmpty() },
            { _, _ -> withContext(NonCancellable) { response.await() } },
            { pid, provider, value, expected ->
                if (failCommit) throw java.io.IOException("Synthetic storage failure")
                if (profile != pid || expected != null && keys[pid to provider] != expected) false
                else { keys[pid to provider] = value; true }
            }, { busy = it }, { errors += it })
    }
}
