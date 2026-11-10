package com.nuvio.tv.ui.screens.addon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.setup.FirstWatchAccountOutcome
import com.nuvio.tv.core.setup.FirstWatchPlaybackOutcome
import com.nuvio.tv.core.setup.FirstWatchSnapshot
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

@Composable
internal fun FirstWatchChecklist(snapshot: FirstWatchSnapshot, checkingAccounts: Boolean,
    onCheckAccounts: () -> Unit, onCancelCheck: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = System.currentTimeMillis(); delay(1_000L) }
        }
    }
    val readiness = snapshot.at(now)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.first_watch_title), style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary)
        Text(stringResource(R.string.first_watch_catalog, stringResource(
            if (readiness == null) R.string.first_watch_unknown else if (readiness.catalogAdded) R.string.first_watch_added else R.string.first_watch_missing)),
            style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
        Text(stringResource(R.string.first_watch_source, stringResource(
            if (readiness == null) R.string.first_watch_unknown else if (readiness.sourceAdded) R.string.first_watch_added else R.string.first_watch_missing)),
            style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
        Text(stringResource(R.string.first_watch_account_required), style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary)
        readiness?.accounts?.forEach { account ->
            val outcome = stringResource(when (account.outcome) {
                FirstWatchAccountOutcome.ACCEPTED -> R.string.first_watch_signin_accepted
                FirstWatchAccountOutcome.REJECTED -> R.string.first_watch_reconnect
                FirstWatchAccountOutcome.UNAVAILABLE -> R.string.first_watch_unavailable
                null -> R.string.first_watch_unknown
            })
            val status = stringResource(R.string.first_watch_saved_account,
                DebridProviders.displayName(account.providerId), outcome)
            Text(if (account.ageMs != null) "$status · ${firstWatchAge(account.ageMs)}" else status,
                style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
        }
        val playback = readiness?.playback
        val playbackText = if (playback != null) stringResource(
            if (playback.outcome == FirstWatchPlaybackOutcome.STARTED) R.string.first_watch_playback_started
            else R.string.first_watch_playback_failed, playback.title, firstWatchAge(readiness?.playbackAgeMs ?: 0L))
        else stringResource(R.string.first_watch_playback_unknown) +
            (readiness?.playbackAgeMs?.let { " · ${firstWatchAge(it)}" } ?: "")
        Text(playbackText, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
        if (readiness?.accounts?.isNotEmpty() == true) {
            Button(onClick = if (checkingAccounts) onCancelCheck else onCheckAccounts) {
                Text(stringResource(if (checkingAccounts) R.string.first_watch_cancel_check else R.string.first_watch_check_accounts))
            }
        }
    }
}

@Composable
private fun firstWatchAge(ageMs: Long): String = if (ageMs < 60_000L)
    stringResource(R.string.first_watch_seconds_ago, ageMs / 1_000L)
else stringResource(R.string.first_watch_minutes_ago, ageMs / 60_000L)
