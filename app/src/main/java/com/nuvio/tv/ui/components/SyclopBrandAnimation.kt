package com.nuvio.tv.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.R
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SyclopBrandState { Loading, Error }

/** Decorative owner-supplied artwork. Keep the real status, error and actions in live UI text. */
@Composable
fun SyclopBrandAnimation(
    state: SyclopBrandState,
    modifier: Modifier = Modifier,
    size: Dp = 128.dp
) {
    val resources = LocalContext.current.resources
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pixels = with(LocalDensity.current) { size.roundToPx().coerceIn(1, 256) }
    var resumed by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var motionEnabled by remember { mutableStateOf(brandMotionEnabled()) }
    val artwork by produceState<Drawable?>(null, resources, state, pixels, motionEnabled) {
        // The still also serves as a local placeholder and the Android 7/8 and reduced-motion fallback.
        value = withContext(Dispatchers.IO) { decodeBrandStill(resources, state, pixels) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && motionEnabled) {
            val animated = withContext(Dispatchers.IO) {
                try {
                    BrandImageDecoder.decode(resources, state, pixels)
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            if (animated != null) value = animated
        }
    }
    val currentArtwork by rememberUpdatedState(artwork)

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed) motionEnabled = brandMotionEnabled()
            else (currentArtwork as? Animatable)?.stop()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            (currentArtwork as? Animatable)?.stop()
        }
    }
    DisposableEffect(artwork) {
        onDispose { (artwork as? Animatable)?.stop() }
    }

    AndroidView(
        factory = { BrandAnimationView(it) },
        modifier = modifier.size(size),
        update = { view ->
            view.bind(artwork, resumed && motionEnabled, playOnce = state == SyclopBrandState.Error)
        },
        onRelease = { view -> view.release() }
    )
}

private fun brandMotionEnabled(): Boolean {
    // The app supports Android 7; the system animation query was added in Android 8.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return ValueAnimator.areAnimatorsEnabled()
}

private fun decodeBrandStill(resources: Resources, state: SyclopBrandState, pixels: Int): Drawable? {
    val resource = when (state) {
        SyclopBrandState.Loading -> R.drawable.syclop_loading_dark_still
        SyclopBrandState.Error -> R.drawable.syclop_error_dark_still
    }
    // Both supplied stills are 512px. Avoid decoding their full resolution for a small TV status.
    var sample = 1
    while (512 / (sample * 2) >= pixels) sample *= 2
    val bitmap = BitmapFactory.decodeResource(resources, resource, BitmapFactory.Options().apply {
        inSampleSize = sample
        inScaled = false
    }) ?: return null
    return BitmapDrawable(resources, bitmap)
}

@RequiresApi(Build.VERSION_CODES.P)
private object BrandImageDecoder {
    fun decode(resources: Resources, state: SyclopBrandState, pixels: Int): Drawable {
        val resource = when (state) {
            SyclopBrandState.Loading -> R.raw.syclop_loading_dark
            SyclopBrandState.Error -> R.raw.syclop_error_dark
        }
        return ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources, resource)) { decoder, _, _ ->
            decoder.setTargetSize(pixels, pixels)
        }.also { drawable ->
            (drawable as? AnimatedImageDrawable)?.repeatCount = when (state) {
                SyclopBrandState.Loading -> AnimatedImageDrawable.REPEAT_INFINITE
                SyclopBrandState.Error -> 0
            }
        }
    }
}

/** Stops native frame scheduling when detached, hidden, paused or removed from composition. */
private class BrandAnimationView(context: Context) : ImageView(context) {
    private var motionAllowed = false
    private var playOnce = false
    private var started = false

    init {
        scaleType = ScaleType.FIT_CENTER
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(artwork: Drawable?, allowMotion: Boolean, playOnce: Boolean) {
        if (drawable !== artwork) {
            (drawable as? Animatable)?.stop()
            setImageDrawable(artwork)
            started = false
        }
        motionAllowed = allowMotion
        this.playOnce = playOnce
        reconcileAnimation()
    }

    fun release() {
        motionAllowed = false
        (drawable as? Animatable)?.stop()
        setImageDrawable(null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reconcileAnimation()
    }

    override fun onDetachedFromWindow() {
        (drawable as? Animatable)?.stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        reconcileAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        reconcileAnimation()
    }

    private fun reconcileAnimation() {
        val animation = drawable as? Animatable ?: return
        if (!motionAllowed || !isAttachedToWindow || !isShown || windowVisibility != VISIBLE) {
            animation.stop()
        } else if (!playOnce || !started) {
            animation.start()
            started = true
        }
    }
}
