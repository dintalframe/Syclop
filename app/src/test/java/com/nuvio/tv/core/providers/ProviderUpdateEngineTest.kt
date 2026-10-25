package com.nuvio.tv.core.providers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProviderUpdateEngineTest {
    private class Memory:ProviderUpdatePersistence {
        var values=emptyMap<String,ProviderUpdateStatus>()
        override fun read()=values
        override fun write(states:Map<String,ProviderUpdateStatus>) { values=states }
    }
    @Test fun `successful checks persist and skip until requested24h boundary`()=runTest {
        val memory=Memory();var now=1000L;var calls=0
        val engine=ProviderUpdateEngine(memory,{now}) { calls++;null }
        engine.update(listOf("https://provider.example/manifest"))
        now+=ProviderUpdatePolicy.INTERVAL_MS-1
        ProviderUpdateEngine(memory,{now}) { calls++;null }.update(listOf("https://provider.example/manifest"))
        assertEquals(1,calls)
        now++
        engine.update(listOf("https://provider.example/manifest"))
        assertEquals(2,calls)
        assertEquals(now,memory.values.values.single().lastSuccessMillis)
        assertFalse(memory.values.keys.single().contains("provider"))
    }
    @Test fun `failed check keeps last success and allows bounded later app-start catchup`()=runTest {
        val memory=Memory();var now=1000L;var fail=false;var calls=0
        val engine=ProviderUpdateEngine(memory,{now}) { calls++;if(fail)ProviderUpdateError.NETWORK_OR_INVALID else null }
        engine.update(listOf("https://p.example"));val success=now
        now+=ProviderUpdatePolicy.INTERVAL_MS;fail=true
        engine.update(listOf("https://p.example"));assertEquals(success,memory.values.values.single().lastSuccessMillis)
        now+=ProviderUpdatePolicy.FAILED_RETRY_MS-1;engine.update(listOf("https://p.example"));assertEquals(2,calls)
        now++;engine.update(listOf("https://p.example"));assertEquals(3,calls)
    }
    @Test fun `manual update bypasses clock without duplicating URLs or exceeding concurrency two`()=runTest {
        var active=0;var peak=0;var count=0
        val engine=ProviderUpdateEngine(Memory(),{1000}) { active++;peak=maxOf(peak,active);count++;delay(10);active--;null }
        val urls=(1..5).map { "https://p.example/$it" }
        engine.update(urls+urls,force=true)
        assertEquals(5,count);assertEquals(2,peak)
    }
    @Test fun `one failed provider cannot discard another successful provider status`()=runTest {
        val memory=Memory()
        val engine=ProviderUpdateEngine(memory,{1000}) { if(it.endsWith("bad"))error("dummy failure") else null }
        engine.update(listOf("https://p.example/bad","https://p.example/good"))
        assertEquals(ProviderUpdateError.NETWORK_OR_INVALID,memory.values[ProviderUpdatePolicy.key("https://p.example/bad")]?.error)
        assertEquals(1000L,memory.values[ProviderUpdatePolicy.key("https://p.example/good")]?.lastSuccessMillis)
    }
    @Test fun `job cancellation stops network work and persists safe interrupted status`()=runTest {
        val started=CompletableDeferred<Unit>();val wait=CompletableDeferred<Unit>();val memory=Memory()
        val engine=ProviderUpdateEngine(memory,{1000}) { started.complete(Unit);wait.await();null }
        val work=async { engine.update(listOf("https://p.example")) };started.await();work.cancelAndJoin()
        assertEquals(ProviderUpdateError.CANCELLED,memory.values.values.single().error)
        assertFalse(memory.values.values.single().running)
        assertEquals(0L,memory.values.values.single().lastSuccessMillis)
    }
    @Test fun `clock reversal does not postpone checks forever`() {
        assertTrue(ProviderUpdatePolicy.due(ProviderUpdateStatus(2000,2000),1000))
    }
    @Test fun `batch is bounded and unprocessed providers remain due`()=runTest {
        val memory=Memory();val engine=ProviderUpdateEngine(memory,{1000}) { null }
        engine.update((1..40).map { "https://p.example/$it" })
        assertEquals(30,memory.values.size)
        engine.update((1..40).map { "https://p.example/$it" })
        assertEquals(40,memory.values.size)
    }
    @Test fun `periodic flex check updates instead of missing the whole next daily cycle`()=runTest {
        val memory=Memory();var now=1000L;var calls=0
        val engine=ProviderUpdateEngine(memory,{now}) { calls++;null }
        val urls=listOf("https://p.example")
        engine.update(urls)
        now+=ProviderUpdatePolicy.INTERVAL_MS-ProviderUpdatePolicy.PERIODIC_FLEX_MS-1
        assertFalse(engine.update(urls,periodic=true));assertEquals(1,calls)
        now++
        assertFalse(engine.update(urls,periodic=true));assertEquals(2,calls)
        assertEquals(now,memory.values.values.single().lastSuccessMillis)
    }
    @Test fun `app-start gate stays strict while periodic allowance cannot accelerate failed retry`()=runTest {
        val memory=Memory();var now=1000L;var calls=0;var fail=false
        val engine=ProviderUpdateEngine(memory,{now}) { calls++;if(fail)ProviderUpdateError.NETWORK_OR_INVALID else null }
        val urls=listOf("https://p.example")
        engine.update(urls)
        now+=ProviderUpdatePolicy.INTERVAL_MS-ProviderUpdatePolicy.PERIODIC_FLEX_MS
        engine.update(urls);assertEquals(1,calls)
        fail=true;engine.update(urls,periodic=true);assertEquals(2,calls)
        now+=ProviderUpdatePolicy.FAILED_RETRY_MS-1
        engine.update(urls,periodic=true);engine.update(urls);assertEquals(2,calls)
        now++
        engine.update(urls,periodic=true);assertEquals(3,calls)
        // A failed periodic attempt has not made a successful 24-hour app-start check due yet.
        engine.update(urls);assertEquals(3,calls)
    }
    @Test fun `large background collections prioritize never checked entries at the next periodic cycle`()=runTest {
        val memory=Memory();var now=1000L
        val engine=ProviderUpdateEngine(memory,{now}) { null }
        val urls=(1..40).map { "https://p.example/$it" }
        assertTrue(engine.update(urls,periodic=true));assertEquals(30,memory.values.size)
        now+=ProviderUpdatePolicy.INTERVAL_MS
        engine.update(urls,periodic=true)
        assertEquals(40,memory.values.size)
    }
    @Test fun `restart during an inflight refresh restores interrupted status and one-hour catchup`()=runTest {
        val memory=Memory();var now=1000L
        val urls=listOf("https://p.example")
        ProviderUpdateEngine(memory,{now}) { null }.update(urls)
        now+=ProviderUpdatePolicy.INTERVAL_MS
        val started=CompletableDeferred<Unit>();val waiting=CompletableDeferred<Unit>()
        val old=async { ProviderUpdateEngine(memory,{now}) { started.complete(Unit);waiting.await();null }.update(urls) }
        started.await()
        val restored=Memory().also { it.values=memory.values.toMap() }
        assertEquals(ProviderUpdateError.CANCELLED,restored.values.values.single().error)
        old.cancelAndJoin()
        var calls=0
        val restarted=ProviderUpdateEngine(restored,{now}) { calls++;null }
        restarted.update(urls);assertEquals(0,calls)
        now+=ProviderUpdatePolicy.FAILED_RETRY_MS
        restarted.update(urls);assertEquals(1,calls)
    }

}
