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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.initialization.InitializationPhase
import com.nuvio.tv.core.initialization.InitializationStageStatus
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
    BackHandler(onBack = onContinue)
    LaunchedEffect(continueFocus) { withFrameNanos { }; continueFocus.requestFocus() }
    // Stay in the Activity's existing window: a brief account check need not create and
    // immediately tear down another ViewRoot while accessibility is inspecting startup.
    Column(modifier.fillMaxSize().background(Color.Black)
        .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
        .padding(horizontal = 56.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        SyclopBrandAnimation(if (state.phase == InitializationPhase.NEEDS_ATTENTION)
            SyclopBrandState.Error else SyclopBrandState.Loading, size = 96.dp)
        Text("Preparing your connected accounts", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("Importing your watch history and lists from connected services.",
            modifier = Modifier.widthIn(max = 700.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(12.dp))
        if (state.stages.isEmpty()) Text("Checking connected accounts…", color = Color.LightGray)
        state.stages.forEach { stage ->
            val detail = when (stage.status) {
                InitializationStageStatus.WAITING -> "Waiting"
                InitializationStageStatus.READING -> stage.step?.label ?: "Reading saved account data"
                InitializationStageStatus.COMPLETE -> if (stage.count == null) "Previously initialized"
                    else "${stage.count} entries read" + if (stage.truncated) " · partial history/list snapshot" else ""
                InitializationStageStatus.FAILED -> when (stage.failure) {
                    com.nuvio.tv.core.initialization.InitializationFailure.TIMEOUT -> "Timed out — retry or continue with saved data"
                    com.nuvio.tv.core.initialization.InitializationFailure.INCOMPLETE -> "Some data could not be imported — retry or continue"
                    com.nuvio.tv.core.initialization.InitializationFailure.UNAVAILABLE -> "Account data is not available yet"
                    else -> "Could not finish — retry or continue with saved data"
                }
            }
            Text("${stage.provider.label}: $detail", modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                color = if (stage.status == InitializationStageStatus.FAILED) Color(0xFFFFCCCC) else Color.White)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onContinue, modifier = Modifier.focusRequester(continueFocus)) { Text("Continue with saved data") }
            if (state.phase == InitializationPhase.NEEDS_ATTENTION) Button(onClick = onRetry) { Text("Retry failed imports") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Continue opens Home with any saved data.",
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
