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
    @Test fun `file size tie migration upgrades exact prior automatic order only`()=runTest {
        val previous = listOf(DebridStreamSortCriterion(DebridStreamSortKey.BITRATE,DebridStreamSortDirection.DESC),
            DebridStreamSortCriterion(DebridStreamSortKey.RESOLUTION,DebridStreamSortDirection.DESC)) +
            DebridStreamSortCriterion.defaultOrder.filter { it.key != DebridStreamSortKey.RESOLUTION }
        val h=Fixture(DebridStreamPreferences(maxResults=11,sortCriteria=previous),alreadyV1=true)
        val migrated=h.store.settings.first()
        assertEquals(DebridStreamSortCriterion.bitrateFirstOrder(),migrated.streamPreferences.sortCriteria)
        assertEquals(11,migrated.streamPreferences.maxResults)
        assertEquals(true,h.memory.data.first()[booleanPreferencesKey("stream_size_order_v2")])
    }
    @Test fun `file size migration preserves previous custom and original order`()=runTest {
        for(criteria in listOf(emptyList(),listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE,DebridStreamSortDirection.ASC)))) {
            val h=Fixture(DebridStreamPreferences(sortCriteria=criteria),alreadyV1=true)
            assertEquals(criteria,h.store.settings.first().streamPreferences.sortCriteria)
        }
    }
    private class Fixture(prior:DebridStreamPreferences,alreadyV1:Boolean=false) {
        val memory=MemoryStore(mutablePreferencesOf(stringPreferencesKey("stream_preferences") to Gson().toJson(prior),
            intPreferencesKey("instant_playback_preparation_limit") to 0,
            stringPreferencesKey("debrid_stream_name_template") to "My formatter",
            booleanPreferencesKey("stream_bitrate_order_v1") to alreadyV1))
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
