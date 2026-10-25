package com.nuvio.tv.core.initialization
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class InitializationPreferencesTest {
    @Test fun `only opaque stable account identity is saved and profiles are separate`()=runTest {
        val one=MemoryInitializationStore();val two=MemoryInitializationStore()
        val factory=mockk<ProfileDataStoreFactory>{every{get(1,any())}returns one;every{get(2,any())}returns two}
        val prefs=InitializationPreferences(factory)
        val key=InitializationMarkerKey.fromAccount(1,InitializationProvider.TRAKT,"synthetic-account-name")!!
        assertTrue(prefs.complete(key){true});assertTrue(prefs.contains(key))
        assertFalse(prefs.contains(key.copy(profileId=2)))
        val saved=one.data.first().asMap().values.joinToString()
        assertFalse(saved.contains("synthetic-account-name"));assertTrue(saved.contains(key.accountHash))
        assertEquals(64,key.accountHash.length)
    }
    @Test fun `queued stale completion refuses the durable edit`()=runTest {
        val store=MemoryInitializationStore();val factory=mockk<ProfileDataStoreFactory>{every{get(any(),any())}returns store}
        val prefs=InitializationPreferences(factory);val key=InitializationMarkerKey.fromAccount(1,InitializationProvider.MAL,"viewer")!!
        var current=true;store.beforeUpdate={current=false}
        assertFalse(prefs.complete(key){current});assertFalse(prefs.contains(key))
    }
    @Test fun `disconnect invalidates only that provider and preserves other account choices`()=runTest {
        val store=MemoryInitializationStore();val factory=mockk<ProfileDataStoreFactory>{every{get(any(),any())}returns store}
        val prefs=InitializationPreferences(factory)
        val one=InitializationMarkerKey.fromAccount(1,InitializationProvider.TRAKT,"one")!!
        val two=InitializationMarkerKey.fromAccount(1,InitializationProvider.MAL,"two")!!
        prefs.complete(one){true};prefs.complete(two){true};prefs.invalidateProvider(1,InitializationProvider.TRAKT){true}
        assertFalse(prefs.contains(one));assertTrue(prefs.contains(two))
    }
    @Test fun `marker hash is provider separated and rejects malformed identity`() {
        val a=InitializationMarkerKey.fromAccount(1,InitializationProvider.TRAKT,"viewer")!!
        val b=InitializationMarkerKey.fromAccount(1,InitializationProvider.MAL,"viewer")!!
        assertNotEquals(a.accountHash,b.accountHash);assertNull(InitializationMarkerKey.fromAccount(1,InitializationProvider.MAL,"x\nprivate"))
        assertNull(InitializationMarkerKey.fromAccount(1,InitializationProvider.MAL,"x".repeat(513)))
    }
}
internal class MemoryInitializationStore:DataStore<Preferences> {
    private val lock=Mutex();private val values=MutableStateFlow(emptyPreferences())
    var beforeUpdate:(suspend()->Unit)?=null
    override val data:Flow<Preferences> =values
    override suspend fun updateData(transform:suspend(Preferences)->Preferences):Preferences=lock.withLock {
        beforeUpdate?.invoke();transform(values.value).also{values.value=it}
    }
}
