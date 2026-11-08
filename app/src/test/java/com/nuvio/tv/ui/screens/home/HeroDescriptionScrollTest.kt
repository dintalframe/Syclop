package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeroDescriptionScrollTest {
    @Test fun `compact hero keeps whole lines and scrolls far enough to reveal the final line`() {
        val viewport = heroDescriptionViewport(listOf(20f, 40f, 60f, 80f, 100f), 100, 4, 70)
        assertEquals(HeroDescriptionViewport(60, 3), viewport)
        assertEquals(40, requireNotNull(heroDescriptionScrollPlan(100, viewport.heightPx, 1f)).distancePx)
    }

    @Test fun `short descriptions keep natural height and unreadably clipped viewports do not animate`() {
        assertEquals(HeroDescriptionViewport(42, 2), heroDescriptionViewport(listOf(20f, 40f), 42, 4, 100))
        assertEquals(HeroDescriptionViewport(10, 0), heroDescriptionViewport(listOf(20f, 40f), 42, 4, 10))
        assertEquals(HeroDescriptionViewport(0, 0), heroDescriptionViewport(listOf(20f), 20, 4, 0))
    }

    @Test fun `fitting text and invalid layout never start a scroll`() {
        assertNull(heroDescriptionScrollPlan(80, 80, 1f))
        assertNull(heroDescriptionScrollPlan(40, 80, 1f))
        assertNull(heroDescriptionScrollPlan(80, 0, 1f))
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(heroDescriptionScrollPlan(160, 80, density))
        }
    }

    @Test fun `overflow uses the same physical reading speed at different display densities`() {
        val normal = requireNotNull(heroDescriptionScrollPlan(144, 72, 1f))
        val dense = requireNotNull(heroDescriptionScrollPlan(288, 144, 2f))
        assertEquals(72, normal.distancePx)
        assertEquals(144, dense.distancePx)
        assertEquals(6_000, normal.durationMillis)
        assertEquals(normal.durationMillis, dense.durationMillis)
    }

    @Test fun `disabled or invalid motion stays stationary`() {
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) assertFalse(heroDescriptionMotionEnabled(scale))
        assertTrue(heroDescriptionMotionEnabled(1f))
        assertTrue(heroDescriptionMotionEnabled(2f))
    }

    @Test fun `scroll waits for a settled selection and holds the last lines before resetting`() = runTest {
        val events = mutableListOf<String>()
        val job = backgroundScope.launch {
            runHeroDescriptionScroll(HeroDescriptionScrollPlan(12, 1_000), 4_000,
                { if (it) events += "moving" }, { events += "top" }) {
                events += "start"
                delay(it.durationMillis.toLong())
                events += "end"
            }
        }
        runCurrent()
        assertEquals(listOf("top"), events)
        advanceTimeBy(3_999); runCurrent()
        assertEquals(listOf("top"), events)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("top", "moving", "start"), events)
        advanceTimeBy(1_000); runCurrent()
        assertEquals("end", events.last())
        advanceTimeBy(3_999); runCurrent()
        assertEquals("end", events.last())
        advanceTimeBy(1); runCurrent()
        assertEquals("top", events.last())
        job.cancel()
    }

    @Test fun `accessibility reading timeout extends the stationary preview`() = runTest {
        var starts = 0
        val job = backgroundScope.launch {
            runHeroDescriptionScroll(HeroDescriptionScrollPlan(12, 1_000), 10_000, {}, {}) {
                starts++
                awaitCancellation()
            }
        }
        runCurrent(); advanceTimeBy(9_999); runCurrent()
        assertEquals(0, starts)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, starts)
        job.cancel()
    }

    @Test fun `losing selection before dwell cancels all future motion`() = runTest {
        var starts = 0
        val job = backgroundScope.launch {
            runHeroDescriptionScroll(HeroDescriptionScrollPlan(12, 1_000), 4_000, {}, {}) { starts++ }
        }
        runCurrent(); advanceTimeBy(2_000)
        job.cancel(); runCurrent(); advanceTimeBy(20_000); runCurrent()
        assertEquals(0, starts)
    }

    @Test fun `hiding a moving hero cancels its animation and restores stationary text`() = runTest {
        var moving = false
        var animationCancelled = false
        var starts = 0
        val job = backgroundScope.launch {
            runHeroDescriptionScroll(HeroDescriptionScrollPlan(12, 1_000), 4_000, { moving = it }, {}) {
                starts++
                try { awaitCancellation() } finally { animationCancelled = true }
            }
        }
        runCurrent(); advanceTimeBy(4_000); runCurrent()
        assertTrue(moving)
        job.cancel(); runCurrent(); advanceTimeBy(20_000); runCurrent()
        assertTrue(animationCancelled)
        assertFalse(moving)
        assertEquals(1, starts)
    }
}
