package com.nuvio.tv.core.initialization

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirstUseInitializationTest {
    @Test fun `success is marked only after awaited reads and preserves real counts and limits`() = runTest {
        val markers = MemoryMarkers(); val gate = FirstUseInitialization(markers)
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val p = Participant { entered.complete(Unit); release.await(); InitializationReadResult(17, truncated=true) }
        gate.reset(1,0); val job = launch { gate.run(1,0,listOf(p)) { true } }; entered.await()
        assertEquals(InitializationPhase.IMPORTING,gate.state.value.phase); assertTrue(markers.keys.isEmpty())
        release.complete(Unit); job.join()
        assertEquals(InitializationPhase.READY,gate.state.value.phase)
        assertEquals(17,gate.state.value.stages.single().count); assertTrue(gate.state.value.stages.single().truncated)
        assertEquals(1,markers.keys.size)
    }
    @Test fun `failed stage is unmarked and retry runs only the failed blocking import`() = runTest {
        val markers=MemoryMarkers(); val gate=FirstUseInitialization(markers); var good=0; var attempts=0
        val one=Participant(InitializationProvider.TRAKT) { good++; InitializationReadResult(3) }
        val two=Participant(InitializationProvider.MAL) { if(attempts++==0) error("synthetic private response") else InitializationReadResult(2) }
        gate.reset(1,0); gate.run(1,0,listOf(one,two)){true}
        assertEquals(InitializationPhase.NEEDS_ATTENTION,gate.state.value.phase); assertEquals(1,markers.keys.size)
        gate.reset(1,0); gate.run(1,0,listOf(one,two)){true}
        assertEquals(InitializationPhase.READY,gate.state.value.phase); assertEquals(1,good); assertEquals(2,attempts)
        assertFalse(gate.state.value.toString().contains("private response"))
    }
    @Test fun `read timeout is failure not success or completion marker`() = runTest {
        val markers=MemoryMarkers(); val gate=FirstUseInitialization(markers,100)
        gate.reset(1,0); gate.run(1,0,listOf(Participant { delay(101); InitializationReadResult(99) })){true}
        assertEquals(InitializationFailure.TIMEOUT,gate.state.value.stages.single().failure); assertTrue(markers.keys.isEmpty())
    }
    @Test fun `capture has a finite deadline too`() = runTest {
        val markers=MemoryMarkers(); val gate=FirstUseInitialization(markers,100)
        val participant=object:InitializationParticipant {
            override val provider=InitializationProvider.TRAKT; override val accountChanges=flowOf("test")
            override suspend fun capture(profileId:Int,selectionRevision:Long):InitializationSession? { delay(101); return null }
        }
        gate.reset(1,0); gate.run(1,0,listOf(participant)){true}
        assertEquals(InitializationFailure.TIMEOUT,gate.state.value.stages.single().failure); assertTrue(markers.keys.isEmpty())
    }
    @Test fun `caller cancellation propagates without success marker`() = runTest {
        val markers=MemoryMarkers(); val gate=FirstUseInitialization(markers); val entered=CompletableDeferred<Unit>()
        gate.reset(1,0); val job=launch { gate.run(1,0,listOf(Participant { entered.complete(Unit); awaitCancellation() })){true} }
        entered.await(); job.cancelAndJoin(); assertTrue(markers.keys.isEmpty())
    }
    @Test fun `account or profile loss after read cannot commit marker`() = runTest {
        val markers=MemoryMarkers(); val gate=FirstUseInitialization(markers); var current=true
        val p=Participant(current={current}) { current=false; InitializationReadResult(6) }
        gate.reset(1,0)
        try { gate.run(1,0,listOf(p)){true}; fail("stale read accepted") } catch(e:InitializationReadException) { assertEquals(InitializationFailure.ACCOUNT_CHANGED,e.reason) }
        assertTrue(markers.keys.isEmpty())
    }
    @Test fun `Continue remains authoritative after a noncooperative old response`() = runTest {
        val markers=MemoryMarkers(); val gate=FirstUseInitialization(markers); val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val p=Participant { entered.complete(Unit); withContext(NonCancellable){ release.await() }; InitializationReadResult(4) }
        gate.reset(1,0); val job=launch { try {gate.run(1,0,listOf(p)){true}}catch(_:InitializationReadException){} }
        entered.await(); gate.continueWithSavedData(); release.complete(Unit); job.join()
        assertEquals(InitializationPhase.CONTINUED,gate.state.value.phase); assertTrue(markers.keys.isEmpty())
    }
    @Test fun `prior durable completion unblocks Home before RAM warmup and does not pretend offline history`() = runTest {
        val markers=MemoryMarkers(); val p=Participant { delay(10_000); InitializationReadResult(5) }
        markers.keys+=p.marker.stored; val gate=FirstUseInitialization(markers); gate.reset(1,0)
        val job=launch { gate.run(1,0,listOf(p)){true} }; runCurrent()
        assertEquals(InitializationPhase.READY,gate.state.value.phase); assertNull(gate.state.value.stages.single().count)
        assertTrue(job.isActive); job.cancelAndJoin(); assertEquals(1,markers.keys.size)
    }
    @Test fun `incomplete read does not produce a completion marker`() = runTest {
        val markers=MemoryMarkers(); val gate=FirstUseInitialization(markers); gate.reset(1,0)
        gate.run(1,0,listOf(Participant { InitializationReadResult(4,complete=false) })){true}
        assertEquals(InitializationFailure.INCOMPLETE,gate.state.value.stages.single().failure); assertTrue(markers.keys.isEmpty())
    }
    private class MemoryMarkers:InitializationMarkerStore {
        val keys=mutableSetOf<String>()
        override suspend fun contains(key:InitializationMarkerKey)=key.stored in keys
        override suspend fun complete(key:InitializationMarkerKey,current:()->Boolean):Boolean { if(!current())return false; keys+=key.stored;return true }
        override suspend fun invalidateProvider(profileId:Int,provider:InitializationProvider,current:()->Boolean) { if(current())keys.removeAll { it.startsWith("v1:${provider.name}:") } }
    }
    private class Participant(override val provider:InitializationProvider=InitializationProvider.TRAKT,
        private val current:()->Boolean={true},private val read:suspend()->InitializationReadResult):InitializationParticipant {
        val marker=InitializationMarkerKey.fromAccount(1,provider,"synthetic-user")!!
        override val accountChanges=flowOf("synthetic")
        override suspend fun capture(profileId:Int,selectionRevision:Long)=object:InitializationSession {
            override val marker=this@Participant.marker
            override val scope=InitializationReadScope(provider,"synthetic",current)
            override suspend fun read(progress:(InitializationStep)->Unit):InitializationReadResult { progress(InitializationStep.HISTORY); return this@Participant.read() }
        }
    }
}
