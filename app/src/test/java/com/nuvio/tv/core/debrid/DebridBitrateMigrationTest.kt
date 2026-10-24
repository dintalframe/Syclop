package com.nuvio.tv.core.debrid

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.*
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DebridBitrateMigrationTest {
    @Test fun `approved migration only prepends sort and preserves filters limits templates and prefetch`()=runTest {
        val prior=DebridStreamPreferences(maxResults=17,sizeMinGb=2,preferredLanguages=listOf(DebridStreamLanguage.JA),
            requiredResolutions=listOf(DebridStreamResolution.P1080),sortCriteria=listOf(DebridStreamSortCriterion(DebridStreamSortKey.LANGUAGE)))
        val h=Fixture(prior)
        val settings=h.store.settings.first()
        assertEquals(prior.copy(sortCriteria=DebridStreamSortCriterion.bitrateFirstOrder(prior.sortCriteria)),settings.streamPreferences)
        assertEquals(0,settings.instantPlaybackPreparationLimit)
        assertEquals("My formatter",settings.streamNameTemplate)
        assertEquals(true,h.memory.data.first()[booleanPreferencesKey("stream_bitrate_order_v1")])
    }
    @Test fun `subsequent explicit original order is not overwritten on next read`()=runTest {
        val h=Fixture(DebridStreamPreferences())
        h.store.settings.first()
        h.store.setStreamPreferences(DebridStreamPreferences(sortCriteria=emptyList(),maxResults=9))
        val next=h.store.settings.first()
        assertTrue(next.streamPreferences.sortCriteria.isEmpty());assertEquals(9,next.streamPreferences.maxResults)
    }
    private class Fixture(prior:DebridStreamPreferences) {
        val memory=MemoryStore(mutablePreferencesOf(stringPreferencesKey("stream_preferences") to Gson().toJson(prior),
            intPreferencesKey("instant_playback_preparation_limit") to 0,
            stringPreferencesKey("debrid_stream_name_template") to "My formatter"))
        private val active=MutableStateFlow(1)
        private val profiles=mockk<ProfileManager>{every{activeProfileId}returns active}
        private val factory=mockk<ProfileDataStoreFactory>{every{get(1,any())}returns memory}
        private val cipher=mockk<DebridCredentialCipher>(relaxed=true)
        val store=DebridSettingsDataStore(factory,profiles,cipher)
    }
    private class MemoryStore(initial:Preferences):DataStore<Preferences> {
        private val values=MutableStateFlow(initial);private val mutex=Mutex()
        override val data:Flow<Preferences> = values
        override suspend fun updateData(transform:suspend(Preferences)->Preferences):Preferences=mutex.withLock {
            transform(values.value).also{values.value=it}
        }
    }
}
