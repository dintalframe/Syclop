package com.nuvio.tv.core.initialization
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
@OptIn(ExperimentalCoroutinesApi::class)
class InitializationReadGateTest {
    @Test fun `concurrent background and awaited reads share one actual successful batch`()=runTest {
        val gate=InitializationReadGate(); val scope=owner(); var calls=0; val release=CompletableDeferred<Unit>()
        val first=async(start=CoroutineStart.UNDISPATCHED){gate.run(scope){calls++;release.await();InitializationReadResult(4)}}
        val next=async(start=CoroutineStart.UNDISPATCHED){gate.run(scope){calls++;InitializationReadResult(5)}}
        release.complete(Unit);assertEquals(4,first.await().count);assertEquals(4,next.await().count);assertEquals(1,calls)
        assertEquals(4,gate.recentComplete(scope)?.count)
    }
    @Test fun `failed concurrent read shares failure but a later retry can run`()=runTest {
        val gate=InitializationReadGate();val scope=owner();var calls=0;val release=CompletableDeferred<Unit>()
        supervisorScope {
            val first=async(start=CoroutineStart.UNDISPATCHED){runCatching {gate.run(scope){calls++;release.await();throw InitializationReadException(InitializationFailure.NETWORK)}}}
            val next=async(start=CoroutineStart.UNDISPATCHED){runCatching {gate.run(scope){calls++;InitializationReadResult()}}}
            release.complete(Unit);assertTrue(first.await().isFailure);assertTrue(next.await().isFailure)
        }
        assertEquals(1,calls);assertNull(gate.recentComplete(scope));gate.run(scope){calls++;InitializationReadResult(2)};assertEquals(2,calls)
    }
    @Test fun `stale queued owner never inherits another account result`()=runTest {
        val gate=InitializationReadGate();var current=true;val scope=owner(current={current});val release=CompletableDeferred<Unit>()
        val first=launch {try {gate.run(scope){release.await();InitializationReadResult(9)}}catch(_:InitializationReadException){}}
        runCurrent();current=false;release.complete(Unit);first.join()
        assertNull(gate.recentComplete(owner("different")))
    }
    @Test fun `no-op background cycle is never a recent complete import`()=runTest {
        val gate=InitializationReadGate();val scope=owner();gate.run(scope){InitializationReadResult(complete=false)}
        assertNull(gate.recentComplete(scope))
    }
    @Test fun `cancellation is not a reusable successful completion`()=runTest {
        val gate=InitializationReadGate();val scope=owner();val job=launch{gate.run(scope){awaitCancellation()}};runCurrent();job.cancelAndJoin()
        assertNull(gate.recentComplete(scope));assertEquals(1,gate.run(scope){InitializationReadResult(1)}.count)
    }
    @Test fun `recent startup join shares failed background outcome without an immediate hidden retry`()=runTest {
        val gate=InitializationReadGate();val scope=owner();var calls=0;val release=CompletableDeferred<Unit>()
        supervisorScope {
            val first=async(start=CoroutineStart.UNDISPATCHED){runCatching{gate.run(scope){calls++;release.await();throw InitializationReadException(InitializationFailure.NETWORK)}}}
            val joined=async(start=CoroutineStart.UNDISPATCHED){runCatching{gate.recentComplete(scope)?:gate.run(scope){calls++;InitializationReadResult()}}}
            release.complete(Unit);assertTrue(first.await().isFailure);assertTrue(joined.await().isFailure)
        }
        assertEquals(1,calls);assertNull(gate.recentComplete(scope))
        gate.run(scope){calls++;InitializationReadResult(2)};assertEquals(2,calls)
    }
    private fun owner(key:String="owner",current:()->Boolean={true})=InitializationReadScope(InitializationProvider.TRAKT,key,current)
}
