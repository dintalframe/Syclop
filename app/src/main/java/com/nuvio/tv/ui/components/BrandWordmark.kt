package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.InterFamily

@Composable
@Suppress("UNUSED_PARAMETER") // Keep callers source-compatible with the upstream image API.
fun BrandWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1f,
    drawableOverride: Int? = null
) {
    // An original text identity, independent of upstream theme-specific raster wordmarks.
    BoxWithConstraints(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .semantics { this.contentDescription = contentDescription ?: "Syclop" },
        contentAlignment = Alignment.Center
    ) {
        val wordmarkSize = if (maxHeight.value.isFinite()) {
            (maxHeight.value * 0.76f).coerceIn(16f, 44f)
        } else 32f
        Text(
            text = "Syclop",
            color = Color(0xFFF2F2F7),
            fontFamily = InterFamily,
            fontWeight = FontWeight.Light,
            fontSize = wordmarkSize.sp,
            letterSpacing = (-0.6).sp,
            maxLines = 1
        )
    }
}
