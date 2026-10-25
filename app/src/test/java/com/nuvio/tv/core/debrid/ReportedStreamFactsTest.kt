package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ReportedStreamFactsTest {
    @Test fun `behavior filename automatically exposes 4K HEVC and fps without imported badges`() {
        val source = source("Big Buck Bunny").copy(behaviorHints = hints("Big.Buck.Bunny.4K.x265.60fps.mkv"))
        assertEquals("4K (2160p) · HEVC · 60 fps", ReportedStreamFacts.qualitySummary(source))
        assertNull(source.nativeFileMetadata)
    }
    @Test fun `explicit audio layouts are reported but channel counts cannot invent LFE`() {
        assertEquals("1080p · AAC · 7.1 · 5.1 · 2.1", ReportedStreamFacts.qualitySummary(source("1080p AAC 2.1 5.1 7.1")))
        assertTrue(ReportedStreamFacts.qualitySummary(source("1080p AAC 2.1"))!!.contains("2.1"))
        assertFalse(ReportedStreamFacts.qualitySummary(source("1080p AAC 6ch"))!!.contains("5.1"))
        assertTrue(ReportedStreamFacts.qualitySummary(source("1080p Stereo"))!!.contains("Stereo"))
    }
    @Test fun `reported video claims never claim checked tracks or invent missing HDR`() {
        val label = ReportedStreamFacts.qualitySummary(source("1080p x264"))!!
        assertEquals("1080p · AVC", label)
        assertFalse(label.contains("HDR"))
        assertNull(ReportedStreamFacts.qualitySummary(source("A plain movie title")))
    }
    @Test fun `supported bit units are normalized to bps and byte rates remain unknown`() {
        for ((text, expected) in listOf("75.25 Mbps" to 75_250_000L, "12500 kbit/s" to 12_500_000L,
            "5000 kbps" to 5_000_000L, "0.5 Gbps" to 500_000_000L, "1 Mbit/s" to 1_000_000L)) {
            assertEquals(expected, ReportedStreamFacts.explicitBitsPerSecond(source(text)))
        }
        for (text in listOf("25 MB/s", "25 MBps", "60fps", "-1 Mbps", "0 Mbps", "10001 Mbps", "9999999 Mbps", "NaN Mbps", "1e9 Mbps")) {
            assertNull(text, ReportedStreamFacts.explicitBitsPerSecond(source(text)))
        }
    }
    @Test fun `reported Mbps cannot become native bitrate evidence`() {
        val source = source("75 Mbps 4K")
        assertNull(StreamBitrateFacts.estimatedBitsPerSecond(source))
        assertEquals("Reported bitrate: 75.0 Mbps (unverified)", ReportedStreamFacts.bitrateLabel(source))
    }
    @Test fun `single movie reported size and confirmed runtime produce clearly unverified average`() {
        val movie = source("Movie 1.5 GB").copy(reportedMovieRuntimeMillis = 120_000L,
            behaviorHints = hints(size = 1_500_000_000L))
        assertEquals(100_000_000L, ReportedStreamFacts.averageBitsPerSecond(movie))
        assertEquals("Reported avg: 100.0 Mbps (reported size / movie runtime; unverified)", ReportedStreamFacts.bitrateLabel(movie))
        assertNull(StreamBitrateFacts.estimatedBitsPerSecond(movie))
    }
    @Test fun `missing movie runtime and packs cannot yield reported average`() {
        assertNull(ReportedStreamFacts.averageBitsPerSecond(source("Movie 100 GB")))
        for (title in listOf("Movie Season Pack 100 GB", "Movie Collection 100 GB", "Movie Complete 100 GB", "S01 100 GB", "Series.S01E01.10GB.mkv", "Series.S01E01E02.10GB.mkv", "Series.1x02.10GB.mkv", "Series.E01.10GB.mkv")) {
            assertNull(ReportedStreamFacts.averageBitsPerSecond(source(title).copy(reportedMovieRuntimeMillis = 120_000L)))
        }
    }
    @Test fun `invalid duration size and implausible average remain unknown`() {
        for (duration in listOf(0L, -1L, 59_999L, 86_400_001L)) {
            assertNull(ReportedStreamFacts.averageBitsPerSecond(source("Movie 1 GB").copy(reportedMovieRuntimeMillis = duration)))
        }
        assertNull(ReportedStreamFacts.averageBitsPerSecond(source("Movie").copy(reportedMovieRuntimeMillis = 60_000L,
            behaviorHints = hints(size = Long.MAX_VALUE))))
    }
    @Test fun `explicit reported rate takes priority over reported average within reported tier`() {
        val movie = source("Movie 20 Mbps 1 GB").copy(reportedMovieRuntimeMillis = 120_000L)
        assertEquals(20_000_000L, ReportedStreamFacts.rankingBitsPerSecond(movie))
    }
    @Test fun `native tier beats higher reported rate and unknown in either direction`() {
        val native = source("1080p").copy(nativeFileMetadata = StreamFileMetadata(100_000L, 1_000L, StreamDurationOrigin.CATALOG_MOVIE))
        val reported = source("2160p 999 Mbps")
        val unknown = source("2160p")
        for (direction in DebridStreamSortDirection.entries) {
            val settings = settings().copy(streamPreferences = DebridStreamPreferences(sortCriteria = listOf(
                DebridStreamSortCriterion(DebridStreamSortKey.BITRATE, direction))))
            assertEquals(listOf(native, reported, unknown), DirectDebridStreamFilter.applyPreferences(listOf(unknown, reported, native), settings))
        }
    }
    @Test fun `reported bitrate and single movie averages rank descending within reported tier`() {
        val high = source("1080p 20 Mbps")
        val low = source("4K 10 Mbps")
        val average = source("Movie").copy(reportedMovieRuntimeMillis = 120_000L, behaviorHints = hints(size = 225_000_000L))
        assertEquals(listOf(high, average, low), DirectDebridStreamFilter.applyPreferences(listOf(low, average, high), settings()))
    }
    @Test fun `Original and explicit custom nonbitrate order are preserved`() {
        val first = source("1080p 5 Mbps"); val second = source("4K 20 Mbps")
        val original = settings().copy(streamPreferences = DebridStreamPreferences(sortCriteria = emptyList()))
        assertEquals(listOf(first, second), DirectDebridStreamFilter.applyPreferences(listOf(first, second), original))
        val custom = original.copy(streamPreferences = original.streamPreferences.copy(sortCriteria = listOf(
            DebridStreamSortCriterion(DebridStreamSortKey.RESOLUTION, DebridStreamSortDirection.ASC))))
        assertEquals(listOf(first, second), DirectDebridStreamFilter.applyPreferences(listOf(second, first), custom))
    }
    private fun settings() = DebridSettings(enabled = true, allDebridApiKey = "fixture-key", preferredResolverProviderId = "alldebrid",
        streamPreferences = DebridStreamPreferences(sortCriteria = DebridStreamSortCriterion.bitrateFirstOrder()))
    private fun source(title: String) = Stream(null, title, null, null, null, "a".repeat(40), 0, null, null, "Magnify", null)
    private fun hints(filename: String? = null, size: Long? = null) = StreamBehaviorHints(null, null, null, null, filename = filename, videoSize = size)
}
