package com.nuvio.tv.ui.components

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class SourceLabelPaletteTest {
    @Test fun `service colors are distinct and white labels meet readable text contrast`() {
        val colors = listOf("realdebrid", "torbox", "alldebrid", "premiumize").map {
            SourceLabelPalette.background("", "native-service:$it") }
        assertEquals(4, colors.toSet().size)
        val all = colors + listOf("1080p", "HDR10", "HEVC", "Atmos 5.1", "4 GB", "WEB-DL").map {
            SourceLabelPalette.background(it) }
        all.forEach { color ->
            fun channel(shift: Int): Double {
                val value = ((color shr shift) and 255).toDouble() / 255
                return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
            }
            val luminance = .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
            assertTrue("White text contrast must be at least 4.5", 1.05 / (luminance + .05) >= 4.5)
        }
    }
}
