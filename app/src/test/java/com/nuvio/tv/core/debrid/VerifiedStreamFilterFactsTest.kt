package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.mediafacts.SourceMediaFormatFacts
import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/** Exercise the production facts and preference filters with real native-format projections. */
class VerifiedStreamFilterFactsTest {
    @Test fun `inspected SDR AVC stereo replaces contradictory advertised DV HEVC Atmos and 7 point 1`() {
        val stream = source(CLAIMS, inspectedAvcStereo())
        val facts = DirectDebridStreamFilter.facts(stream, settings())
        assertEquals(DebridStreamResolution.P1080, facts.resolution)
        assertEquals(DebridStreamEncode.AVC, facts.encode)
        assertTrue(DebridStreamVisualTag.SDR in facts.visualTags)
        assertTrue(DebridStreamVisualTag.IMAX in facts.visualTags) // Projection is not a native track field.
        assertTrue(facts.visualTags.none { it in setOf(DebridStreamVisualTag.DV, DebridStreamVisualTag.DV_ONLY,
            DebridStreamVisualTag.HDR, DebridStreamVisualTag.HDR10, DebridStreamVisualTag.HDR10_PLUS, DebridStreamVisualTag.HDR_DV) })
        assertEquals(listOf(DebridStreamAudioTag.AAC), facts.audioTags)
        assertEquals(listOf(DebridStreamAudioChannel.CH_2_0), facts.audioChannels)
    }

    @Test fun `actual preferences use inspected quality rather than false release claims`() {
        val reported = source(CLAIMS)
        val inspected = source(CLAIMS, inspectedAvcStereo())
        val advertised = settings(DebridStreamPreferences(requiredVisualTags = listOf(DebridStreamVisualTag.DV),
            requiredEncodes = listOf(DebridStreamEncode.HEVC), requiredAudioChannels = listOf(DebridStreamAudioChannel.CH_7_1)))
        assertEquals(listOf(reported), DirectDebridStreamFilter.applyPreferences(listOf(inspected, reported), advertised))
        val observed = settings(DebridStreamPreferences(requiredVisualTags = listOf(DebridStreamVisualTag.SDR),
            requiredEncodes = listOf(DebridStreamEncode.AVC), requiredAudioChannels = listOf(DebridStreamAudioChannel.CH_2_0)))
        assertEquals(listOf(inspected), DirectDebridStreamFilter.applyPreferences(listOf(reported, inspected), observed))
    }

    @Test fun `TrueHD and DTS HD inspection cannot inherit advertised Atmos DTS X or lossless MA extensions`() {
        val trueHd = DirectDebridStreamFilter.facts(source("TrueHD Atmos 7.1", audio("audio/true-hd")), settings())
        assertEquals(listOf(DebridStreamAudioTag.TRUEHD), trueHd.audioTags)
        val dtsHd = DirectDebridStreamFilter.facts(source("DTS-HD MA DTS:X", audio("audio/vnd.dts.hd")), settings())
        assertEquals(listOf(DebridStreamAudioTag.DTS_HD), dtsHd.audioTags)
        assertFalse(DebridStreamAudioTag.ATMOS in trueHd.audioTags)
        assertFalse(DebridStreamAudioTag.DTS_HD_MA in dtsHd.audioTags)
        assertFalse(DebridStreamAudioTag.DTS_X in dtsHd.audioTags)
    }

    @Test fun `channel count without speaker layout cannot become verified 5 point 1 from a filename`() {
        val stream = source("AAC 5.1", audio("audio/aac", count = 6))
        assertEquals(listOf(DebridStreamAudioChannel.UNKNOWN), DirectDebridStreamFilter.facts(stream, settings()).audioChannels)
        assertTrue(DirectDebridStreamFilter.applyPreferences(listOf(stream), settings(DebridStreamPreferences(
            requiredAudioChannels = listOf(DebridStreamAudioChannel.CH_5_1)))).isEmpty())
    }

    @Test fun `unsupported inspected codecs and layouts remain unknown instead of borrowing filename tags`() {
        val native = SourceMediaFormatFacts.from(listOf(
            mapOf("mime" to "video/x-vnd.on2.vp9", "width" to 1920, "height" to 1080, "color-transfer" to 3),
            mapOf("mime" to "audio/raw", "channel-count" to 1, "channel-mask" to 4)))!!
        val facts = DirectDebridStreamFilter.facts(source("AV1 AAC 7.1", native), settings())
        assertEquals(DebridStreamEncode.UNKNOWN, facts.encode)
        assertEquals(listOf(DebridStreamAudioTag.UNKNOWN), facts.audioTags)
        assertEquals(listOf(DebridStreamAudioChannel.UNKNOWN), facts.audioChannels)
    }

    @Test fun `missing codec and colour data on an inspected video cannot preserve advertised HEVC Dolby Vision`() {
        val native = SourceMediaFormatFacts.from(listOf(mapOf("mime" to "video/x-future",
            "width" to 1920, "height" to 1080)))!!
        val facts = DirectDebridStreamFilter.facts(source("HEVC Dolby Vision", native), settings())
        assertEquals(DebridStreamEncode.UNKNOWN, facts.encode)
        assertEquals(listOf(DebridStreamVisualTag.UNKNOWN), facts.visualTags)
    }

    @Test fun `track types absent from inspection retain their clearly reported fields`() {
        val videoOnly = inspectedAvcStereo().copy(audio = emptyList())
        val videoFacts = DirectDebridStreamFilter.facts(source("HEVC HDR10 AAC 5.1", videoOnly), settings())
        assertEquals(DebridStreamEncode.AVC, videoFacts.encode)
        assertEquals(listOf(DebridStreamAudioTag.AAC), videoFacts.audioTags)
        assertEquals(listOf(DebridStreamAudioChannel.CH_5_1), videoFacts.audioChannels)
        val audioOnly = inspectedAvcStereo().copy(video = null)
        val audioFacts = DirectDebridStreamFilter.facts(source("HEVC HDR10 Atmos 7.1", audioOnly), settings())
        assertEquals(DebridStreamEncode.HEVC, audioFacts.encode)
        assertTrue(DebridStreamVisualTag.HDR10 in audioFacts.visualTags)
        assertEquals(listOf(DebridStreamAudioTag.AAC), audioFacts.audioTags)
        assertEquals(listOf(DebridStreamAudioChannel.CH_2_0), audioFacts.audioChannels)
    }

    @Test fun `multiple inspected tracks expose only their confirmed codecs and layouts`() {
        val native = SourceMediaFormatFacts.from(listOf(
            mapOf("mime" to "audio/aac", "channel-count" to 2, "channel-mask" to 12),
            mapOf("mime" to "audio/eac3-joc", "channel-count" to 6, "channel-mask" to 252)))!!
        val facts = DirectDebridStreamFilter.facts(source("DTS-HD MA 7.1", native), settings())
        assertEquals(listOf(DebridStreamAudioTag.AAC, DebridStreamAudioTag.DD_PLUS, DebridStreamAudioTag.ATMOS), facts.audioTags)
        assertEquals(listOf(DebridStreamAudioChannel.CH_2_0, DebridStreamAudioChannel.CH_5_1), facts.audioChannels)
        assertFalse(DebridStreamAudioTag.DTS_HD_MA in facts.audioTags)
    }

    private fun inspectedAvcStereo() = SourceMediaFormatFacts.from(listOf(
        mapOf("mime" to "video/avc", "width" to 1920, "height" to 1080, "color-transfer" to 3),
        mapOf("mime" to "audio/aac", "channel-count" to 2, "channel-mask" to 12)))!!
    private fun audio(mime: String, count: Int? = null) = SourceMediaFormatFacts.from(listOf(
        buildMap<String, Any> { put("mime", mime); count?.let { put("channel-count", it) } }))!!
    private fun source(title: String, inspected: VerifiedSourceMediaFacts? = null) = Stream(null, title, null,
        null, null, "a".repeat(40), null, null, null, "Synthetic", null,
        nativeFileMetadata = inspected?.let { StreamFileMetadata(verifiedTracks = it) })
    private fun settings(preferences: DebridStreamPreferences = DebridStreamPreferences()) =
        DebridSettings(streamPreferences = preferences)
    private companion object { const val CLAIMS = "2160p HEVC Dolby Vision HDR10+ IMAX Atmos TrueHD 7.1" }
}
