package com.nuvio.tv.ui.screens.addon

import android.view.KeyEvent
import android.os.Build
import android.os.SystemClock
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.MainActivity
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.screens.settings.DebridSettingsViewModel
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.DebridAccountCheck
import com.nuvio.tv.core.setup.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Mounts production setup with MainActivity's Hilt owner independently of seeded catalogs. */
@RunWith(AndroidJUnit4::class)
class EssentialAddonSetupScreenTvTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun skipContinuesFromSetup() {
        withSetup {
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithText(label(R.string.essential_addon_enter_link_on_tv)).assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithText(label(R.string.essential_addon_continue_for_now)).assertIsFocused()
            assertEquals(0, skipCalls.get())
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(timeoutMillis = 5_000) { skipCalls.get() == 1 }
        }
    }

    @Test
    fun keyboardBackKeepsManualEntryUntilTheNextBack() {
        withSetup { scenario ->
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithText(label(R.string.essential_addon_enter_link_on_tv)).assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            val editor = compose.onNode(hasSetTextAction())
            editor.assertIsFocused()
            compose.waitUntil(timeoutMillis = 5_000) { isImeVisible(scenario) }

            pressSystemBack()
            compose.waitUntil(timeoutMillis = 5_000) { !isImeVisible(scenario) }
            compose.onNodeWithText(label(R.string.essential_addon_link_title)).assertIsDisplayed()
            editor.assertIsFocused()

            press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(timeoutMillis = 5_000) { !hasText(R.string.essential_addon_link_title) }
            compose.onNodeWithText(label(R.string.essential_addon_enter_link_on_tv)).assertIsFocused()
        }
    }

    @Test
    fun phoneQrBackRestoresPhoneAction() {
        withSetup {
            val qrDescription = label(R.string.cd_qr_code)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(timeoutMillis = 5_000) {
                hasQr(qrDescription) ||
                    hasText(R.string.error_network_required) ||
                    hasText(R.string.error_server_ports_unavailable)
            }
            assumeFalse("QR requires a non-loopback IPv4 address on the bench.", hasText(R.string.error_network_required))
            assumeFalse("The setup server could not open a LAN listener.", hasText(R.string.error_server_ports_unavailable))
            compose.onNodeWithContentDescription(qrDescription).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.addon_qr_close)).assertIsFocused()

            press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(timeoutMillis = 5_000) { !hasQr(qrDescription) }
            compose.onNodeWithText(label(R.string.essential_addon_show_qr)).assertIsFocused()
        }
    }

    @Test
    fun serviceRepairBackRestoresItsActionAndChooseTitleContinues() {
        val continueCalls = AtomicInteger()
        withSetup(onSkip = { continueCalls.incrementAndGet() }) {
            compose.onNodeWithText(label(R.string.first_watch_title)).assertIsDisplayed()
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithText(label(R.string.essential_addon_continue_for_now)).assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithText(label(R.string.first_watch_connect)).assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText(label(R.string.action_back)).assertIsDisplayed().assertIsFocused()
            press(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithText(label(R.string.first_watch_connect)).assertIsFocused()
            assertEquals(0, continueCalls.get())
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithText(label(R.string.first_watch_choose_title)).assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(timeoutMillis = 5_000) { continueCalls.get() == 1 }
        }
    }

    @Test
    fun fourSavedAccountsAndLongPlaybackTitleKeepEverySetupActionReachableAtTvBounds() {
        assumeTrue("Synthetic account layout fixture requires the isolated local-only emulator.",
            BuildConfig.LOCAL_ONLY_BUILD && Build.HARDWARE in setOf("ranchu", "goldfish"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var viewModel: DebridSettingsViewModel
            scenario.onActivity { viewModel = ViewModelProvider(it)[DebridSettingsViewModel::class.java] }
            // Use the mounted production owner's real store without adding a test-only app entry point.
            val field = DebridSettingsViewModel::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
            val store = field.get(viewModel) as DebridSettingsDataStore
            val before = runBlocking { store.settings.first() }
            assumeFalse("This layout fixture requires an empty owned bench profile.", before.hasAnyApiKey)
            val profile = store.activeProfileId.value
            val providers = DebridProviders.visible().map { it.id }
            assertEquals(4, providers.size)
            val continues = AtomicInteger()
            try {
                runBlocking {
                    providers.forEach { id ->
                        check(store.setProviderApiKeyForProfile(profile, id, "layout-fixture-$id", ""))
                    }
                    val configuration = withTimeout(10_000L) {
                        viewModel.firstWatch.configuration.first { config -> config != null &&
                            providers.all { config.settings.apiKeyFor(it) == "layout-fixture-$it" } }
                    }!!
                    val captured = viewModel.firstWatch.captureScope()
                    providers.forEach { id ->
                        check(store.recordFirstWatchAccount(captured, id,
                            firstWatchAccountBinding(profile, id, "layout-fixture-$id"),
                            DebridAccountCheck(FirstWatchAccountOutcome.ACCEPTED), System.currentTimeMillis() - 120_000L) { true })
                    }
                    check(viewModel.firstWatch.recordPlayback(captured, FirstWatchPlaybackObservation(configuration.binding,
                        System.currentTimeMillis() - 120_000L,
                        "Owned demonstration selection with a deliberately long title ".repeat(5).trim(),
                        "owned:layout-fixture", "exo", FirstWatchPlaybackOutcome.STARTED)) { true })
                }
                scenario.onActivity { activity -> activity.setContent {
                    NuvioTheme { Box(Modifier.requiredSize(960.dp, 540.dp)) {
                        EssentialAddonSetupScreen(onSkip = { continues.incrementAndGet() }, debridViewModel = viewModel)
                    } }
                } }
                compose.waitForIdle()
                compose.onNodeWithText("Owned demonstration selection", substring = true).assertIsDisplayed()
                providers.forEach { id -> compose.onNodeWithText("${DebridProviders.displayName(id)}: Saved", substring = true).assertIsDisplayed() }
                for (action in listOf(R.string.essential_addon_show_qr, R.string.essential_addon_enter_link_on_tv,
                    R.string.essential_addon_continue_for_now, R.string.first_watch_connect,
                    R.string.first_watch_choose_title, R.string.first_watch_check_accounts)) {
                    compose.onNodeWithText(label(action)).assertIsDisplayed()
                }
                compose.onNodeWithText(label(R.string.essential_addon_show_qr)).assertIsFocused()
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNodeWithText(label(R.string.essential_addon_enter_link_on_tv)).assertIsFocused()
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNodeWithText(label(R.string.essential_addon_continue_for_now)).assertIsFocused()
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNodeWithText(label(R.string.first_watch_connect)).assertIsFocused()
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                press(KeyEvent.KEYCODE_BACK)
                compose.onNodeWithText(label(R.string.first_watch_connect)).assertIsFocused()
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNodeWithText(label(R.string.first_watch_choose_title)).assertIsFocused()
                press(KeyEvent.KEYCODE_DPAD_LEFT)
                compose.onNodeWithText(label(R.string.first_watch_check_accounts)).assertIsFocused()
                assertEquals(0, continues.get())
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(instrumentation.targetContext.cacheDir, "first-watch-layout-fixture.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            } finally {
                viewModel.cancelAccountCheck()
                runBlocking {
                    providers.forEach { id -> store.setProviderApiKeyForProfile(profile, id, "", "layout-fixture-$id") }
                }
            }
        }
    }

    private fun withSetup(
        onSkip: () -> Unit = {},
        test: (ActivityScenario<MainActivity>) -> Unit
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    NuvioTheme { EssentialAddonSetupScreen(onSkip = onSkip) }
                }
            }
            compose.onNodeWithText(label(R.string.essential_addon_show_qr))
                .assertIsDisplayed().assertIsFocused()
            test(scenario)
        }
    }

    private fun isImeVisible(scenario: ActivityScenario<MainActivity>): Boolean {
        var visible = false
        scenario.onActivity { activity ->
            visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun label(id: Int) = instrumentation.targetContext.getString(id)

    private fun hasText(id: Int) = compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty()

    private fun hasQr(description: String) =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun pressSystemBack() {
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            // The platform IME owns the first Back and can be in another process.
            assertTrue(instrumentation.uiAutomation.injectInputEvent(
                KeyEvent(downTime, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0), true
            ))
        }
    }

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }
}
