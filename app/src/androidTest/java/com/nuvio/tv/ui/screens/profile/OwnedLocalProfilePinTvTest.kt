package com.nuvio.tv.ui.screens.profile

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.core.sync.SetProfilePinResult
import com.nuvio.tv.data.local.LOCAL_PROFILE_PIN_KEY_ALIAS
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.domain.model.WatchProgress
import java.security.KeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Owned-emulator launcher fixture. This class requires the explicit bench argument. */
@RunWith(AndroidJUnit4::class)
class OwnedLocalProfilePinTvTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    @Test fun localPinCancelsRestoresFocusAndKeepsViewerProgressSeparate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ownedLocalProfilePin") == "1")
        require(BuildConfig.LOCAL_ONLY_BUILD && Build.VERSION.SDK_INT >= 30 &&
            Build.HARDWARE in setOf("ranchu", "goldfish") &&
            (property("ro.kernel.qemu") == "1" || property("ro.boot.qemu") == "1"))
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var scenario: ActivityScenario<MainActivity>? = null
        var ownedId: Int? = null
        lateinit var originalProfileSettings: Preferences
        lateinit var profileSettings: DataStore<Preferences>
        lateinit var originalLockSettings: Preferences
        lateinit var lockSettings: DataStore<Preferences>
        lateinit var profiles: ProfileManager
        lateinit var locks: ProfileLockStateDataStore
        lateinit var sync: ProfileSyncService
        var keyWasPresent = true

        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                require(!activity.authManager.isAuthenticated && activity.authManager.currentUserId == null)
                profiles = activity.profileManager
                locks = activity.profileLockStateDataStore
                sync = activity.profileSyncService
            }
            await("primary profile ready") { profiles.activeProfileReady.value }
            require(profiles.activeProfileId.value == 1 && profiles.profiles.value.map { it.id } == listOf(1))
            require(read { locks.pinEnabled.first().values.none { it } })
            val factory = field<ProfileDataStoreFactory>(profiles, "factory")
            require(read { factory.get(1, "layout_settings").data.first()[booleanPreferencesKey("has_chosen_layout")] } == true) {
                "Prepare the account-free Advanced launcher before the profile fixture."
            }
            val profileDataStore = field<ProfileDataStore>(profiles, "profileDataStore")
            profileSettings = field(profileDataStore, "dataStore")
            originalProfileSettings = read { profileSettings.data.first() }
            lockSettings = field(locks, "dataStore")
            originalLockSettings = read { lockSettings.data.first() }
            keyWasPresent = keyStore().containsAlias(LOCAL_PROFILE_PIN_KEY_ALIAS)
            val progress = WatchProgressPreferences(factory, profiles)
            val primaryBefore = read { progress.getAllRawEntries(1) }
            val originalRevision = profiles.activeProfileSelectionRevision
            scenario.close()
            scenario = null

            val secondary = requireNotNull(read { profiles.createProfile(SECONDARY, "#365C83") })
            ownedId = secondary.id
            read {
                profiles.setRememberLastProfileEnabled(false)
                factory.get(secondary.id, "layout_settings").edit {
                    it[booleanPreferencesKey("has_chosen_layout")] = true
                    it[stringPreferencesKey("selected_layout")] = "CLASSIC"
                }
                factory.get(secondary.id, "experience_settings").edit {
                    it[stringPreferencesKey("mode")] = "ADVANCED"
                }
                progress.saveProgress(WatchProgress(
                    contentId = OWNED_CONTENT, contentType = "movie", name = "Owned profile resume",
                    poster = null, backdrop = null, logo = null, videoId = OWNED_CONTENT,
                    season = null, episode = null, episodeTitle = null, position = 15_000L,
                    duration = 100_000L, lastWatched = System.currentTimeMillis()
                ), secondary.id)
                assertEquals(SetProfilePinResult.Success, sync.setProfilePin(secondary.id, PIN))
            }
            await("Remember Last Profile is off") { !profiles.rememberLastProfileEnabled.value }

            val selectionScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = selectionScenario
            val unlock = context.getString(R.string.profile_pin_overlay_unlock_heading, SECONDARY)
            val selector = context.getString(R.string.profile_selection_title)
            await("actual profile selector", timeoutMillis = 15_000L) { hasText(selector) && hasText(SECONDARY) }
            instrumentation.waitForIdleSync()
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("secondary PIN prompt") { hasText(unlock) }

            if (!imeVisible(selectionScenario)) key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("numeric IME opens") { imeVisible(selectionScenario) }
            key(KeyEvent.KEYCODE_BACK)
            await("Back closes only the IME") { !imeVisible(selectionScenario) && hasText(unlock) }
            digits("9999")
            await("wrong PIN rejected") { hasText(context.getString(R.string.profile_pin_invalid)) }
            assertEquals(1, profiles.activeProfileId.value)

            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val pending = owner.launch {
                lockSettings.updateData { prefs ->
                    held.complete(Unit)
                    release.await()
                    prefs
                }
            }
            try {
                read { held.await() }
                digits(PIN)
                await("PIN verification is pending") { hasText(context.getString(R.string.profile_pin_verifying)) }
                key(KeyEvent.KEYCODE_BACK)
                await("Back cancels the PIN prompt") { hasText(selector) && !hasText(unlock) }
            } finally {
                release.complete(Unit)
                read { pending.join() }
            }
            // A Center on the restored card opens the same PIN prompt, even after the earlier success returns.
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("same protected profile is focused after Back") { hasText(unlock) }
            assertEquals(originalRevision, profiles.activeProfileSelectionRevision)
            assertEquals(1, profiles.activeProfileId.value)
            assertEquals(primaryBefore, read { progress.getAllRawEntries(1) })

            if (imeVisible(selectionScenario)) {
                key(KeyEvent.KEYCODE_BACK)
                await("numeric IME closes for hardware PIN entry") { !imeVisible(selectionScenario) }
            }
            digits(PIN)
            await("correct PIN selects the intended profile") { profiles.activeProfileId.value == secondary.id && !hasText(unlock) }
            assertEquals(15_000L, read { progress.getProgress(OWNED_CONTENT).first() }?.position)
            assertEquals(primaryBefore, read { progress.getAllRawEntries(1) })
            val home = context.getString(R.string.nav_home)
            val settings = context.getString(R.string.nav_settings)
            val connectedServices = context.getString(R.string.settings_menu_connected_services)
            await("protected viewer reaches Home", timeoutMillis = 15_000L) { selectedTab(home) }
            key(KeyEvent.KEYCODE_BACK)
            val settingsTab = hasContentDescription(settings) and hasClickAction()
            await("Settings entry is available") {
                compose.onAllNodes(settingsTab).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
            }
            compose.onNode(settingsTab).performClick()
            await("protected viewer enters Settings") { hasText(connectedServices) }
            read { profiles.setRememberLastProfileEnabled(true) }
            await("Remember Last Profile is on") { profiles.rememberLastProfileEnabled.value }

            selectionScenario.recreate()
            await("protected viewer prompts again after Activity recreation with Remember Last Profile") {
                hasText(selector) && hasText(SECONDARY)
            }
            instrumentation.waitForIdleSync()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("same active viewer still requires PIN") { hasText(unlock) }
            assertFalse(hasText("Owned profile resume"))
            assertTrue(read { locks.pinEnabled.first()[secondary.id] == true })
            if (imeVisible(selectionScenario)) {
                key(KeyEvent.KEYCODE_BACK)
                await("IME closes while switching viewers") { !imeVisible(selectionScenario) && hasText(unlock) }
            }
            key(KeyEvent.KEYCODE_BACK)
            await("cancel returns to the protected viewer card") { hasText(selector) && !hasText(unlock) }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("unprotected viewer starts at Home with no inherited Settings route", timeoutMillis = 15_000L) {
                profiles.activeProfileId.value == 1 && selectedTab(home) && !hasText(connectedServices)
            }
            assertEquals(primaryBefore, read { progress.getAllRawEntries(1) })
        } finally {
            scenario?.close()
            owner.cancel()
            ownedId?.let { id ->
                read {
                    profiles.setActiveProfile(1)
                    check(profiles.deleteProfile(id))
                    check(locks.pinEnabled.first()[id] != true)
                    profileSettings.updateData { originalProfileSettings }
                    lockSettings.updateData { originalLockSettings }
                    if (!keyWasPresent) keyStore().deleteEntry(LOCAL_PROFILE_PIN_KEY_ALIAS)
                }
            }
        }
    }

    private fun property(name: String) = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand("getprop $name")
    ).bufferedReader().use { it.readText().trim() }

    private fun imeVisible(scenario: ActivityScenario<MainActivity>): Boolean {
        var visible = false
        scenario.onActivity {
            visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun hasText(value: String) = compose.onAllNodesWithText(value, useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun selectedTab(value: String) = compose.onAllNodes(
        hasContentDescription(value) and isSelected(), useUnmergedTree = true
    ).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun key(code: Int) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            check(automation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(), action, code, 0,
                0, 0, 0, 0, InputDevice.SOURCE_DPAD), true))
        }
        instrumentation.waitForIdleSync()
    }

    private fun digits(value: String) = value.forEach { key(KeyEvent.KEYCODE_0 + it.digitToInt()) }

    private fun await(description: String, timeoutMillis: Long = 8_000L, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            if (check()) return
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        val visible = listOf(context.getString(R.string.profile_selection_title), SECONDARY,
            context.getString(R.string.profile_pin_overlay_unlock_heading, SECONDARY),
            context.getString(R.string.profile_pin_verifying), context.getString(R.string.profile_pin_invalid))
            .filter { runCatching { hasText(it) }.getOrDefault(false) }
        throw AssertionError("$description; matching profile labels=${visible.joinToString()}")
    }

    private fun <T> read(action: suspend () -> T): T = runBlocking { withTimeout(10_000L) { action() } }
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T = target.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(target) as T
    }

    private companion object {
        const val SECONDARY = "PIN Viewer"
        const val OWNED_CONTENT = "tt998867101"
        const val PIN = "1947"
    }
}
