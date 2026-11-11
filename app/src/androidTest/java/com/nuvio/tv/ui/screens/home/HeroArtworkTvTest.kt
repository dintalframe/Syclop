package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.screens.detail.HeroContentSection
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.asStable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeroArtworkTvTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedLogoKeepsMetadataInPlaceWithLargerText() = withBlockedLogo { logo, releaseLogo, _ ->
        val title = "A quiet river through the old forest"
        val synopsis = "A small canoe arrives at the riverbank."
        val preview = HeroPreview(title, logo, synopsis, null, yearText = "2026", imdbText = null,
            genres = emptyList<String>().asStable(), poster = null, backdrop = null, imageUrl = null)

        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                NuvioTheme {
                    Box(Modifier.width(440.dp)) {
                        HeroTitleBlock(itemKeyProvider = { "fixture" }, previewProvider = { preview },
                            portraitMode = false, showImdbRatings = false)
                    }
                }
            }
        }
        val metadata = compose.onNodeWithText(synopsis)
        val before = metadata.fetchSemanticsNode().boundsInRoot
        assertEquals(1, titleMaxLines(title))
        releaseLogo.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) { titleMaxLines(title) == 2 }
        assertEquals(before, metadata.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun detailsShowsTitleWhileLogoLoadsAndKeepsPlayFocusedOnError() = withBlockedLogo { logo, releaseLogo, failed ->
        val title = "A quiet river through the old forest"
        val action = "Play fixture"
        val meta = Meta(id = "tt100001", type = ContentType.MOVIE, name = title, poster = null,
            posterShape = PosterShape.POSTER, background = null, logo = logo, description = null,
            releaseInfo = "2026", imdbRating = null, genres = emptyList(), runtime = null,
            director = emptyList(), cast = emptyList(), videos = emptyList(), country = null,
            awards = null, language = null, links = emptyList())
        var plays = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                NuvioTheme {
                    HeroContentSection(meta, nextEpisode = null,
                        nextToWatch = NextToWatch(null, false, meta.id, null, null, action),
                        onPlayClick = { plays++ }, isInLibrary = false, onToggleLibrary = {},
                        onLibraryLongPress = {}, isMovieWatched = false, isMovieWatchedPending = false,
                        onToggleMovieWatched = {})
                }
            }
        }
        val play = compose.onNodeWithText(action)
        play.performSemanticsAction(SemanticsActions.RequestFocus)
        play.assertIsFocused()
        val before = play.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(title).assertIsDisplayed()
        releaseLogo.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) { failed.get() }
        compose.onNodeWithText(title).assertIsDisplayed()
        assertEquals(before, play.fetchSemanticsNode().boundsInRoot)
        play.assertIsFocused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(1, plays) }
    }

    @OptIn(DelicateCoilApi::class)
    private fun withBlockedLogo(test: (String, CompletableDeferred<Unit>, AtomicBoolean) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = SingletonImageLoader.get(context)
        val releaseLogo = CompletableDeferred<Unit>()
        val failed = AtomicBoolean(false)
        val logo = "https://artwork.invalid/test-logo.png"
        val imageLoader = ImageLoader.Builder(context).components {
            add(Interceptor { chain ->
                if (chain.request.data == logo) {
                    releaseLogo.await()
                    failed.set(true)
                    ErrorResult(null, chain.request, IOException("Fixture logo unavailable"))
                } else chain.proceed()
            })
        }.build()
        SingletonImageLoader.setUnsafe(imageLoader)
        try {
            test(logo, releaseLogo, failed)
        } finally {
            releaseLogo.complete(Unit)
            SingletonImageLoader.setUnsafe(previous)
            imageLoader.shutdown()
        }
    }

    private fun titleMaxLines(title: String): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.maxLines
    }
}
