package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.debrid.DebridAccountCheck
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.setup.*
import io.mockk.every
import io.mockk.mockk
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FirstWatchEvidenceStoreTest {
    @Test fun `read-only account check persists age without replacing any credential`() = runTest {
        val h = Harness()
        h.store.setProviderApiKey("realdebrid", "owned-key")
        h.store.setProviderApiKey("alldebrid", "other-key")
        val before = h.store.settings.first()
        val scope = h.scope()
        val binding = firstWatchAccountBinding(1, "realdebrid", "owned-key")
        assertTrue(h.store.recordFirstWatchAccount(scope, "realdebrid", binding,
            DebridAccountCheck(FirstWatchAccountOutcome.ACCEPTED), 20_000L) { true })
        assertEquals(before, h.store.settings.first())
        val observation = h.store.firstWatchObservations.first().accounts["realdebrid"]
        assertEquals(20_000L, observation?.checkedAtMs)
        assertEquals(FirstWatchAccountOutcome.ACCEPTED, observation?.outcome)
        assertFalse(h.memory.value.toString().contains("owned-key"))
        assertFalse(h.memory.value.toString().contains("other-key"))
    }

    @Test fun `cancel and profile away and back are rejected inside delayed edit`() = runTest {
        for (changeProfile in listOf(false, true)) {
            val h = Harness()
            h.store.setProviderApiKey("realdebrid", "owned-key")
            val scope = h.scope()
            var current = true
            val gate = h.gateNextEdit()
            val result = async {
                h.store.recordFirstWatchAccount(scope, "realdebrid", firstWatchAccountBinding(1, "realdebrid", "owned-key"),
                    DebridAccountCheck(FirstWatchAccountOutcome.ACCEPTED), 20_000L) { current }
            }
            gate.entered.await()
            if (changeProfile) { h.profile.value = 2; h.selection += 2; h.profile.value = 1 }
            else current = false
            gate.resume.complete(Unit)
            assertFalse(result.await())
            assertTrue(h.store.firstWatchObservations.first().accounts.isEmpty())
        }
    }

    @Test fun `credential replacement invalidates prior account and playback observations`() = runTest {
        val h = Harness()
        h.store.setProviderApiKey("realdebrid", "owned-key")
        val scope = h.scope()
        assertTrue(h.store.recordFirstWatchAccount(scope, "realdebrid", firstWatchAccountBinding(1, "realdebrid", "owned-key"),
            DebridAccountCheck(FirstWatchAccountOutcome.ACCEPTED), 20_000L) { true })
        val playback = FirstWatchPlaybackObservation("configuration", 20_000L, "Owned title", "owned:1", "exo", FirstWatchPlaybackOutcome.STARTED)
        assertTrue(h.store.recordFirstWatchPlayback(scope, playback) { true })
        h.store.setProviderApiKey("realdebrid", "replacement-key")
        assertTrue(h.store.firstWatchObservations.first().accounts.isEmpty())
        assertNull(h.store.firstWatchObservations.first().playback)
        assertFalse(h.store.recordFirstWatchAccount(scope, "realdebrid", firstWatchAccountBinding(1, "realdebrid", "owned-key"),
            DebridAccountCheck(FirstWatchAccountOutcome.ACCEPTED), 21_000L) { true })
    }

    @Test fun `validated save records accepted evidence only with its successful credential commit`() = runTest {
        val h = Harness()
        assertTrue(h.store.setProviderApiKeyForProfile(1, "realdebrid", "owned-key", "",
            acceptedCheck = DebridAccountCheck(FirstWatchAccountOutcome.ACCEPTED), checkedAtMs = 25_000L))
        assertEquals(25_000L, h.store.firstWatchObservations.first().accounts["realdebrid"]?.checkedAtMs)
        assertFalse(h.store.setProviderApiKeyForProfile(1, "realdebrid", "late-key", "different-key",
            acceptedCheck = DebridAccountCheck(FirstWatchAccountOutcome.ACCEPTED), checkedAtMs = 30_000L))
        assertEquals(25_000L, h.store.firstWatchObservations.first().accounts["realdebrid"]?.checkedAtMs)
    }

    @Test fun `late source callback cannot publish playback after owner changed`() = runTest {
        val h = Harness()
        val scope = h.scope()
        var ownerCurrent = true
        val gate = h.gateNextEdit()
        val result = async {
            h.store.recordFirstWatchPlayback(scope, FirstWatchPlaybackObservation("configuration", 20_000L,
                "Owned title", "owned:1", "mpv", FirstWatchPlaybackOutcome.STARTED)) { ownerCurrent }
        }
        gate.entered.await()
        ownerCurrent = false
        gate.resume.complete(Unit)
        assertFalse(result.await())
        assertNull(h.store.firstWatchObservations.first().playback)
    }

    @Test fun `resolver fallback and source preference away and back cannot revive persisted playback`() = runTest {
        val h = Harness()
        h.store.setProviderApiKey("realdebrid", "owned-key")
        h.store.setProviderApiKey("alldebrid", "second-owned-key")
        h.store.setPreferredResolverProviderId("realdebrid")
        val initial = h.store.settings.first()
        val captured = h.scope()
        assertTrue(h.store.recordFirstWatchAccount(captured, "realdebrid", firstWatchAccountBinding(1, "realdebrid", "owned-key"),
            DebridAccountCheck(FirstWatchAccountOutcome.ACCEPTED), 20_000L) { true })
        val mutations: List<suspend (Boolean) -> Unit> = listOf(
            { away -> h.store.setConnectedServiceFallbackEnabled(!away) },
            { away -> h.store.setPreferredResolverProviderId(if (away) "alldebrid" else "realdebrid") },
            { away -> h.store.setStreamPreferences(if (away) initial.streamPreferences.copy(maxResults = 4) else initial.streamPreferences) }
        )
        for (mutate in mutations) {
            val observation = FirstWatchPlaybackObservation("a".repeat(64), 20_000L, "Owned title", "owned:1", "exo", FirstWatchPlaybackOutcome.STARTED)
            val playbackScope = h.scope(h.store.firstWatchResolverRevision.first())
            assertTrue(h.store.recordFirstWatchPlayback(playbackScope, observation) { true })
            mutate(false)
            assertEquals("identical source settings retain observation", observation, h.recreate().firstWatchObservations.first().playback)
            mutate(true); mutate(false)
            val restored = h.recreate().firstWatchObservations.first()
            assertNull("intervening source change must survive recreation", restored.playback)
            assertEquals(FirstWatchAccountOutcome.ACCEPTED, restored.accounts["realdebrid"]?.outcome)
        }
    }

    @Test fun `pending playback capture cannot cross resolver configuration away and back`() = runTest {
        val h = Harness()
        val pending = h.scope(h.store.firstWatchResolverRevision.first())
        h.store.setConnectedServiceFallbackEnabled(false)
        h.store.setConnectedServiceFallbackEnabled(true)
        val observation = FirstWatchPlaybackObservation("a".repeat(64), 20_000L, "Owned title", "owned:1", "exo", FirstWatchPlaybackOutcome.STARTED)
        assertFalse(h.store.recordFirstWatchPlayback(pending, observation) { true })
        assertNull(h.recreate().firstWatchObservations.first().playback)
    }

    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
    }

    private class Harness {
        val memory = TestPreferencesStore()
        val profile = MutableStateFlow(1)
        var selection = 0L
        private var gate: Gate? = null
        private val delayed = object : DataStore<Preferences> {
            override val data = memory.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                gate?.also { gate = null; it.entered.complete(Unit); it.resume.await() }
                return memory.updateData(transform)
            }
        }
        private val factory = mockk<ProfileDataStoreFactory> {
            every { get(1, "debrid_settings") } returns delayed
        }
        private val profiles = mockk<ProfileManager> {
            every { activeProfileId } returns profile
            every { activeProfileSelectionRevision } answers { selection }
        }
        val store = DebridSettingsDataStore(factory, profiles,
            DebridCredentialCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") })
        fun scope(resolverRevision: String = "") = FirstWatchWriteScope(1, selection, store.activeCredentialRevision, resolverRevision)
        fun recreate() = DebridSettingsDataStore(factory, profiles,
            DebridCredentialCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") })
        fun gateNextEdit() = Gate().also { gate = it }
    }
}
