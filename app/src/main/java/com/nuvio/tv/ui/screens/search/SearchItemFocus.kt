package com.nuvio.tv.ui.screens.search

internal data class SearchItemFocus(val key: String, val index: Int) {
    fun indexIn(keys: List<String>): Int {
        if (keys.isEmpty()) return -1
        if (keys.getOrNull(index) == key) return index
        val currentIndex = keys.indexOf(key)
        return if (currentIndex >= 0) currentIndex else index.coerceIn(keys.indices)
    }
}

internal data class SearchResultFocus(val rowKey: String, val item: SearchItemFocus)
