package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.contentTextDirection
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CompactGridHomeSummary(
    focusedKey: State<String?>,
    itemsByKey: Map<String, GridItem>,
    firstKey: String?,
    onContinueWatching: (() -> Unit)?
) {
    val colors = NuvioTheme.colors
    val key = focusedKey.value
    var settledKey by remember { mutableStateOf(key) }
    LaunchedEffect(key) {
        if (itemsByKey[key] is GridItem.Content || itemsByKey[key] is GridItem.CollectionFolder) {
            delay(135)
            settledKey = key
        }
    }
    val item = itemsByKey[settledKey].takeIf { it is GridItem.Content || it is GridItem.CollectionFolder }
        ?: itemsByKey[firstKey]
    val title = when (item) {
        is GridItem.Content -> item.item.name
        is GridItem.CollectionFolder -> item.folder.title
        else -> ""
    }
    val description = when (item) {
        is GridItem.Content -> item.item.description?.takeIf(String::isNotBlank)
            ?: item.item.releaseInfo.orEmpty()
        is GridItem.CollectionFolder -> item.collectionTitle
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.BackgroundElevated)
            .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.syclop_primary_head_512),
            contentDescription = null,
            modifier = Modifier.size(34.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = title.contentTextDirection()),
                color = colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = description.contentTextDirection()),
                color = colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onContinueWatching != null) {
            Button(
                onClick = onContinueWatching,
                colors = ButtonDefaults.colors(
                    containerColor = colors.BackgroundCard,
                    contentColor = colors.TextPrimary,
                    focusedContainerColor = colors.FocusRing,
                    focusedContentColor = colors.Background
                )
            ) {
                Text(stringResource(R.string.continue_watching), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
