package com.nuvio.tv.ui.screens.ambient

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AmbientCriticalAuthLeaseTest {
    @Test fun `active auth hides ambient and rejects a timer already ready to fire`() {
        var now = 1_000L
        val controller = AmbientIdleController { now }
        controller.show(); assertTrue(controller.isShowing.value)
        now = 2_000L
        val lease = controller.acquireCriticalAuthLease()
        assertTrue(controller.criticalAuthPaused.value)
        assertFalse(controller.isShowing.value)
        assertEquals(2_000L, controller.lastInteractionAt.value)
        controller.show()
        assertFalse(controller.isShowing.value)
        lease.close()
    }

    @Test fun `final release restarts the complete idle period at current monotonic time`() {
        var now = 10L
        val controller = AmbientIdleController { now }
        val lease = controller.acquireCriticalAuthLease()
        now = 300_010L
        lease.close()
        assertFalse(controller.criticalAuthPaused.value)
        assertEquals(300_010L, controller.lastInteractionAt.value)
        assertFalse(controller.isShowing.value)
        controller.show(); assertTrue(controller.isShowing.value)
    }

    @Test fun `nested scopes resume only after their final owner closes`() {
        val transitions = mutableListOf<Boolean>()
        val pauses = AmbientAuthPauseLeases { transitions += it }
        val outer = pauses.acquire(); val inner = pauses.acquire()
        outer.close()
        assertTrue(pauses.isPaused.value)
        assertEquals(listOf(true), transitions)
        inner.close()
        assertFalse(pauses.isPaused.value)
        assertEquals(listOf(true, false), transitions)
    }

    @Test fun `duplicate and stale disposal cannot release another authorization scope`() {
        val pauses = AmbientAuthPauseLeases {}
        val old = pauses.acquire(); old.close()
        val fresh = pauses.acquire()
        old.close(); old.close()
        assertTrue(pauses.isPaused.value)
        fresh.close(); fresh.close()
        assertFalse(pauses.isPaused.value)
    }

    @Test fun `cancellation finally releases lease without leaving ambient paused`() {
        val pauses = AmbientAuthPauseLeases {}
        try {
            val lease = pauses.acquire()
            try { throw java.util.concurrent.CancellationException() } finally { lease.close() }
        } catch (_: java.util.concurrent.CancellationException) { }
        assertFalse(pauses.isPaused.value)
        assertTrue(pauses.runUnlessPaused {})
    }

    @Test fun `remote interactions do not release a pending auth pause`() {
        var now = 1L
        val controller = AmbientIdleController { now }
        val lease = controller.acquireCriticalAuthLease()
        now = 99L; controller.onInteraction()
        assertEquals(99L, controller.lastInteractionAt.value)
        assertTrue(controller.criticalAuthPaused.value)
        controller.show(); assertFalse(controller.isShowing.value)
        lease.close()
    }

    @Test fun `timer firing and acquiring a lease are serialized`() {
        val enteredTimer = CountDownLatch(1); val finishTimer = CountDownLatch(1)
        val acquired = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val pauses = AmbientAuthPauseLeases {}
        try {
            val timer = executor.submit { pauses.runUnlessPaused { enteredTimer.countDown(); assertTrue(finishTimer.await(5, TimeUnit.SECONDS)) } }
            assertTrue(enteredTimer.await(5, TimeUnit.SECONDS))
            val owner = executor.submit<AutoCloseable> { pauses.acquire().also { acquired.countDown() } }
            assertFalse(acquired.await(50, TimeUnit.MILLISECONDS))
            finishTimer.countDown(); timer.get(5, TimeUnit.SECONDS)
            val lease = owner.get(5, TimeUnit.SECONDS)
            assertFalse(pauses.runUnlessPaused { fail("A timer must not run while auth is pending") })
            lease.close()
        } finally { finishTimer.countDown(); executor.shutdownNow() }
    }

    @Test fun `concurrent nested leases survive duplicate closes and notify once per transition`() {
        val paused = AtomicInteger(); val resumed = AtomicInteger()
        val pauses = AmbientAuthPauseLeases { if (it) paused.incrementAndGet() else resumed.incrementAndGet() }
        val executor = Executors.newFixedThreadPool(8)
        val sentinel = pauses.acquire()
        try {
            val leases = (1..64).map { executor.submit<AutoCloseable> { pauses.acquire() } }.map { it.get(5, TimeUnit.SECONDS) }
            leases.map { lease -> executor.submit { lease.close(); lease.close() } }.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(pauses.isPaused.value)
            assertEquals(1, paused.get()); assertEquals(0, resumed.get())
            sentinel.close()
            assertFalse(pauses.isPaused.value)
            assertEquals(1, paused.get()); assertEquals(1, resumed.get())
        } finally { sentinel.close(); executor.shutdownNow() }
    }
}
