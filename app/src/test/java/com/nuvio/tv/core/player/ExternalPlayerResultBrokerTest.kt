package com.nuvio.tv.core.player

import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.*
import org.junit.Test

/** Uses AndroidX's actual request-code/key dispatch with synthetic player results. */
class ExternalPlayerResultBrokerTest {
    private val first = "00000000-0000-0000-0000-000000000001"
    private val second = "00000000-0000-0000-0000-000000000002"
    private val input = ExternalPlayerInput("https://example.invalid/account-free-fixture")
    private val result = ExternalPlayerResult(60_000L, 600_000L)

    @Test fun `late retired result cannot be delivered to its replacement handoff`() {
        val registry = FixtureRegistry()
        val received = mutableListOf<Pair<String, ExternalPlayerResult?>>()
        val broker = ExternalPlayerResultBroker(registry) { id, value -> received += id to value }
        broker.launch(first, input)
        val oldCode = registry.requestCodes.single()
        broker.retire(first)
        broker.launch(second, input)
        val currentCode = registry.requestCodes.last()
        assertNotEquals(oldCode, currentCode)
        registry.complete(oldCode, result)
        assertTrue(received.isEmpty())
        registry.complete(currentCode, result)
        assertEquals(listOf(second to result), received)
    }

    @Test fun `rebound broker receives an outstanding result under its original identity`() {
        val registry = FixtureRegistry()
        val received = mutableListOf<Pair<String, ExternalPlayerResult?>>()
        val original = ExternalPlayerResultBroker(registry) { _, _ -> fail("detached callback") }
        original.launch(first, input)
        val code = registry.requestCodes.single()
        original.close()
        val recovered = ExternalPlayerResultBroker(registry) { id, value -> received += id to value }
        recovered.recover(first)
        assertEquals(1, registry.requestCodes.size) // Recovery does not launch the player again.
        registry.complete(code, result)
        assertEquals(listOf(first to result), received)
    }

    @Test fun `already parsed pending result can retire its key during recovery registration`() {
        val registry = FixtureRegistry()
        val original = ExternalPlayerResultBroker(registry) { _, _ -> fail("detached callback") }
        original.launch(first, input)
        val code = registry.requestCodes.single()
        original.close()
        registry.complete(code, result)
        val received = mutableListOf<Pair<String, ExternalPlayerResult?>>()
        lateinit var recovered: ExternalPlayerResultBroker
        recovered = ExternalPlayerResultBroker(registry) { id, value ->
            received += id to value
            recovered.retire(id)
        }
        recovered.recover(first)
        recovered.recover(first)
        assertEquals(listOf(first to result), received)
    }

    @Test fun `stopped callbacks wait in Android registry until the broker starts again`() {
        val registry = FixtureRegistry()
        val received = mutableListOf<Pair<String, ExternalPlayerResult?>>()
        val broker = ExternalPlayerResultBroker(registry) { id, value -> received += id to value }
        broker.launch(first, input)
        val code = registry.requestCodes.single()
        broker.deactivate()
        registry.complete(code, result)
        assertTrue(received.isEmpty())
        broker.activate()
        assertEquals(listOf(first to result), received)
        assertEquals(1, registry.requestCodes.size)
    }

    @Test fun `received null result is buffered distinctly until activation`() {
        val registry = FixtureRegistry()
        val buffered = mutableListOf<Pair<String, ExternalPlayerResult?>>()
        val received = mutableListOf<Pair<String, ExternalPlayerResult?>>()
        val broker = ExternalPlayerResultBroker(registry, onBuffered = { id, value -> buffered += id to value }) {
            id, value -> received += id to value
        }
        broker.launch(first, input)
        broker.deactivate()
        registry.complete(registry.requestCodes.single(), null)
        assertEquals(listOf(first to null), buffered)
        assertTrue(received.isEmpty())
        broker.activate()
        assertEquals(listOf(first to null), received)
    }

    @Test fun `persisted received result outranks duplicate registry replay`() {
        val registry = FixtureRegistry()
        val original = ExternalPlayerResultBroker(registry) { _, _ -> fail("detached") }
        original.launch(first, input)
        val code = registry.requestCodes.single()
        original.close()
        registry.complete(code, ExternalPlayerResult(590_000L, 600_000L))
        val received = mutableListOf<Pair<String, ExternalPlayerResult?>>()
        val buffered = mutableListOf<Pair<String, ExternalPlayerResult?>>()
        val recovered = ExternalPlayerResultBroker(registry, initiallyActive = false,
            onBuffered = { id, value -> buffered += id to value }) { id, value -> received += id to value }
        recovered.recover(first, BufferedExternalPlayerResult(result))
        assertTrue(received.isEmpty())
        assertTrue(buffered.isEmpty())
        recovered.activate()
        assertEquals(listOf(first to result), received)
    }

    @Test fun `normal result unregister is deferred until Android dispatch returns`() {
        val registry = FixtureRegistry()
        val cleanup = mutableListOf<() -> Unit>()
        val received = mutableListOf<String>()
        lateinit var broker: ExternalPlayerResultBroker
        broker = ExternalPlayerResultBroker(registry, deferCleanup = { cleanup += it }) { id, _ ->
            received += id
            broker.retire(id)
        }
        broker.launch(first, input)
        val code = registry.requestCodes.single()
        assertTrue(registry.complete(code, result))
        assertEquals(listOf(first), received)
        assertEquals(1, cleanup.size)
        cleanup.removeAt(0).invoke()
        assertFalse(registry.complete(code, result)) // The actual AndroidX request-code map was removed.
        assertEquals(listOf(first), received)
    }

    @Test fun `same handoff cannot launch twice`() {
        val registry = FixtureRegistry()
        val broker = ExternalPlayerResultBroker(registry) { _, _ -> }
        broker.launch(first, input)
        assertTrue(runCatching { broker.launch(first, input) }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, registry.requestCodes.size)
    }

    @Test fun `closed broker cannot send or recover handoffs`() {
        val registry = FixtureRegistry()
        val broker = ExternalPlayerResultBroker(registry) { _, _ -> }
        broker.close()
        assertTrue(runCatching { broker.launch(first, input) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { broker.recover(first) }.exceptionOrNull() is IllegalStateException)
        assertTrue(registry.requestCodes.isEmpty())
    }

    private class FixtureRegistry : ActivityResultRegistry() {
        val requestCodes = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) { requestCodes += requestCode }
        fun complete(requestCode: Int, value: ExternalPlayerResult?): Boolean = dispatchResult(requestCode, value)
    }
}
