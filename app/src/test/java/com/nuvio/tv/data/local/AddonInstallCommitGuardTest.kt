package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AddonInstallCommitGuardTest {
    @Test fun `replaced install cannot commit after its queued datastore edit is released`()=runTest {
        val f=Fixture();val revision=AtomicLong(1L)
        val result=async { f.preferences.addAddonIfCurrent("https://old.example",1,7) { revision.get()==1L } }
        f.memory.entered.await();revision.incrementAndGet();f.memory.release.complete(Unit)
        assertFalse(result.await());assertNull(f.memory.value[stringPreferencesKey("installed_addon_urls_ordered")])
    }
    @Test fun `dismissed install cannot commit after its queued datastore edit is released`()=runTest {
        val f=Fixture();val current=java.util.concurrent.atomic.AtomicBoolean(true)
        val result=async { f.preferences.addAddonIfCurrent("https://dismissed.example",1,7,current::get) }
        f.memory.entered.await();current.set(false);f.memory.release.complete(Unit)
        assertFalse(result.await());assertNull(f.memory.value[stringPreferencesKey("installed_addon_urls_ordered")])
    }
    @Test fun `reinstall leaves existing disabled choice and provider order unchanged`()=runTest {
        val before=preferencesOf(stringPreferencesKey("installed_addon_urls_ordered") to "[\"https://first.example\",\"https://kept.example\"]",
            stringPreferencesKey("installed_addon_enabled_states") to "{\"https://kept.example\":false}")
        val f=Fixture(before);f.memory.release.complete(Unit)
        assertTrue(f.preferences.addAddonIfCurrent("https://kept.example/manifest.json",1,7))
        assertEquals(before,f.memory.value)
    }
    private class Fixture(initial:Preferences=emptyPreferences()) {
        val memory=QueuedStore(initial)
        private val profiles=mockk<ProfileManager>(relaxed=true) {
            every { activeProfileId } returns MutableStateFlow(1)
            every { activeProfileSelectionRevision } returns 7L
            every { activeProfile } returns null
        }
        private val factory=mockk<ProfileDataStoreFactory> { every { get(1,"addon_preferences") } returns memory }
        val preferences=AddonPreferences(factory,profiles)
    }
    private class QueuedStore(initial:Preferences):DataStore<Preferences> {
        var value=initial
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        override val data:Flow<Preferences> get()=flowOf(value)
        override suspend fun updateData(transform:suspend(Preferences)->Preferences):Preferences {
            entered.complete(Unit);release.await()
            return transform(value).also { value=it }
        }
    }
}
