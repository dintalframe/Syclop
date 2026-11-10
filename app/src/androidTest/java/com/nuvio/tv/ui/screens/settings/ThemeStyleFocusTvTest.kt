package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import android.graphics.Bitmap
import java.io.File
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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
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
import androidx.lifecycle.ViewModelProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.MainActivity
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    @Test
    fun standaloneAppearanceCallerKeepsFocusAcrossEveryStyleChange() {
        withSettings(standalone = true) {
            exerciseStyleTransitions()
            captureLifecycle("theme-standalone-transitions")
        }
    }

    @Test
    fun normalAppearanceRetainsChosenStyleAfterActivityRecreationAndAcceptsTheNextChoice() {
        assumeTrue("Normal lifecycle fixture requires explicit owned_theme_lifecycle_fixture=1.",
            InstrumentationRegistry.getArguments().getString("owned_theme_lifecycle_fixture") == "1")
        require(BuildConfig.LOCAL_ONLY_BUILD && Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Normal lifecycle fixture requires an owned local-build emulator."
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var before: MainActivity
            scenario.onActivity { before = it }
            runBlocking { withTimeout(10_000) { before.profileManager.activeProfileReady.first { it } } }
            require(!before.authManager.isAuthenticated && before.authManager.currentUserId == null)
            require(before.profileManager.profiles.value.map { it.id } == listOf(1) &&
                before.profileManager.activeProfileId.value == 1) { "A single owned primary profile is required." }
            runBlocking {
                require(before.addonRepository.getInstalledAddons().first().isEmpty())
                require(before.experienceModeDataStore.mode.first() != null &&
                    before.layoutPreferenceDataStore.hasChosenLayout.first()) {
                    "Complete normal owned-guest setup before this fixture; it does not alter onboarding."
                }
            }
            val originalStyle = runBlocking { before.themeDataStore.settingsUiStyle.first() }
            try {
                runBlocking { before.themeDataStore.setSettingsUiStyle(SettingsUiStyle.CLASSIC) }
                openNormalAppearance()
                chooseNormalStyle(before, SettingsUiStyle.CLASSIC, SettingsUiStyle.ZEN)
                captureLifecycle("theme-normal-zen-before-recreate")

                scenario.recreate()
                lateinit var after: MainActivity
                scenario.onActivity { after = it }
                assertNotSame(before, after)
                runBlocking { withTimeout(10_000) { after.profileManager.activeProfileReady.first { it } } }
                assertEquals(1, after.profileManager.activeProfileId.value)
                assertEquals(SettingsUiStyle.ZEN, runBlocking { after.themeDataStore.settingsUiStyle.first() })
                // Reenter through real controls if recreation returned Home or the Settings menu.
                openNormalAppearance()
                waitSelectedStyle(SettingsUiStyle.ZEN)
                captureLifecycle("theme-normal-zen-after-recreate")
                chooseNormalStyle(after, SettingsUiStyle.ZEN, SettingsUiStyle.HORIZON)
                captureLifecycle("theme-normal-horizon-next-choice")
            } catch (failure: Throwable) {
                runCatching {
                    val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
                    File(directory, "theme-normal-failure-semantics.txt").writeText(
                        "savedStyle=" + runBlocking { before.themeDataStore.settingsUiStyle.first() }.name +
                            "\nMERGED\n" + compose.onRoot().printToString(maxDepth = 30) +
                            "\nUNMERGED\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
                }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { captureLifecycle("theme-normal-failure") }
                    .exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            } finally {
                require(before.profileManager.activeProfileId.value == 1) {
                    "Profile changed; cleanup refuses to write another owner's style."
                }
                runBlocking { before.themeDataStore.setSettingsUiStyle(originalStyle) }
            }
        }
    }

    @Test
    fun normalAdvancedHidesUnavailableReportsWithStoredPreferenceEnabledAndKeepsLocalDiagnostics() {
        assumeTrue("Normal reporting fixture requires explicit owned_report_availability_fixture=1.",
            InstrumentationRegistry.getArguments().getString("owned_report_availability_fixture") == "1")
        require(BuildConfig.LOCAL_ONLY_BUILD && BuildConfig.PLAYBACK_REPORTS_BASE_URL.isBlank() &&
            Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Normal reporting fixture requires an owned emulator with no configured report endpoint."
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            lateinit var playerViewModel: PlayerViewModel
            scenario.onActivity {
                activity = it
                playerViewModel = ViewModelProvider(it)[PlayerViewModel::class.java]
            }
            runBlocking { withTimeout(10_000) { activity.profileManager.activeProfileReady.first { it } } }
            require(!activity.authManager.isAuthenticated && activity.authManager.currentUserId == null)
            require(activity.profileManager.profiles.value.map { it.id } == listOf(1) &&
                activity.profileManager.activeProfileId.value == 1) { "A single owned primary profile is required." }
            val selectionRevision = activity.profileManager.activeProfileSelectionRevision
            val controller = playerViewModel.controller
            val services = requireNotNull(controller.debridSettingsDataStore)
            val diagnostics = runBlocking {
                require(activity.addonRepository.getInstalledAddons().first().isEmpty())
                require(!services.settings.first().hasAnyApiKey && !controller.tmdbSettingsDataStore.settings.value.enabled)
                require(activity.experienceModeDataStore.mode.first() != null &&
                    activity.layoutPreferenceDataStore.hasChosenLayout.first()) {
                    "Complete normal owned-guest setup before this fixture; it does not alter onboarding."
                }
                require(controller.playerSettingsDataStore.playerSettings.first().internalPlayerEngine in
                    setOf(InternalPlayerEngine.EXOPLAYER, InternalPlayerEngine.AUTO))
                controller.playerSettingsDataStore.lastPlaybackDiagnostics.first().also {
                    require(it.videoBitrate > 0 || it.streamUrl.isNullOrBlank() || it.durationMs <= 0L) {
                        "Advanced would automatically probe this stored stream; fixture refuses without changing it."
                    }
                }
            }
            val factory = controller.playerSettingsDataStore.javaClass.getDeclaredField("factory").apply {
                isAccessible = true
            }.get(controller.playerSettingsDataStore) as ProfileDataStoreFactory
            val playerPreferences = factory.get(1, "player_settings")
            @Suppress("UNCHECKED_CAST")
            val devicePreferences = controller.deviceLocalPlayerPreferences.javaClass.getDeclaredField("store").apply {
                isAccessible = true
            }.get(controller.deviceLocalPlayerPreferences) as DataStore<Preferences>
            val originalPlayer = runBlocking { playerPreferences.data.first() }
            val originalDevice = runBlocking { devicePreferences.data.first() }
            val originalStyle = runBlocking { activity.themeDataStore.settingsUiStyle.first() }
            try {
                runBlocking {
                    controller.playerSettingsDataStore.setPlaybackIssueReportsEnabled(true)
                    activity.themeDataStore.setSettingsUiStyle(SettingsUiStyle.CLASSIC)
                    withTimeout(5_000) { controller.playerSettingsDataStore.playerSettings.first { it.playbackIssueReportsEnabled } }
                }
                // Reuse the existing normal Home/Settings route, then enter the same real System leaf.
                openNormalAppearance()
                press(KeyEvent.KEYCODE_BACK)
                compose.onNode(hasText(label(R.string.settings_menu_theme)) and hasClickAction()).assertIsFocused()
                repeat(4) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
                val system = hasText(label(R.string.settings_menu_system)) and hasClickAction()
                compose.onNode(system).assertIsFocused()
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                val firstAdvancedAction = (hasText(label(R.string.experience_mode_switch_to_advanced)) or
                    hasText(label(R.string.experience_mode_switch_to_essential))) and hasClickAction()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(firstAdvancedAction and isFocused()).fetchSemanticsNodes().size == 1
                }
                val hud = hasText(label(R.string.advanced_player_stats_hud)) and hasClickAction()
                repeat(18) {
                    if (compose.onAllNodes(hud and isFocused()).fetchSemanticsNodes().isEmpty()) press(KeyEvent.KEYCODE_DPAD_DOWN)
                }
                compose.onNode(hud).assertIsDisplayed().assertIsFocused()
                compose.onNodeWithText(label(R.string.advanced_section_diagnostics)).assertIsDisplayed()
                compose.onNodeWithText(label(R.string.advanced_sentry_reports)).assertIsDisplayed()
                compose.onNodeWithText(label(R.string.advanced_playback_issue_reports)).assertDoesNotExist()
                val originalHud = runBlocking { controller.deviceLocalPlayerPreferences.playerStatsHudEnabled.first() }
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                runBlocking { withTimeout(5_000) {
                    controller.deviceLocalPlayerPreferences.playerStatsHudEnabled.first { it != originalHud }
                } }
                compose.onNode(hud).assertIsFocused()
                assertTrue(runBlocking { controller.playerSettingsDataStore.playerSettings.first().playbackIssueReportsEnabled })
                val noDiagnosticData = diagnostics.timestampMs == 0L || diagnostics.host.isBlank()
                if (noDiagnosticData) captureLifecycle("report-normal-advanced-diagnostics")

                val localDiagnostics = hasText(label(if (noDiagnosticData) R.string.diag_last_playback_title
                    else R.string.diag_section_source_hardware)) and hasClickAction()
                repeat(8) {
                    if (compose.onAllNodes(localDiagnostics and isFocused()).fetchSemanticsNodes().isEmpty()) press(KeyEvent.KEYCODE_DPAD_DOWN)
                }
                compose.onNode(localDiagnostics).assertIsDisplayed().assertIsFocused()
                if (noDiagnosticData) {
                    compose.onNodeWithText(label(R.string.diag_no_data)).assertIsDisplayed()
                    captureLifecycle("report-normal-advanced-local-diagnostics")
                }
                press(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(system and isFocused()).fetchSemanticsNodes().size == 1
                }
                compose.onNode(system).assertIsFocused()
                if (noDiagnosticData) captureLifecycle("report-normal-advanced-back")
            } finally {
                require(activity.profileManager.activeProfileId.value == 1 &&
                    activity.profileManager.activeProfileSelectionRevision == selectionRevision) {
                    "Profile owner changed; cleanup refuses to write another owner's preferences."
                }
                runBlocking {
                    playerPreferences.updateData { originalPlayer }
                    devicePreferences.updateData { originalDevice }
                    activity.themeDataStore.setSettingsUiStyle(originalStyle)
                    check(playerPreferences.data.first() == originalPlayer)
                    check(devicePreferences.data.first() == originalDevice)
                    check(activity.themeDataStore.settingsUiStyle.first() == originalStyle)
                }
            }
        }
    }

    private fun openNormalAppearance() {
        val classic = hasText(styleLabel(SettingsUiStyle.CLASSIC)) and hasClickAction()
        if (compose.onAllNodes(classic).fetchSemanticsNodes().isNotEmpty()) return
        val advanced = (hasContentDescription(label(R.string.settings_advanced)) or
            hasText(label(R.string.settings_advanced))) and hasClickAction()
        if (compose.onAllNodes(advanced).fetchSemanticsNodes().isEmpty()) {
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodes(hasText(label(R.string.home_no_addons))).fetchSemanticsNodes().isNotEmpty()
            }
            val settings = hasContentDescription(label(R.string.nav_settings)) and hasClickAction()
            var focused = false
            repeat(4) {
                if (!focused) {
                    press(KeyEvent.KEYCODE_DPAD_LEFT)
                    if (compose.onAllNodes(settings).fetchSemanticsNodes().size == 1) {
                        compose.onNode(settings).performSemanticsAction(SemanticsActions.RequestFocus) { focused = it() }
                    }
                }
            }
            assertTrue("Normal Home must expose its Settings drawer action", focused)
            compose.onNode(settings).assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        activateNormalAction(advanced)
        val playback = hasText(label(R.string.settings_playback)) and hasClickAction()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(playback and isFocused()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(playback).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText(label(R.string.settings_menu_theme)) and hasClickAction()).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(label(R.string.appearance_settings_style)).assertIsDisplayed()
    }

    private fun activateNormalAction(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
        compose.onNode(matcher).performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            .assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun chooseNormalStyle(activity: MainActivity, current: SettingsUiStyle, desired: SettingsUiStyle) {
        waitSelectedStyle(current)
        compose.onNode(hasText(styleLabel(current)) and hasClickAction())
            .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }.assertIsFocused()
        val direction = if (desired.ordinal > current.ordinal) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(desired.ordinal - current.ordinal)) { press(direction) }
        compose.onNodeWithText(styleLabel(desired)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        runBlocking { withTimeout(5_000) { activity.themeDataStore.settingsUiStyle.first { it == desired } } }
        waitSelectedStyle(desired)
        compose.waitForIdle()
        compose.onNode(hasText(styleLabel(desired)) and hasClickAction()).assertIsFocused()
    }

    private fun waitSelectedStyle(style: SettingsUiStyle) {
        val selected = hasText(styleLabel(style)) and hasContentDescription(label(R.string.cd_selected))
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(selected).fetchSemanticsNodes().size == 1 }
        compose.onNode(selected).assertIsDisplayed()
    }

    private fun captureLifecycle(name: String) {
        compose.waitForIdle()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "$name.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
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

    private fun withSettings(standalone: Boolean = false, test: (MainActivity) -> Unit) {
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
                            if (standalone) {
                                ThemeSettingsScreen(onBackPress = {})
                            } else SettingsWorkspaceSurface(Modifier.fillMaxSize()) {
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
                if (standalone) {
                    compose.waitUntil(timeoutMillis = 5_000) {
                        compose.onAllNodes(hasText(styleLabel(SettingsUiStyle.CLASSIC)) and hasClickAction())
                            .fetchSemanticsNodes().size == 1
                    }
                } else {
                    compose.waitUntil(timeoutMillis = 5_000) {
                        compose.onAllNodes(hasContentDescription(label(R.string.theme_color_custom)) and isFocused())
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithContentDescription(label(R.string.theme_color_custom)).assertIsFocused()
                }
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
