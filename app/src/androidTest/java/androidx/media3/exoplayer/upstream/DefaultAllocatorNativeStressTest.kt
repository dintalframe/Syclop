package androidx.media3.exoplayer.upstream

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DefaultAllocatorNativeStressTest {

    @Test
    fun testConcurrentAllocationAndRelease() {
        val numThreads = 8
        val allocationsPerThread = 500
        val allocationSize = 16384 // 16 KB

        val executor = Executors.newFixedThreadPool(numThreads)

        val workers = List(numThreads) {
            Callable {
                for (i in 0 until allocationsPerThread) {
                    val allocation = DefaultAllocatorNative.createAllocation(allocationSize)
                    assertNotNull("Stress allocation should succeed", allocation)
                    assertNotNull("Allocation buffer should not be null", allocation!!.buffer)
                    assertTrue("Allocation buffer must be direct", allocation.buffer!!.isDirect)
                    assertEquals("Buffer capacity should match size", allocationSize, allocation.buffer!!.capacity())
                    assertTrue("Allocation handle must be non-zero", allocation.nativeHandle != 0L)
                    
                    // Immediately free it
                    DefaultAllocatorNative.freeAllocation(allocation)
                    assertEquals("Handle must be cleared to 0 immediately", 0L, allocation.nativeHandle)
                }
            }
        }

        try {
            executor.invokeAll(workers, 30, TimeUnit.SECONDS).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        // Wait an additional 3.5 seconds to ensure all queued background native deallocations 
        // (which are scheduled with a 2-second delay) execute and complete successfully without crashing.
        Thread.sleep(3500)
    }

    @Test
    fun testConcurrentDefaultAllocatorLateRelease() {
        val numThreads = 8
        val allocationsPerThread = 200
        val segmentSize = 65536
        val allocator = DefaultAllocator(true, segmentSize, 0, true)
        val poolSize = numThreads * allocationsPerThread * segmentSize
        allocator.setTargetBufferSize(poolSize)
        val reset = CyclicBarrier(numThreads) { allocator.reset() }

        val executor = Executors.newFixedThreadPool(numThreads)

        val workers = List(numThreads) {
            Callable {
                val threadAllocations = mutableListOf<Allocation>()
                for (i in 0 until allocationsPerThread) {
                    threadAllocations.add(allocator.allocate())
                }
                // Keep all allocations live until the player resets its buffer target.
                reset.await(20, TimeUnit.SECONDS)
                threadAllocations.forEach { allocator.release(it) }
            }
        }

        try {
            executor.invokeAll(workers, 30, TimeUnit.SECONDS).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(0, allocator.totalBytesAllocated)
        assertEquals(poolSize, allocator.availableBytes)
        allocator.trim()
        assertEquals(0, allocator.memoryFootprint)
    }
}
