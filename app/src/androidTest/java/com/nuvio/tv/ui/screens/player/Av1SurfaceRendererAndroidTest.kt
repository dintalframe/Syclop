package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.av1.Libgav1VideoRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class Av1SurfaceRendererAndroidTest {
    private val stockRenderer = Libgav1VideoRenderer(0L, null, null, 50)
    private val surfaceRenderer = Av1SurfaceRenderer(stockRenderer)

    @Test
    fun clearEightBitAndUnspecifiedAv1KeepStockCapabilities() {
        val sdr = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorTransfer(C.COLOR_TRANSFER_SDR)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setLumaBitdepth(8)
            .setChromaBitdepth(8)
            .build()
        val supported = listOf(
            av1(codecs = "av01.0.04M.08", colorInfo = sdr),
            av1(codecs = "av01.0.04M.08.0.110.01.01.01.0"),
            av1(configuration = configuration(profile = 0, flags = 0x0c)),
            av1()
        )
        for (format in supported) {
            val original = stockRenderer.capabilities.supportsFormat(format)
            assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(original))
            assertEquals(original, surfaceRenderer.capabilities.supportsFormat(format))
        }
    }

    @Test
    fun rejectsHighDepthHdrAndNon420Av1BeforeSurfaceSelection() {
        val unsupported = listOf(
            "10-bit codec" to av1(codecs = "av01.0.04M.10"),
            "12-bit codec" to av1(codecs = "av01.2.04M.12"),
            "10-bit luma" to av1(colorInfo = ColorInfo.Builder().setLumaBitdepth(10).build()),
            "12-bit chroma" to av1(colorInfo = ColorInfo.Builder().setChromaBitdepth(12).build()),
            "PQ" to av1(colorInfo = ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()),
            "HLG" to av1(colorInfo = ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_HLG).build()),
            "10-bit av1C" to av1(configuration = configuration(profile = 0, flags = 0x4c)),
            "12-bit av1C" to av1(configuration = configuration(profile = 2, flags = 0x6c)),
            "4:4:4 av1C" to av1(configuration = configuration(profile = 1, flags = 0x00)),
            "monochrome av1C" to av1(configuration = configuration(profile = 0, flags = 0x1c)),
            "4:4:4 codec" to av1(codecs = "av01.1.04M.08.0.000.01.01.01.0"),
            "monochrome codec" to av1(codecs = "av01.0.04M.08.1.110.01.01.01.0")
        )
        for ((label, format) in unsupported) {
            assertEquals(label, C.FORMAT_HANDLED, support(stockRenderer.capabilities, format))
            assertEquals(label, C.FORMAT_UNSUPPORTED_SUBTYPE, support(surfaceRenderer.capabilities, format))
        }
    }

    @Test
    fun retainsStockUnsupportedTypeAndDrmResults() {
        val nonAv1 = av1(codecs = "av01.0.04M.10").buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .build()
        val drm = av1(codecs = "av01.0.04M.10").buildUpon()
            .setCryptoType(C.CRYPTO_TYPE_FRAMEWORK)
            .build()

        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, support(stockRenderer.capabilities, nonAv1))
        assertEquals(C.FORMAT_UNSUPPORTED_DRM, support(stockRenderer.capabilities, drm))
        for (format in listOf(nonAv1, drm)) {
            assertEquals(
                stockRenderer.capabilities.supportsFormat(format),
                surfaceRenderer.capabilities.supportsFormat(format)
            )
        }
    }

    @Test
    fun trackSelectorRoutesHighDepthToCapablePlatformInEitherOrder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val outcome = AtomicReference<Result<Unit>>()
        instrumentation.runOnMainSync {
            outcome.set(runCatching {
                val context = instrumentation.targetContext
                val selector = DefaultTrackSelector(context)
                val platform = CapablePlatformFixture()
                val format = av1(codecs = "av01.0.04M.10")
                try {
                    selector.init({}, DefaultBandwidthMeter.Builder(context)
                        .setResetOnNetworkTypeChange(false)
                        .build())
                    val capabilities = surfaceRenderer.capabilities
                    val groups = TrackGroupArray(TrackGroup("owned-av1", format))
                    for (renderers in listOf(arrayOf(capabilities, platform), arrayOf(platform, capabilities))) {
                        val selected = selector.selectTracks(
                            renderers, groups, MediaSource.MediaPeriodId("owned-av1"), Timeline.EMPTY
                        )
                        val platformIndex = renderers.indexOf(platform)
                        assertTrue(selected.isRendererEnabled(platformIndex))
                        assertFalse(selected.isRendererEnabled(1 - platformIndex))
                    }
                } finally {
                    selector.release()
                }
            })
        }
        outcome.get().getOrThrow()
    }

    private class CapablePlatformFixture : RendererCapabilities {
        override fun getName() = "Capable platform fixture"
        override fun getTrackType() = C.TRACK_TYPE_VIDEO
        override fun supportsFormat(format: Format) = RendererCapabilities.create(
            if (format.sampleMimeType == MimeTypes.VIDEO_AV1) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE
        )
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    }

    private fun av1(
        codecs: String? = null,
        colorInfo: ColorInfo? = null,
        configuration: ByteArray? = null
    ): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_AV1)
        .setCodecs(codecs)
        .setColorInfo(colorInfo)
        .setInitializationData(listOfNotNull(configuration))
        .setWidth(160)
        .setHeight(96)
        .build()

    private fun configuration(profile: Int, flags: Int) = byteArrayOf(
        0x81.toByte(), ((profile shl 5) or 4).toByte(), flags.toByte(), 0
    )

    private fun support(capabilities: RendererCapabilities, format: Format) =
        RendererCapabilities.getFormatSupport(capabilities.supportsFormat(format))
}
