package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
internal fun WatchedEpisodeBadge(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        modifier = modifier.padding(6.dp)
            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        maxLines = 1
    )
}
