package com.nuvio.tv.ui.screens.party

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.nuvio.tv.R
import com.nuvio.tv.core.party.PartyEvent
import com.nuvio.tv.core.party.PartyRepository

@Composable
fun PartyEffects(repository: PartyRepository) {
    val context = LocalContext.current
    val resources = LocalResources.current
    LifecycleEventEffect(Lifecycle.Event.ON_START) { repository.refreshAvailability() }
    LaunchedEffect(repository, context) {
        repository.events.collect { event ->
            val message = when (event) {
                is PartyEvent.MemberJoined -> resources.getString(R.string.party_member_joined, event.name)
                is PartyEvent.MemberLeft -> resources.getString(R.string.party_member_left, event.name)
                PartyEvent.Removed -> resources.getString(R.string.party_removed)
                PartyEvent.Ended -> resources.getString(R.string.party_ended)
                is PartyEvent.MediaChanged -> resources.getString(R.string.party_media_changed, event.media.title)
                is PartyEvent.ControlChanged -> resources.getString(
                    if (event.hostOnly) R.string.party_control_host_only else R.string.party_control_everyone,
                )
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
