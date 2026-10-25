package com.nuvio.tv.core.mediafacts

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class SelectedSourceProbeWorkerTest {
    @Test fun `noninterruptible native work has bounded caller return and cannot spawn a parallel probe`()=runBlocking {
        val worker=SelectedSourceProbeWorker();val release=AtomicBoolean(false);val started=CountDownLatch(1)
        val cancellations=AtomicInteger(0);val secondStarted=AtomicBoolean(false)
        val first=async { worker.run(500L,cancel={cancellations.incrementAndGet()}) {
            started.countDown()
            while(!release.get()) { try { Thread.sleep(10) } catch(_:InterruptedException) {} }
            "late"
        } }
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(2,TimeUnit.SECONDS) })
            assertNull(first.await());assertTrue(cancellations.get()>0)
            assertNull(worker.run(300L,cancel={cancellations.incrementAndGet()}) { secondStarted.set(true);"second" })
            assertFalse(secondStarted.get())
        } finally { release.set(true) }
        assertEquals("next",worker.run(2000L,cancel={}) { "next" })
    }
    @Test fun `native exceptions become unknown instead of exposing request or parser payload`()=runBlocking {
        val worker=SelectedSourceProbeWorker()
        assertNull(worker.run<String>(2000L,cancel={}) { throw IllegalStateException("private synthetic request") })
        assertEquals("ready",worker.run(2000L,cancel={}) { "ready" })
    }
}
