package com.nuvio.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSelectionRestorePolicyTest {
    @Test
    fun `manual player return from stream arms restoration`() {
        assertTrue(
            shouldArmSourceSelectionRestore(
                autoPlayNavigation = false,
                previousRoute = "stream/{videoId}/{contentType}/{title}"
            )
        )
    }

    @Test
    fun `autoplay and non-stream returns do not arm restoration`() {
        assertFalse(
            shouldArmSourceSelectionRestore(
                autoPlayNavigation = true,
                previousRoute = "stream/{videoId}/{contentType}/{title}"
            )
        )
        assertFalse(
            shouldArmSourceSelectionRestore(
                autoPlayNavigation = false,
                previousRoute = "detail/{itemId}/{itemType}"
            )
        )
        assertFalse(
            shouldArmSourceSelectionRestore(
                autoPlayNavigation = false,
                previousRoute = null
            )
        )
    }

    @Test
    fun `restore target is clamped to the current stream list`() {
        val rows = List(10) { "row|$it" }
        assertEquals(0, sourceSelectionRestoreTarget(-1, null, rows))
        assertEquals(4, sourceSelectionRestoreTarget(4, null, rows))
        assertEquals(9, sourceSelectionRestoreTarget(40, "removed|0", rows))
        assertNull(sourceSelectionRestoreTarget(4, "removed|0", emptyList()))
    }

    @Test
    fun `return restores the chosen duplicate occurrence after a same size reorder`() {
        val selectedOccurrence = "duplicate|1"
        val previousRows = listOf("duplicate|0", selectedOccurrence, "other|0")
        val currentRows = listOf("duplicate|0", "other|0", selectedOccurrence)

        val restoredIndex = sourceSelectionRestoreTarget(
            previousRows.indexOf(selectedOccurrence), selectedOccurrence, currentRows
        )

        assertEquals(selectedOccurrence, restoredIndex?.let(currentRows::get))
    }
}
