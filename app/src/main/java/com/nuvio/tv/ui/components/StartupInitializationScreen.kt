package com.nuvio.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.initialization.InitializationFailure
import com.nuvio.tv.core.initialization.InitializationPhase
import com.nuvio.tv.core.initialization.InitializationStageStatus
import com.nuvio.tv.core.initialization.InitializationStep
import com.nuvio.tv.core.initialization.StartupInitializationState

/** Callers use the actual Home route and selected owner, never a background import alone. */
internal fun startupInitializationBlocksHome(homeEntry: Boolean, profileId: Int,
    selectionRevision: Long, state: StartupInitializationState): Boolean = homeEntry &&
    (state.profileId != profileId || state.selectionRevision != selectionRevision || state.blocksHome)

/** Blocks only Home. All text is fixed application copy or bounded local counts. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun StartupInitializationScreen(state: StartupInitializationState, onRetry: () -> Unit,
    onContinue: () -> Unit, modifier: Modifier = Modifier) {
    val continueFocus = remember { FocusRequester() }
    val canRetry = state.phase == InitializationPhase.NEEDS_ATTENTION
    val (title, description) = when {
        canRetry -> R.string.startup_accounts_attention to R.string.startup_accounts_attention_description
        state.phase == InitializationPhase.IMPORTING ->
            R.string.startup_accounts_importing to R.string.startup_accounts_importing_description
        else -> R.string.startup_accounts_checking to R.string.startup_accounts_checking_description
    }
    BackHandler(onBack = onContinue)
    // Retry disappears when its import starts. Re-enter the stable action after that
    // focus target is removed, including retries started outside this composable.
    LaunchedEffect(canRetry) { withFrameNanos { }; continueFocus.requestFocus() }
    // Stay in the Activity's existing window: a brief account check need not create and
    // immediately tear down another ViewRoot while accessibility is inspecting startup.
    Column(modifier.fillMaxSize().background(Color.Black)
        .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
        .padding(horizontal = 56.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        SyclopBrandAnimation(if (state.phase == InitializationPhase.NEEDS_ATTENTION)
            SyclopBrandState.Error else SyclopBrandState.Loading, size = 96.dp)
        Text(stringResource(title), modifier = Modifier.semantics {
            heading()
            if (canRetry) liveRegion = LiveRegionMode.Polite
        }, textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(description),
            modifier = Modifier.widthIn(max = 700.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(12.dp))
        state.stages.forEach { stage ->
            val detail = when (stage.status) {
                InitializationStageStatus.WAITING -> stringResource(R.string.startup_stage_waiting)
                InitializationStageStatus.READING -> stringResource(when (stage.step) {
                    InitializationStep.HISTORY -> R.string.startup_stage_reading_history
                    InitializationStep.LIBRARY -> R.string.startup_stage_reading_library
                    InitializationStep.ACCOUNT -> R.string.startup_stage_checking_account
                    null -> R.string.startup_stage_preparing_import
                })
                InitializationStageStatus.COMPLETE -> {
                    val count = stage.count
                    if (count == null) {
                        stringResource(R.string.startup_stage_previously_initialized)
                    } else {
                        val entries = pluralStringResource(R.plurals.startup_stage_entries_read, count, count)
                        if (stage.truncated) stringResource(R.string.startup_stage_partial, entries) else entries
                    }
                }
                InitializationStageStatus.FAILED -> stringResource(when (stage.failure) {
                    InitializationFailure.TIMEOUT -> R.string.startup_stage_timed_out
                    InitializationFailure.INCOMPLETE -> R.string.startup_stage_incomplete
                    InitializationFailure.UNAVAILABLE -> R.string.startup_stage_unavailable
                    else -> R.string.startup_stage_failed
                })
            }
            Text(stringResource(R.string.startup_stage_provider_detail, stage.provider.label, detail),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics {
                    if (stage.status == InitializationStageStatus.READING) liveRegion = LiveRegionMode.Polite
                },
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                color = if (stage.status == InitializationStageStatus.FAILED) Color(0xFFFFCCCC) else Color.White)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onContinue, modifier = Modifier.focusRequester(continueFocus)) {
                Text(stringResource(R.string.startup_action_continue))
            }
            if (canRetry) Button(onClick = {
                // Transfer focus before onRetry can synchronously remove this button.
                continueFocus.requestFocus()
                onRetry()
            }) { Text(stringResource(R.string.startup_action_retry)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.startup_accounts_continue_hint),
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

/** Keep mounted Home/navigation effects alive while the same-window gate owns all input. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun StartupInitializationContent(blocked: Boolean, modifier: Modifier = Modifier,
    content: @Composable () -> Unit) {
    val contentFocus = remember { FocusRequester() }
    var restoreOnUnblock by remember { mutableStateOf(blocked) }
    LaunchedEffect(blocked) {
        if (blocked) restoreOnUnblock = true
        else if (restoreOnUnblock) {
            restoreOnUnblock = false
            withFrameNanos { }
            contentFocus.requestFocus()
        }
    }
    Box(modifier.fillMaxSize().focusRequester(contentFocus)
        // Both guards must precede the group target; the blocked override must also
        // precede focusRestorer's own onEnter so restoration cannot bypass it.
        .then(if (blocked) Modifier.focusProperties { onEnter = { cancelFocusChange() } } else Modifier)
        .focusRestorer().focusGroup()
        .onPreviewKeyEvent { blocked }
        .then(if (blocked) Modifier.clearAndSetSemantics {} else Modifier)) {
        content()
    }
}
