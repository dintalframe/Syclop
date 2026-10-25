package com.nuvio.tv.core.initialization
import com.nuvio.tv.data.mal.*
import java.util.concurrent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
class MalLoadedInitializationSnapshotTest {
    @Test fun `scoped transient disconnected load cannot be observed as a finished logout`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val values=ConcurrentHashMap<String,String>()
        values["account.p2"]="""{"username":"synthetic-viewer","access_token":"synthetic-access","refresh_token":"synthetic-refresh","expires_at":9000}"""
        val backend=object:MalAccountPersistence {
            override fun read(key:String):String? { if(key=="account.p2"){entered.countDown();check(release.await(5,TimeUnit.SECONDS))};return values[key] }
            override fun write(values:Map<String,String?>)=Unit
            override fun clear()=Unit
        }
        val profiles=MutableStateFlow(1);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val store=MalAuthStore(backend,profiles,scope,MalConfiguration(),initialProfileId=1,diagnose={_,_->})
        val executor=Executors.newSingleThreadExecutor()
        try {
            profiles.value=2;assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertFalse(store.state.value.connected) // The deliberate intermediate clear is visible only on raw flow.
            val started=CountDownLatch(1)
            val snapshot=executor.submit<MalAccountState>{started.countDown();store.initializationSnapshot()}
            assertTrue(started.await(5,TimeUnit.SECONDS));assertFalse(snapshot.isDone)
            release.countDown();val loaded=snapshot.get(5,TimeUnit.SECONDS)
            assertTrue(loaded.connected);assertEquals(2,loaded.scope.profileId);assertEquals("synthetic-viewer",loaded.username)
        } finally {release.countDown();scope.cancel();executor.shutdownNow()}
    }
}
