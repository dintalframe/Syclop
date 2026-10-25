package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HomeBrowseFocusTransferTest {
    @Test fun `restored card is clamped when a catalog shrinks`() {
        assertEquals(0, HomeBrowseFocusTransfer.cardIndex(null, 12))
        assertEquals(7, HomeBrowseFocusTransfer.cardIndex(7, 12))
        assertEquals(2, HomeBrowseFocusTransfer.cardIndex(7, 3))
        assertEquals(0, HomeBrowseFocusTransfer.cardIndex(-4, 3))
    }

    @Test fun `Down waits for both lazy axes before requesting the intended card`() = runTest {
        var prepared = false
        var frames = 0
        var requests = 0
        val result = HomeBrowseFocusTransfer.transfer({ true }, { prepared = true }, { frames++ },
            { assertTrue(prepared); frames >= 3 }, { requests++; true }, { true })
        assertEquals(HomeBrowseFocusTransfer.Result.FOCUSED, result)
        assertEquals(1, requests)
        assertEquals(4, frames)
    }

    @Test fun `detached leaf and rejected requests retry instead of consuming Down without focus`() = runTest {
        var requests = 0
        val result = HomeBrowseFocusTransfer.transfer({ true }, {}, {}, { true }, {
            when (++requests) { 1 -> throw IllegalStateException("not attached"); 2 -> false; else -> true }
        }, { true })
        assertEquals(HomeBrowseFocusTransfer.Result.FOCUSED, result)
        assertEquals(3, requests)
    }

    @Test fun `accepted request without actual child focus is not success and retries are bounded`() = runTest {
        var requests = 0
        val result = HomeBrowseFocusTransfer.transfer({ true }, {}, {}, { true }, { requests++; true }, { false })
        assertEquals(HomeBrowseFocusTransfer.Result.UNAVAILABLE, result)
        assertEquals(12, requests)
    }

    @Test fun `profile or category change after layout prevents focus transfer`() = runTest {
        var current = true
        var requests = 0
        val result = HomeBrowseFocusTransfer.transfer({ current }, {}, { current = false }, { true },
            { requests++; true }, { true })
        assertEquals(HomeBrowseFocusTransfer.Result.STALE, result)
        assertEquals(0, requests)
    }

    @Test fun `profile change after accepted focus cannot publish a successful transfer`() = runTest {
        var current = true
        val result = HomeBrowseFocusTransfer.transfer({ current }, {}, {}, { true }, { current = false; true }, { true })
        assertEquals(HomeBrowseFocusTransfer.Result.STALE, result)
    }

    @Test fun `uncomposed row times out without any leaf request`() = runTest {
        var frames = 0
        var requests = 0
        val result = HomeBrowseFocusTransfer.transfer({ true }, {}, { frames++ }, { false }, { requests++; true }, { false })
        assertEquals(HomeBrowseFocusTransfer.Result.UNAVAILABLE, result)
        assertEquals(12, frames)
        assertEquals(0, requests)
    }

    @Test fun `row removed before its first layout cannot leave entry moving forever`() = runTest {
        var requests = 0
        val result = HomeBrowseFocusTransfer.transfer({ true }, { awaitCancellation() }, {}, { true },
            { requests++; true }, { true })
        assertEquals(HomeBrowseFocusTransfer.Result.UNAVAILABLE, result)
        assertEquals(0, requests)
    }

    @Test fun `leaving the screen cancels preparation without a late request`() = runTest {
        var requests = 0
        try {
            HomeBrowseFocusTransfer.transfer({ true }, { throw CancellationException("screen left") }, {}, { true },
                { requests++; true }, { true })
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(0, requests)
    }
}
