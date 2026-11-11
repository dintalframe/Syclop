package com.nuvio.tv.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchItemFocusTest {
    @Test
    fun `return follows the exact occurrence when results move`() {
        val previous = SearchItemFocus("movie:title#1", 1)

        assertEquals(3, previous.indexIn(listOf("movie:other", "movie:title", "series:title", "movie:title#1")))
    }

    @Test
    fun `return uses the old position when the focused result is removed`() {
        val previous = SearchItemFocus("movie:title", 1)

        assertEquals(1, previous.indexIn(listOf("movie:left", "movie:right")))
    }

    @Test
    fun `return uses the last result when the row shrinks past the old position`() {
        val previous = SearchItemFocus("movie:title", 3)

        assertEquals(0, previous.indexIn(listOf("movie:remaining")))
    }

    @Test
    fun `empty results leave focus to the caller's header`() {
        assertEquals(-1, SearchItemFocus("movie:title", 0).indexIn(emptyList()))
    }
}
