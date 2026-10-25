package com.nuvio.tv.core.initialization
import com.nuvio.tv.data.simkl.*
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
class SimklInitializationBatchTest {
    @Test fun `batch account guard is rechecked after shared rate-limit wait before HTTP`()=runTest {
        var current=true;var calls=0
        val auth=SimklAuthorization(SimklAuthScope(1,2),"synthetic-token")
        val client=SimklApiClient(SimklHttpEngine{_,_,_,_->calls++;SimklRawHttpResponse(200,"{}")},
            SimklApiConfiguration("synthetic-client","syclop","1"),{auth},{},nowEpochMs={0L},sleep={current=false})
        val scope=InitializationReadScope(InitializationProvider.SIMKL,"one"){current}
        withContext(scope){client.execute(SimklApiRequest(SimklHttpMethod.GET,"/sync/activities"))}
        try {withContext(scope){client.execute(SimklApiRequest(SimklHttpMethod.GET,"/sync/playback"))};fail("stale request dispatched")}
        catch(e:InitializationReadException){assertEquals(InitializationFailure.ACCOUNT_CHANGED,e.reason)}
        assertEquals(1,calls)
    }
    @Test fun `changed batch owner after HTTP is rejected before response decoding`()=runTest {
        var current=true;var calls=0;val auth=SimklAuthorization(SimklAuthScope(1,2),"synthetic-token")
        val client=SimklApiClient(SimklHttpEngine{_,_,_,_->calls++;current=false;SimklRawHttpResponse(200,"{}")},
            SimklApiConfiguration("synthetic-client","syclop","1"),{auth},{})
        val scope=InitializationReadScope(InitializationProvider.SIMKL,"one"){current}
        try{withContext(scope){client.execute(SimklApiRequest(SimklHttpMethod.GET,"/sync/activities"))};fail("stale response accepted")}
        catch(e:InitializationReadException){assertEquals(InitializationFailure.ACCOUNT_CHANGED,e.reason)}
        assertEquals(1,calls)
    }
    @Test fun `stale batch checkpoint is not retried as a network transport error`()=runTest {
        var current=false;var calls=0;var sleeps=0
        val client=SimklApiClient(SimklHttpEngine{_,_,_,_->calls++;SimklRawHttpResponse(200,"{}")},
            SimklApiConfiguration("synthetic-client","syclop","1"),{SimklAuthorization(SimklAuthScope(1,2),"synthetic")},{},sleep={sleeps++})
        try{withContext(InitializationReadScope(InitializationProvider.SIMKL,"one"){current}){
            client.execute(SimklApiRequest(SimklHttpMethod.GET,"/sync/activities"))};fail("stale batch accepted")}
        catch(_:InitializationReadException){}
        assertEquals(0,calls);assertEquals(0,sleeps)
    }
    @Test fun `batch auth scope cannot be replaced by an independently captured helper account`()=runTest {
        var calls=0;val a=SimklAuthScope(1,2);val b=SimklAuthScope(1,3)
        val client=SimklApiClient(SimklHttpEngine{_,_,_,_->calls++;SimklRawHttpResponse(200,"{}")},
            SimklApiConfiguration("synthetic-client","syclop","1"),{SimklAuthorization(b,"synthetic-b")},{})
        val owner=InitializationReadScope(InitializationProvider.SIMKL,"one"){true}
        try {withContext(owner+SimklInitializationAuthScope(a)) {
            client.execute(SimklApiRequest(SimklHttpMethod.GET,"/users/settings"),expectedAuthScope=b)
        };fail("helper replaced batch auth")} catch(e:InitializationReadException){assertEquals(InitializationFailure.ACCOUNT_CHANGED,e.reason)}
        assertEquals(0,calls)
    }
    @Test fun `snapshot commit rechecks captured account inside queued durable edit`()=runTest {
        val data=MemoryInitializationStore();val factory=mockk<ProfileDataStoreFactory>{every{get(any(),any())}returns data}
        val storage=AndroidSimklSyncStorage(factory);var current=true
        data.beforeUpdate={current=false}
        assertFalse(storage.saveIfCurrent(1,"synthetic-snapshot"){current})
        assertNull(storage.load(1))
    }
    @Test fun `legacy unbound snapshot is retained but never projected as another account`()=runTest {
        val original="""{"isInitialized":true,"watermark":"synthetic-old-watermark"}"""
        val storage=MemorySnapshot(original);val active=MutableStateFlow(1)
        val profiles=mockk<ProfileManager>{every{activeProfileId}returns active;every{activeProfileSelectionRevision}returns 0L}
        val auth=mockk<SimklAuthStorage>{every{state}returns MutableStateFlow(SimklAuthState(isAuthenticated=true,accountId=123));every{currentScope()}returns SimklAuthScope(1,1)}
        val repository=SimklSyncRepository(mockk(),storage,mockk(),auth,profiles,StandardTestDispatcher(testScheduler),backgroundScope)
        repository.ensureLoaded()
        assertFalse(repository.state.value.snapshot.isInitialized);assertNull(repository.state.value.snapshot.watermark)
        assertEquals(original,storage.payload)
    }
    @Test fun `tagged same-account snapshot remains usable on a later cold start`()=runTest {
        val hash=InitializationMarkerKey.fromAccount(1,InitializationProvider.SIMKL,"id:123")!!.accountHash
        val storage=MemorySnapshot("""{"isInitialized":true,"accountIdentityHash":"$hash"}""")
        val active=MutableStateFlow(1)
        val profiles=mockk<ProfileManager>{every{activeProfileId}returns active;every{activeProfileSelectionRevision}returns 0L}
        val auth=mockk<SimklAuthStorage>{every{state}returns MutableStateFlow(SimklAuthState(isAuthenticated=true,accountId=123));every{currentScope()}returns SimklAuthScope(1,1)}
        val repository=SimklSyncRepository(mockk(),storage,mockk(),auth,profiles,StandardTestDispatcher(testScheduler),backgroundScope)
        repository.ensureLoaded();assertTrue(repository.state.value.snapshot.isInitialized)
    }
    @Test fun `new account cannot borrow foreign fresh snapshot as completed import`()=runTest {
        val hashA=InitializationMarkerKey.fromAccount(1,InitializationProvider.SIMKL,"id:123")!!.accountHash
        val hashB=InitializationMarkerKey.fromAccount(1,InitializationProvider.SIMKL,"id:456")!!.accountHash
        val storage=MemorySnapshot("""{"isInitialized":true,"lastCheckedAtEpochMs":${System.currentTimeMillis()},"accountIdentityHash":"$hashA"}""")
        val active=MutableStateFlow(1);val state=MutableStateFlow(SimklAuthState(isAuthenticated=true,accountId=123))
        var accountScope=SimklAuthScope(1,1)
        val profiles=mockk<ProfileManager>{every{activeProfileId}returns active;every{activeProfileSelectionRevision}returns 0L}
        val auth=mockk<SimklAuthStorage>{every{this@mockk.state}returns state;every{currentScope()}answers{accountScope};every{isCurrent(any())}answers{firstArg<SimklAuthScope>()==accountScope}}
        val engine=mockk<SimklSyncEngine>();var reads=0
        coEvery{engine.synchronize(any())}coAnswers{reads++;assertFalse(firstArg<SimklSyncSnapshot>().isInitialized);SimklSyncSnapshot(isInitialized=true)}
        val authRepository=mockk<SimklAuthRepository>{coEvery{synchronizeUserSettings(null)}returns Unit}
        val repository=SimklSyncRepository(engine,storage,authRepository,auth,profiles,StandardTestDispatcher(testScheduler),backgroundScope)
        repository.ensureLoaded();assertEquals(hashA,repository.state.value.snapshot.accountIdentityHash)
        accountScope=SimklAuthScope(1,2);state.value=SimklAuthState(isAuthenticated=true,accountId=456)
        repository.refresh(TrackingRefreshIntent.AUTOMATIC)
        val expected=repository.captureInitializationReadScope()
        repository.hideForeignInitializationProjection(expected)
        val result=repository.awaitInitializationImport(expected)
        assertTrue(result.complete);assertEquals(1,reads);assertEquals(hashB,repository.state.value.snapshot.accountIdentityHash)
        coVerify(exactly=0){authRepository.synchronizeUserSettings(any())}
    }
    private class MemorySnapshot(var payload:String):SimklSyncStorage {
        override suspend fun load(profileId:Int)=payload
        override suspend fun save(profileId:Int,payload:String){this.payload=payload}
        override suspend fun remove(profileId:Int){payload=""}
    }
}
