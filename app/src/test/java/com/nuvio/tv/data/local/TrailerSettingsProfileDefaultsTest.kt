package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.player.TrailerVideoPolicy
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class TrailerSettingsProfileDefaultsTest {
    @Before fun setup() {
        mockkObject(TrailerVideoPolicy)
        every { TrailerVideoPolicy.default4kTrailers(any()) } returns false
    }
    @After fun cleanup() { unmockkObject(TrailerVideoPolicy) }

    @Test fun `new profile is quiet while another viewer explicit enabled choice remains intact`() = runTest {
        val h=Fixture()
        assertFalse(h.store.settings.first().enabled)
        h.active.value=2
        assertTrue(h.store.settings.first().enabled)
        h.active.value=1
        assertFalse(h.store.settings.first().enabled)
        assertNull(h.fresh.data.first()[booleanPreferencesKey("trailer_enabled")])
    }
    @Test fun `explicit enable and disable survive store recreation`() = runTest {
        val h=Fixture()
        h.store.setEnabled(true)
        assertTrue(h.recreate().settings.first().enabled)
        h.store.setEnabled(false)
        assertFalse(h.recreate().settings.first().enabled)
    }
    private class Fixture {
        val active=MutableStateFlow(1)
        val fresh=MemoryStore(emptyPreferences())
        val enabled=MemoryStore(preferencesOf(booleanPreferencesKey("trailer_enabled") to true))
        val context=mockk<Context>()
        val profiles=mockk<ProfileManager> { every { activeProfileId } returns active }
        val factory=mockk<ProfileDataStoreFactory> {
            every { get(1,"trailer_settings") } returns fresh
            every { get(2,"trailer_settings") } returns enabled
        }
        val store=recreate()
        fun recreate()=TrailerSettingsDataStore(context,factory,profiles)
    }
    private class MemoryStore(initial: Preferences):DataStore<Preferences> {
        private val values=MutableStateFlow(initial)
        private val lock=Mutex()
        override val data:Flow<Preferences> = values
        override suspend fun updateData(transform:suspend(Preferences)->Preferences):Preferences=lock.withLock {
            transform(values.value).also { values.value=it }
        }
    }
}
