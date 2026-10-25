package com.nuvio.tv.core.initialization
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class AccountImportEventsTest {
    @Test fun `ordinary profile reload generation does not erase same-account marker`()=runTest {
        val h=Fixture();h.events.stamp(1,0,1,true,"viewer"){true};h.prefs.complete(h.key){true}
        h.events.stamp(1,2,3,true,"viewer"){true}
        assertTrue(h.prefs.contains(h.key))
    }
    @Test fun `same-selection reconnect requires a new import even for same account`()=runTest {
        val h=Fixture();h.events.stamp(1,0,1,true,"viewer"){true};h.prefs.complete(h.key){true}
        h.events.stamp(1,0,2,true,"viewer"){true}
        assertFalse(h.prefs.contains(h.key))
    }
    @Test fun `loaded disconnect invalidates prior completion but stale loading event does not`()=runTest {
        val h=Fixture();h.events.stamp(1,0,1,true,"viewer"){true};h.prefs.complete(h.key){true}
        h.events.stamp(1,0,2,false,null){false};assertTrue(h.prefs.contains(h.key))
        h.events.stamp(1,0,2,false,null){true};assertFalse(h.prefs.contains(h.key))
    }
    @Test fun `marker IO failure remains recoverable and cannot reuse stale completion`()=runTest {
        val h=Fixture();h.events.stamp(1,0,1,true,"viewer"){true};h.prefs.complete(h.key){true}
        h.store.beforeUpdate={throw java.io.IOException("synthetic private disk detail")}
        val event=h.events.stamp(1,0,2,false,null){true}
        assertFalse(event.contains("private disk detail"))
        try{h.events.ensureReady(1){true};fail("failed invalidation accepted")}catch(e:InitializationReadException){assertEquals(InitializationFailure.UNAVAILABLE,e.reason)}
        h.store.beforeUpdate=null
        h.events.ensureReady(1){true};assertFalse(h.prefs.contains(h.key))
        val next=h.events.stamp(1,0,3,true,"viewer"){true};assertTrue(next.startsWith("1:3:true:"))
    }
    private class Fixture {
        val store=MemoryInitializationStore()
        private val factory=mockk<ProfileDataStoreFactory>{every{get(any(),any())}returns store}
        val prefs=InitializationPreferences(factory)
        val events=AccountImportEvents(prefs,InitializationProvider.MAL)
        val key=InitializationMarkerKey.fromAccount(1,InitializationProvider.MAL,"viewer")!!
    }
}
