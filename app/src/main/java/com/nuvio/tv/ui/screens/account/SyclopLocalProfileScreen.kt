@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R

@Composable
internal fun SyclopLocalProfileScreen(onBackPress: () -> Unit) {
    BackHandler(onBack = onBackPress)
    Column(
        modifier = Modifier.fillMaxSize().padding(64.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.Start
    ) {
        Text(stringResource(R.string.syclop_local_profile_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.syclop_local_profile_description), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onBackPress) { Text(stringResource(R.string.syclop_back)) }
    }
}
