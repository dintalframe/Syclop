package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.MainActivity
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real style cards, persistence, layout replacement and remote activation; no direct selection events. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ThemeStyleFocusTvTest {
    private class TestMotionScale : MotionDurationScale {
        val factor = mutableFloatStateOf(1f)
        override val scaleFactor: Float get() = factor.floatValue
    }

    private val motion = TestMotionScale()
    @get:Rule val compose = createEmptyComposeRule(effectContext = motion)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val appliedStyle = AtomicReference(SettingsUiStyle.CLASSIC)

    @Test
    fun chosenStyleKeepsFocusAcrossEveryLayoutReplacement() {
        withSettings { exerciseStyleTransitions() }
    }

    @Test
    fun zeroAnimationScaleKeepsFocusAcrossEveryLayoutReplacement() {
        motion.factor.floatValue = 0f
        withSettings { exerciseStyleTransitions() }
    }

    @Test
    fun customColorCancelAndBackReturnToTheColorInEveryStyle() {
        withSettings { activity ->
            listOf(SettingsUiStyle.CLASSIC, SettingsUiStyle.ZEN, SettingsUiStyle.HORIZON).forEach { style ->
                if (style != SettingsUiStyle.CLASSIC) chooseStyle(style)
                val before = runBlocking { activity.themeDataStore.themeSelection.first() }
                focusCustomColor()
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.onNodeWithText(label(R.string.custom_theme_solid_title)).assertIsDisplayed()
                press(KeyEvent.KEYCODE_BACK)
                compose.onNodeWithText(label(R.string.custom_theme_solid_title)).assertDoesNotExist()
                compose.onNodeWithContentDescription(label(R.string.theme_color_custom)).assertIsFocused()

                press(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.onNode(hasText(label(R.string.action_cancel)) and hasAnyAncestor(isDialog()))
                    .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                    .assertIsFocused()
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.onNodeWithText(label(R.string.custom_theme_solid_title)).assertDoesNotExist()
                compose.onNodeWithContentDescription(label(R.string.theme_color_custom)).assertIsFocused()
                assertEquals(before, runBlocking { activity.themeDataStore.themeSelection.first() })
            }
        }
    }

    private fun exerciseStyleTransitions() {
        // Every directed pair, including the reported Classic -> Zen -> Classic reversal.
        listOf(
            SettingsUiStyle.ZEN, SettingsUiStyle.CLASSIC, SettingsUiStyle.HORIZON,
            SettingsUiStyle.ZEN, SettingsUiStyle.HORIZON, SettingsUiStyle.CLASSIC
        ).forEach(::chooseStyle)
    }

    private fun chooseStyle(style: SettingsUiStyle) {
        val currentStyle = appliedStyle.get()
        compose.onNodeWithText(styleLabel(currentStyle))
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }.assertIsFocused()
        val direction = if (style.ordinal > currentStyle.ordinal) KeyEvent.KEYCODE_DPAD_RIGHT
            else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(style.ordinal - currentStyle.ordinal)) { press(direction) }
        compose.onNodeWithText(styleLabel(style)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(timeoutMillis = 5_000) { appliedStyle.get() == style }
        compose.waitForIdle()
        // Query the new control after replacement; the old semantics node is no longer attached.
        compose.onNodeWithText(styleLabel(style)).assertIsFocused()
        compose.onNodeWithContentDescription(label(R.string.theme_color_custom)).assertIsNotFocused()
    }

    private fun withSettings(test: (MainActivity) -> Unit) {
        require(BuildConfig.LOCAL_ONLY_BUILD && Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Theme focus fixture requires a disposable account-free local-build emulator."
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            val originalSelection = runBlocking { activity.themeDataStore.themeSelection.first() }
            val originalStyle = runBlocking { activity.themeDataStore.settingsUiStyle.first() }
            try {
                runBlocking {
                    activity.themeDataStore.setCustomTheme(CustomThemeColors.solid(0x445566))
                    activity.themeDataStore.setSettingsUiStyle(SettingsUiStyle.CLASSIC)
                }
                scenario.onActivity { owner ->
                    owner.setContent {
                        val style by owner.themeDataStore.settingsUiStyle
                            .collectAsStateWithLifecycle(initialValue = SettingsUiStyle.CLASSIC)
                        SideEffect { appliedStyle.set(style) }
                        val initialFocusRequester = remember { FocusRequester() }
                        NuvioTheme(settingsUiStyle = style) {
                            SettingsWorkspaceSurface(Modifier.fillMaxSize()) {
                                // Match SettingsScreen's Horizon Column vs side-rail Row replacement.
                                if (style == SettingsUiStyle.HORIZON) {
                                    Column(Modifier.fillMaxSize()) {
                                        ThemeSettingsContent(initialFocusRequester = initialFocusRequester)
                                    }
                                } else {
                                    Row(Modifier.fillMaxSize()) {
                                        ThemeSettingsContent(initialFocusRequester = initialFocusRequester)
                                    }
                                }
                            }
                        }
                    }
                }
                compose.waitUntil(timeoutMillis = 5_000) {
                    compose.onAllNodes(hasContentDescription(label(R.string.theme_color_custom)) and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription(label(R.string.theme_color_custom)).assertIsFocused()
                test(activity)
            } finally {
                runBlocking {
                    activity.themeDataStore.setCustomTheme(originalSelection.customColors)
                    originalSelection.theme?.let { activity.themeDataStore.setTheme(it) }
                    activity.themeDataStore.setSettingsUiStyle(originalStyle)
                }
            }
        }
    }

    private fun focusCustomColor() {
        compose.onNodeWithContentDescription(label(R.string.theme_color_custom))
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }.assertIsFocused()
    }

    private fun styleLabel(style: SettingsUiStyle) = label(when (style) {
        SettingsUiStyle.CLASSIC -> R.string.settings_style_classic
        SettingsUiStyle.ZEN -> R.string.settings_style_zen
        SettingsUiStyle.HORIZON -> R.string.settings_style_horizon
    })

    private fun label(id: Int) = instrumentation.targetContext.getString(id)

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }
}
