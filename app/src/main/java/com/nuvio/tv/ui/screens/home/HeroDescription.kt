package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** A four-line preview that reads through overflow after the selected hero has settled. */
@Composable
internal fun HeroDescription(
    description: String,
    contentKey: Any,
    autoScrollEnabled: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 4
) {
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val accessibility = LocalAccessibilityManager.current
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (!constraints.hasBoundedWidth || constraints.maxWidth <= 0) {
            Text(description, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            return@BoxWithConstraints
        }
        val width = constraints.maxWidth
        val layout = measurer.measure(
            text = description,
            style = style,
            constraints = Constraints(maxWidth = width)
        )
        val viewport = heroDescriptionViewport(
            lineBottomsPx = List(layout.lineCount) { layout.getLineBottom(it) },
            contentHeightPx = layout.size.height,
            maxLines = maxLines,
            availableHeightPx = constraints.maxHeight
        )
        val plan = if (viewport.visibleLines > 0)
            heroDescriptionScrollPlan(layout.size.height, viewport.heightPx, density.density) else null
        val offset = remember(contentKey, description, width, style) { Animatable(0f) }
        var scrolling by remember(contentKey, description, width, style) { mutableStateOf(false) }
        var visible by remember { mutableStateOf(false) }

        LaunchedEffect(contentKey, description, plan, autoScrollEnabled, windowFocused, visible, lifecycle, accessibility) {
            scrolling = false
            offset.snapTo(0f)
            if (plan == null || !autoScrollEnabled || !windowFocused || !visible) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val motion = currentCoroutineContext()[MotionDurationScale]
                snapshotFlow { motion?.scaleFactor ?: 1f }.distinctUntilChanged().collectLatest { scale ->
                    scrolling = false
                    offset.snapTo(0f)
                    if (heroDescriptionMotionEnabled(scale)) {
                        val pause = accessibility?.calculateRecommendedTimeoutMillis(
                            originalTimeoutMillis = HeroDescriptionReadPauseMillis,
                            containsText = true
                        ) ?: HeroDescriptionReadPauseMillis
                        runHeroDescriptionScroll(plan, pause, { scrolling = it }, { offset.snapTo(0f) }) {
                            offset.animateTo(it.distancePx.toFloat(), tween(it.durationMillis, easing = LinearEasing))
                        }
                    }
                }
            }
        }

        Box(
            Modifier
                .height(with(density) { viewport.heightPx.toDp() })
                .clipToBounds()
                .onGloballyPositioned { visible = !it.boundsInWindow().isEmpty }
                // One stationary semantic node exposes the whole description, without
                // scroll actions that would steal D-pad navigation or repeated announcements.
                .clearAndSetSemantics { text = AnnotatedString(description) }
        ) {
            Text(
                description,
                color = color,
                style = style,
                maxLines = if (scrolling) Int.MAX_VALUE else viewport.visibleLines.coerceAtLeast(1),
                overflow = if (scrolling) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .graphicsLayer { translationY = if (scrolling) -offset.value else 0f }
            )
        }
    }
}
