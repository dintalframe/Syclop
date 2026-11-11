package com.nuvio.tv.ui.screens.library

import android.view.KeyEvent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTvMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class LibrarySavedPosterFocusTvTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoresReorderedOffscreenPosterAndNearestSurvivorFromScrolledGrid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = (0..59).map { "Poster $it" }
        val selected = original[3]
        val reordered = original.toMutableList().apply { remove(selected); add(33, selected) }
        val posters = mutableStateOf(reordered.toList())
        val request = mutableStateOf<RestoreRequest?>(null)
        val restored = AtomicReference<SavedLibraryPosterTarget?>()
        val activated = mutableListOf<String>()
        compose.setContent {
            NuvioTheme {
                val keys = posters.value
                val pending = request.value
                val requesters = remember(keys) { keys.associateWith { FocusRequester() } }
                val grid = rememberLazyGridState(initialFirstVisibleItemIndex = 6)
                val showActions = pending?.showActions == true
                LaunchedEffect(pending, keys) {
                    if (pending == null) return@LaunchedEffect
                    val target = savedLibraryPosterTarget(keys, pending.key, pending.index, showActions)
                        ?: return@LaunchedEffect
                    if (restoreSavedLibraryPoster(grid, target, requesters.getValue(target.key))) {
                        restored.set(target)
                    }
                }
                LazyVerticalGrid(columns = GridCells.Fixed(3), state = grid, modifier = Modifier.height(230.dp)) {
                    repeat(if (showActions) 4 else 3) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(36.dp)) }
                    }
                    items(keys, key = { it }) { key ->
                        Button(onClick = { activated += key }, modifier = Modifier.focusRequester(requesters.getValue(key))) {
                            Text(key)
                        }
                    }
                }
            }
        }

        compose.runOnIdle { request.value = RestoreRequest(selected, 3, showActions = false) }
        compose.waitUntil(timeoutMillis = 5_000) { restored.get()?.key == selected }
        assertEquals(36, restored.get()?.lazyIndex)
        compose.onNodeWithText(selected).assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)

        val surviving = reordered.filterNot { it == selected }
        val neighbor = surviving[3]
        compose.runOnIdle {
            posters.value = surviving
            request.value = RestoreRequest(selected, 3, showActions = true)
        }
        compose.waitUntil(timeoutMillis = 5_000) { restored.get()?.key == neighbor }
        assertEquals(7, restored.get()?.lazyIndex)
        compose.onNodeWithText(neighbor).assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(listOf(selected, neighbor), activated) }
    }

    private data class RestoreRequest(val key: String, val index: Int, val showActions: Boolean)
}
