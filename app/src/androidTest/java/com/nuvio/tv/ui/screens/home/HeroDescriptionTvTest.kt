package com.nuvio.tv.ui.screens.home

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production viewport, drawing, semantic text and motion effect; no account or network. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HeroDescriptionTvTest {
    private class TestMotionScale : MotionDurationScale {
        val factor = mutableFloatStateOf(1f)
        override val scaleFactor: Float get() = factor.floatValue
    }
    private val motion = TestMotionScale()
    @get:Rule val compose = createComposeRule(effectContext = motion)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val description = mutableStateOf((1..12).joinToString("\n") { "Line %02d: A new part of the story.".format(it) })
    private val offscreen = mutableStateOf(false)

    @Test fun overflowScrollsAfterDwellWithinItsFixedViewport() {
        show()
        val top = pixels()
        val bounds = compose.onNodeWithTag(TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height > 0f)
        compose.onNodeWithText(description.value).assertIsDisplayed()
        capture("home-description-overflow-top")
        advance(3_000)
        assertArrayEquals("The first lines must remain still during the reading dwell", top, pixels())
        advance(3_000)
        assertFalse("The real Text drawing must move through overflow", top.contentEquals(pixels()))
        assertEquals(bounds, compose.onNodeWithTag(TAG).fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText(description.value).assertIsDisplayed()
        capture("home-description-overflow-scrolled")
    }

    @Test fun selectionChangesAndOffscreenVisibilityResetTheReadingPosition() {
        show()
        val top = pixels()
        advance(6_000)
        assertFalse(top.contentEquals(pixels()))
        compose.runOnIdle { offscreen.value = true }
        advance(100)
        compose.onNodeWithTag(TAG).assertIsNotDisplayed()
        advance(12_000)
        compose.runOnIdle { offscreen.value = false }
        advance(100)
        assertArrayEquals("A returning hero starts at its first lines", top, pixels())
        compose.runOnIdle { description.value = description.value.replace("Line", "Scene") }
        advance(100)
        val replacement = pixels()
        compose.onNodeWithText(description.value).assertIsDisplayed()
        advance(3_000)
        assertArrayEquals("A new selection receives its own reading dwell", replacement, pixels())
    }

    @Test fun disabledMotionStopsScrollingAndKeepsFullAccessibleDescription() {
        motion.factor.floatValue = 0f
        show()
        val still = pixels()
        advance(20_000)
        assertArrayEquals(still, pixels())
        compose.runOnIdle { motion.factor.floatValue = 1f }
        advance(6_000)
        assertFalse(still.contentEquals(pixels()))
        compose.runOnIdle { motion.factor.floatValue = 0f }
        advance(100)
        assertArrayEquals("Disabling motion restores the stationary ellipsis", still, pixels())
        advance(6_000)
        assertArrayEquals(still, pixels())
        compose.onNodeWithText(description.value).assertIsDisplayed()
        capture("home-description-motion-disabled")
    }

    @Test fun shortDescriptionNeverMoves() {
        description.value = "A short, complete description."
        show()
        val still = pixels()
        advance(20_000)
        assertArrayEquals(still, pixels())
        compose.onNodeWithText(description.value).assertIsDisplayed()
        capture("home-description-short-still")
    }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { NuvioTheme {
            Box(Modifier.fillMaxSize().background(Color.Black).padding(40.dp)) {
                HeroDescription(
                    description = description.value,
                    contentKey = description.value,
                    autoScrollEnabled = true,
                    style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                    color = Color.White,
                    modifier = Modifier.width(300.dp).offset(x = if (offscreen.value) 3_000.dp else 0.dp).testTag(TAG)
                )
            }
        } }
        advance(200)
    }

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun pixels(): IntArray {
        val bitmap = compose.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null)!!, "$name.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }

    private companion object { const val TAG = "hero-description-fixture" }
}
