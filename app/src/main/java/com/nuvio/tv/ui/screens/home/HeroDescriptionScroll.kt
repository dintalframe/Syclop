package com.nuvio.tv.ui.screens.home

import kotlin.math.ceil
import kotlinx.coroutines.delay

internal const val HeroDescriptionReadPauseMillis = 4_000L
private const val HeroDescriptionDpPerSecond = 12.0

internal data class HeroDescriptionScrollPlan(val distancePx: Int, val durationMillis: Int)

internal data class HeroDescriptionViewport(val heightPx: Int, val visibleLines: Int)

/** The hero's remaining height can be smaller than four lines on a compact TV layout. */
internal fun heroDescriptionViewport(
    lineBottomsPx: List<Float>,
    contentHeightPx: Int,
    maxLines: Int,
    availableHeightPx: Int
): HeroDescriptionViewport {
    if (contentHeightPx <= 0 || availableHeightPx <= 0 || maxLines <= 0) return HeroDescriptionViewport(0, 0)
    val visible = lineBottomsPx.take(maxLines).takeWhile {
        it.isFinite() && it > 0f && ceil(it.toDouble()) <= availableHeightPx
    }.size
    val height = when {
        visible == 0 -> minOf(contentHeightPx, availableHeightPx)
        visible == lineBottomsPx.size -> minOf(contentHeightPx, availableHeightPx)
        else -> ceil(lineBottomsPx[visible - 1].toDouble()).toInt()
    }
    return HeroDescriptionViewport(height, visible)
}

/** Keep a consistent reading speed across display densities; short descriptions never animate. */
internal fun heroDescriptionScrollPlan(
    contentHeightPx: Int,
    viewportHeightPx: Int,
    density: Float
): HeroDescriptionScrollPlan? {
    if (viewportHeightPx <= 0 || contentHeightPx <= viewportHeightPx ||
        !density.isFinite() || density <= 0f
    ) return null
    val distance = contentHeightPx - viewportHeightPx
    val duration = ceil(distance.toDouble() / density / HeroDescriptionDpPerSecond * 1_000)
        .coerceIn(1_000.0, Int.MAX_VALUE.toDouble()).toInt()
    return HeroDescriptionScrollPlan(distance, duration)
}

internal fun heroDescriptionMotionEnabled(scaleFactor: Float): Boolean =
    scaleFactor.isFinite() && scaleFactor > 0f

/** Owned by the visible, resumed hero effect. Cancellation stops both dwell and motion. */
internal suspend fun runHeroDescriptionScroll(
    plan: HeroDescriptionScrollPlan,
    readPauseMillis: Long,
    setScrolling: (Boolean) -> Unit,
    reset: suspend () -> Unit,
    scrollToEnd: suspend (HeroDescriptionScrollPlan) -> Unit
) {
    try {
        while (true) {
            setScrolling(false)
            reset()
            delay(readPauseMillis.coerceAtLeast(HeroDescriptionReadPauseMillis))
            setScrolling(true)
            scrollToEnd(plan)
            delay(readPauseMillis.coerceAtLeast(HeroDescriptionReadPauseMillis))
        }
    } finally {
        // The stationary ellipsis also hides a cancelled animation's intermediate offset.
        setScrolling(false)
    }
}
