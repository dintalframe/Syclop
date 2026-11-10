package com.nuvio.tv.ui.screens.stream

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DirectDebridFailure
import com.nuvio.tv.core.debrid.DirectDebridFailureReason
import com.nuvio.tv.core.debrid.DirectDebridResolveResult
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real finite queue and production loading UI; synthetic source responses need no account or media. */
@RunWith(AndroidJUnit4::class)
class SourceRecoveryTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun remoteCancelKeepsProgressAndDropsNonCooperativeLateSuccess() = exerciseCancel(KeyEvent.KEYCODE_DPAD_CENTER)

    @Test fun backCancelsRecoveryToSourceList() = exerciseCancel(KeyEvent.KEYCODE_BACK)

    private fun exerciseCancel(key: Int) {
        val firstRefusal = CompletableDeferred<Unit>()
        val lateResponse = CompletableDeferred<Unit>()
        val ui = mutableStateOf(StreamScreenUiState(title = "Source recovery fixture"))
        val cancelled = mutableStateOf(false)
        var owner = true
        var request: Job? = null
        var launched = false
        val calls = mutableListOf<Stream>()
        val sources = ('1'..'6').map { hash -> Stream(name = "Synthetic source $hash", title = null,
            description = null, url = null, ytId = null, infoHash = hash.toString().repeat(40),
            fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "Fixture", addonLogo = null) }
        val cancel = {
            owner = false
            cancelled.value = true
            request?.cancel()
            Unit
        }
        compose.setContent {
            NuvioTheme {
                if (cancelled.value) Text("Source list")
                else StreamLoadingOverlay(ui.value, onCancel = cancel)
                LaunchedEffect(Unit) {
                    request = coroutineContext.job
                    val result = resolveAutomaticStreamFallback(initial = sources.first(), ranked = sources,
                        eligible = { true }, selectNext = { it.firstOrNull() }, isCurrent = { owner },
                        onAttempt = { attempt, total -> ui.value = ui.value.copy(
                            sourceRecoveryAttempt = attempt, sourceRecoveryTotal = total,
                            directAutoPlayMessage = "Trying synthetic service") }) { stream ->
                        calls += stream
                        if (stream == sources.first()) {
                            firstRefusal.await()
                            AutomaticStreamAttempt<String>(failure = DirectDebridResolveResult.Failure(
                                DirectDebridFailure(DirectDebridFailureReason.SOURCE_REJECTED)))
                        } else withContext(NonCancellable) {
                            lateResponse.await()
                            AutomaticStreamAttempt(playback = "late synthetic playback")
                        }
                    }
                    launched = result.playback != null
                }
            }
        }
        val context = instrumentation.targetContext
        val cancelLabel = context.getString(R.string.action_cancel)
        compose.onNodeWithText(context.getString(R.string.source_recovery_attempt, 1, 6)).assertIsDisplayed()
        compose.onNodeWithText(cancelLabel).assertIsFocused()
        compose.runOnIdle { firstRefusal.complete(Unit) }
        compose.onNodeWithText(context.getString(R.string.source_recovery_attempt, 2, 6)).assertIsDisplayed()
        compose.onNodeWithText("Trying synthetic service").assertIsDisplayed()
        compose.onNodeWithText(cancelLabel).assertIsFocused()
        capture(if (key == KeyEvent.KEYCODE_BACK) "source-recovery-before-back" else "source-recovery-progress-cancel")
        instrumentation.sendKeyDownUpSync(key)
        compose.onNodeWithText("Source list").assertIsDisplayed()
        compose.runOnIdle { lateResponse.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(launched)
            assertEquals(sources.take(2), calls)
        }
        capture(if (key == KeyEvent.KEYCODE_BACK) "source-recovery-after-back" else "source-recovery-cancelled")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null)!!, "$name.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }
}
