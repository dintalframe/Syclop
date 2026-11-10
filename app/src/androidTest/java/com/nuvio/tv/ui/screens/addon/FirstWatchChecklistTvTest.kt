package com.nuvio.tv.ui.screens.addon

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.nuvio.tv.core.setup.*
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the shared production checklist; the fixture contains no playable URL or network action. */
@RunWith(AndroidJUnit4::class)
class FirstWatchChecklistTvTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun addedAndSavedStayUnknownUntilAnActualObservationAndCancelKeepsFocus() {
        val checks = AtomicInteger()
        val cancels = AtomicInteger()
        val checking = mutableStateOf(false)
        val snapshot = snapshot()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    NuvioTheme {
                        Column(Modifier.fillMaxSize().padding(32.dp)) {
                            FirstWatchChecklist(snapshot, checking.value,
                                onCheckAccounts = { checks.incrementAndGet(); checking.value = true },
                                onCancelCheck = { cancels.incrementAndGet(); checking.value = false })
                        }
                    }
                }
            }
            compose.onNodeWithText(label(R.string.first_watch_catalog, label(R.string.first_watch_added))).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.first_watch_source, label(R.string.first_watch_added))).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.first_watch_saved_account, "Real-Debrid", label(R.string.first_watch_unknown))).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.first_watch_playback_unknown)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.first_watch_check_accounts))
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithText(label(R.string.first_watch_check_accounts)).assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText(label(R.string.first_watch_cancel_check)).assertIsFocused()
            assertEquals(1, checks.get())
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText(label(R.string.first_watch_check_accounts)).assertIsFocused()
            assertEquals(1, cancels.get())
        }
    }

    @Test fun acceptedAccountShowsAgeButNeverMakesPlaybackCheckedAndExpiryReturnsUnknown() {
        val initial = snapshot()
        val config = initial.configuration!!
        val now = System.currentTimeMillis()
        val checked = FirstWatchAccountObservation(firstWatchAccountBinding(1, "realdebrid", "synthetic-display-key"),
            now - 120_000L, FirstWatchAccountOutcome.ACCEPTED)
        val state = mutableStateOf(initial.copy(observations = FirstWatchObservations(accounts = mapOf("realdebrid" to checked))))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                NuvioTheme { Column(Modifier.fillMaxSize().padding(32.dp)) { FirstWatchChecklist(state.value, false, {}, {}) } }
            } }
            val accepted = label(R.string.first_watch_saved_account, "Real-Debrid", label(R.string.first_watch_signin_accepted))
            compose.onNodeWithText(accepted, substring = true).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.first_watch_minutes_ago, 2L), substring = true).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.first_watch_playback_unknown)).assertIsDisplayed()
            compose.runOnIdle {
                state.value = FirstWatchSnapshot(config, FirstWatchObservations(accounts = mapOf("realdebrid" to
                    checked.copy(checkedAtMs = now - 900_000L))))
            }
            compose.onNodeWithText(label(R.string.first_watch_saved_account, "Real-Debrid", label(R.string.first_watch_unknown)),
                substring = true).assertIsDisplayed()
        }
    }

    private fun snapshot(): FirstWatchSnapshot {
        val addon = Addon("fixture", "Configured source", version = "1", description = null, logo = null,
            baseUrl = "https://fixture.example", types = listOf(ContentType.MOVIE),
            catalogs = listOf(CatalogDescriptor(ContentType.MOVIE, id = "movies", name = "Movies")),
            resources = listOf(AddonResource("stream", listOf("movie"), null)))
        return FirstWatchSnapshot(FirstWatchConfiguration(1, listOf(addon),
            DebridSettings(realDebridApiKey = "synthetic-display-key"), "internal:EXOPLAYER"))
    }

    private fun label(id: Int, vararg args: Any) = instrumentation.targetContext.getString(id, *args)
    private fun press(keyCode: Int) { instrumentation.sendKeyDownUpSync(keyCode); compose.waitForIdle() }
}
