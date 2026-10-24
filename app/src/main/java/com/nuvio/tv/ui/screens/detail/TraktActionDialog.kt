package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.nuvio.tv.R
import com.nuvio.tv.core.trakt.TraktActionTarget
import com.nuvio.tv.ui.components.NuvioDialog

data class TraktActionDialogState(
    val targets: List<TraktActionTarget>, val target: TraktActionTarget,
    val pending: Boolean = true, val ownRating: Int? = null, val ratingComplete: Boolean = false,
    val selectedRating: Int? = null, val activeCheckinTitle: String? = null,
    val checkinExpiresAt: String? = null, val confirmCancel: Boolean = false,
    val message: String? = null
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TraktActionDialog(state: TraktActionDialogState, onEvent: (MetaDetailsEvent) -> Unit) {
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(state.pending) { if (!state.pending) initialFocus.requestFocus() }
    NuvioDialog(onDismiss = { onEvent(MetaDetailsEvent.OnDismissTraktActions) },
        title = stringResource(R.string.trakt_actions_title), subtitle = state.target.title, width = 620.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.trakt_actions_manual_note))
            if (state.pending) Text(stringResource(R.string.trakt_actions_pending))
            state.message?.let { Text(it) }
            Text(if (state.ratingComplete) state.ownRating?.let { stringResource(R.string.trakt_actions_own_rating, it) }
                ?: stringResource(R.string.trakt_actions_unrated) else stringResource(R.string.trakt_actions_rating_unknown))
            for (row in (1..10).chunked(5)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { rating ->
                    Button(onClick = { onEvent(MetaDetailsEvent.OnSelectTraktRating(rating)) }, enabled = !state.pending,
                        modifier = if(rating == 1) Modifier.focusRequester(initialFocus) else Modifier) {
                        Text(if(state.selectedRating == rating) "✓ $rating" else rating.toString())
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onEvent(MetaDetailsEvent.OnSaveTraktRating) }, enabled = !state.pending && state.selectedRating != null) {
                    Text(stringResource(R.string.trakt_actions_save_rating))
                }
                Button(onClick = { onEvent(MetaDetailsEvent.OnClearTraktRating) }, enabled = !state.pending && state.ownRating != null) {
                    Text(stringResource(R.string.trakt_actions_clear_rating))
                }
            }
            if (state.target.canCheckin) Button(onClick = { onEvent(MetaDetailsEvent.OnTraktCheckin) }, enabled = !state.pending) {
                Text(stringResource(R.string.trakt_actions_checkin))
            }
            state.activeCheckinTitle?.let { title ->
                Text(stringResource(R.string.trakt_actions_active_checkin, title))
                state.checkinExpiresAt?.let { Text(it) }
                if (state.confirmCancel) {
                    Text(stringResource(R.string.trakt_actions_cancel_note))
                    Button(onClick = { onEvent(MetaDetailsEvent.OnConfirmCancelTraktCheckin) }, enabled = !state.pending) {
                        Text(stringResource(R.string.trakt_actions_confirm_cancel))
                    }
                } else Button(onClick = { onEvent(MetaDetailsEvent.OnRequestCancelTraktCheckin) }, enabled = !state.pending) {
                    Text(stringResource(R.string.trakt_actions_cancel_checkin))
                }
            }
            Button(onClick = { onEvent(MetaDetailsEvent.OnRefreshTraktActions) }, enabled = !state.pending) {
                Text(stringResource(R.string.trakt_actions_refresh))
            }
            if (state.targets.size > 1) {
                Text(stringResource(R.string.trakt_actions_select_target))
                state.targets.forEach { target ->
                    Button(onClick = { onEvent(MetaDetailsEvent.OnSelectTraktTarget(target)) }, enabled = !state.pending) { Text(target.title) }
                }
            }
        }
    }
}
