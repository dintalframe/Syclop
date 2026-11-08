package com.nuvio.tv.core.player

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Account-free saved-state fixtures; no external player or network is opened. */
@RunWith(AndroidJUnit4::class)
class ExternalPlayerResultBrokerAndroidTest {
    private val first = "00000000-0000-0000-0000-000000000001"
    private val second = "00000000-0000-0000-0000-000000000002"
    private val input = ExternalPlayerInput("https://example.invalid/account-free-fixture")
    private val result = ExternalPlayerResult(60_000L, 600_000L)

    @Test fun savedRegistryRestoresTheOriginalHandoffRequestCode() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val originalRegistry = FixtureRegistry()
            val original = ExternalPlayerResultBroker(originalRegistry) { _, _ -> fail("detached callback") }
            original.launch(first, input)
            val requestCode = originalRegistry.requestCodes.single()
            val saved = Bundle()
            originalRegistry.onSaveInstanceState(saved)
            original.close()

            val restoredRegistry = FixtureRegistry()
            restoredRegistry.onRestoreInstanceState(saved)
            val received = mutableListOf<Pair<String, ExternalPlayerResult?>>()
            val restored = ExternalPlayerResultBroker(restoredRegistry) { id, value -> received += id to value }
            restored.recover(first)
            assertTrue(restoredRegistry.requestCodes.isEmpty())
            restoredRegistry.complete(requestCode)
            assertEquals(listOf(first to result), received)
            restored.close()
        }
    }

    @Test fun stoppedResultSurvivesProcessStateRestorationAndWaitsForStart() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val originalRegistry = FixtureRegistry()
            var privateBuffer: BufferedExternalPlayerResult? = null
            val original = ExternalPlayerResultBroker(originalRegistry,
                onBuffered = { _, value -> privateBuffer = BufferedExternalPlayerResult(value) }) { _, _ -> fail("stopped callback") }
            original.launch(first, input)
            val requestCode = originalRegistry.requestCodes.single()
            original.deactivate()
            originalRegistry.complete(requestCode)
            val saved = Bundle()
            originalRegistry.onSaveInstanceState(saved)
            original.close()

            val restoredRegistry = FixtureRegistry()
            restoredRegistry.onRestoreInstanceState(saved)
            val received = mutableListOf<Pair<String, ExternalPlayerResult?>>()
            val restored = ExternalPlayerResultBroker(restoredRegistry, initiallyActive = false) { id, value ->
                received += id to value
            }
            restored.recover(first, checkNotNull(privateBuffer))
            assertTrue(received.isEmpty())
            restored.activate()
            assertEquals(listOf(first to result), received)
            assertTrue(restoredRegistry.requestCodes.isEmpty())
            restored.close()
        }
    }

    @Test fun rawPendingIntentRestoresDuringInactiveRegistrationAndDeliversOnce() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val originalRegistry = FixtureRegistry()
            val original = ExternalPlayerResultBroker(originalRegistry) { _, _ -> fail("detached callback") }
            original.launch(first, input)
            val requestCode = originalRegistry.requestCodes.single()
            val inFlight = Bundle()
            originalRegistry.onSaveInstanceState(inFlight)
            original.close()

            val stoppedRegistry = FixtureRegistry()
            stoppedRegistry.onRestoreInstanceState(inFlight)
            stoppedRegistry.complete(requestCode) // Raw Intent arrives before any callback is registered.
            val pending = Bundle()
            stoppedRegistry.onSaveInstanceState(pending)

            val restoredRegistry = FixtureRegistry()
            restoredRegistry.onRestoreInstanceState(pending)
            val buffered = mutableListOf<Pair<String, ExternalPlayerResult?>>()
            val received = mutableListOf<Pair<String, ExternalPlayerResult?>>()
            val restored = ExternalPlayerResultBroker(restoredRegistry, initiallyActive = false,
                onBuffered = { id, value -> buffered += id to value }) { id, value -> received += id to value }
            restored.recover(first)
            assertEquals(listOf(first to result), buffered)
            assertTrue(received.isEmpty())
            assertTrue(restoredRegistry.requestCodes.isEmpty())
            restored.activate()
            restored.activate()
            assertEquals(listOf(first to result), received)
            assertEquals(1, buffered.size)
            restored.close()
        }
    }

    @Test fun retiredRequestCannotBindTheReplacementAfterRegistryRestoration() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val originalRegistry = FixtureRegistry()
            val original = ExternalPlayerResultBroker(originalRegistry) { _, _ -> fail("detached callback") }
            original.launch(first, input)
            val oldCode = originalRegistry.requestCodes.single()
            original.retire(first)
            original.launch(second, input)
            val currentCode = originalRegistry.requestCodes.last()
            val saved = Bundle()
            originalRegistry.onSaveInstanceState(saved)
            original.close()

            val restoredRegistry = FixtureRegistry()
            restoredRegistry.onRestoreInstanceState(saved)
            val received = mutableListOf<Pair<String, ExternalPlayerResult?>>()
            val restored = ExternalPlayerResultBroker(restoredRegistry) { id, value -> received += id to value }
            restored.recover(second)
            restoredRegistry.complete(oldCode)
            assertTrue(received.isEmpty())
            restoredRegistry.complete(currentCode)
            assertEquals(listOf(second to result), received)
            restored.close()
        }
    }

    @Test fun repeatedNormalResultsDoNotAccumulateSavedRequestCodeMappings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var registry: FixtureRegistry
        lateinit var broker: ExternalPlayerResultBroker
        var received = 0
        instrumentation.runOnMainSync {
            registry = FixtureRegistry()
            broker = ExternalPlayerResultBroker(registry) { id, _ ->
                received++
                broker.retire(id)
            }
        }
        repeat(24) { index ->
            instrumentation.runOnMainSync {
                val id = "00000000-0000-0000-0000-" + (index + 1).toString().padStart(12, '0')
                broker.launch(id, input)
                registry.complete(registry.requestCodes.last())
            }
            // Let post-dispatch unregister run after AndroidX removes the launched-key marker.
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val saved = Bundle()
                registry.onSaveInstanceState(saved)
                val keys = checkNotNull(saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS"))
                val codes = checkNotNull(saved.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS"))
                assertEquals(index + 1, received)
                assertTrue("completed handoff keys accumulated: ${keys.size}", keys.isEmpty())
                assertTrue("completed request codes accumulated: ${codes.size}", codes.isEmpty())
            }
        }
        instrumentation.runOnMainSync { broker.close() }
    }

    @Test fun repeatedStoppedResultsDoNotAccumulateSavedRequestCodeMappings() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val registry = FixtureRegistry()
            var received = 0
            lateinit var broker: ExternalPlayerResultBroker
            broker = ExternalPlayerResultBroker(registry) { id, _ -> received++; broker.retire(id) }
            repeat(24) { index ->
                val id = "00000000-0000-0000-0000-" + (index + 1).toString().padStart(12, '0')
                broker.launch(id, input)
                broker.deactivate()
                registry.complete(registry.requestCodes.last())
                assertEquals(index, received)
                broker.activate()
                val saved = Bundle()
                registry.onSaveInstanceState(saved)
                assertEquals(index + 1, received)
                assertTrue(checkNotNull(saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")).isEmpty())
                assertTrue(checkNotNull(saved.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")).isEmpty())
            }
            broker.close()
        }
    }

    private class FixtureRegistry : ActivityResultRegistry() {
        val requestCodes = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) { requestCodes += requestCode }
        fun complete(requestCode: Int) {
            dispatchResult(requestCode, Activity.RESULT_OK, Intent()
                .putExtra("extra_position", 60_000L).putExtra("extra_duration", 600_000L))
        }
    }
}
