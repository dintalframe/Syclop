package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Runs the actual scheduler, cue parser and matcher; only network/index and idle delivery are fakes. */
class AutoSyncLateCandidatePipelineTest {
    @After fun cleanup() {
        unmockkConstructor(AutoSyncLateCandidateWait::class)
        unmockkObject(EmbeddedSubtitleTimelineLoader)
        unmockkObject(AutoSyncSubtitleHttp)
    }

    @Test fun `startup scheduler tries and selects an addon arriving after the original pool failed`() = runTest {
        val selectedUrl = "https://example.invalid/late-pipeline-selected.srt"
        val lateUrl = "https://example.invalid/late-pipeline-alternative.srt"
        val initialAlternativeUrl = "https://example.invalid/late-pipeline-initial-alternative.srt"
        val source = "https://example.invalid/late-pipeline-source.mkv"
        val selected = AutoSyncSubtitleCandidate(selectedUrl, "en", "Original")
        val late = AutoSyncSubtitleCandidate(lateUrl, "en", "Late provider")
        val initialAlternative = AutoSyncSubtitleCandidate(initialAlternativeUrl, "en", "Initial provider")
        val random = Random(19)
        var start = 20_000L
        val reference = (1..80).map { index ->
            start += 4_000L + random.nextInt(6_000)
            SubtitleSyncCue(start, start + 500L + random.nextInt(1_000), "This is synthetic dialogue $index.")
        }
        var originalRequested = false
        var lateVisible = false
        var lateRequested = false
        var exhaustedPoolObserved = false
        mockkObject(EmbeddedSubtitleTimelineLoader)
        mockkObject(AutoSyncSubtitleHttp)
        mockkConstructor(AutoSyncLateCandidateWait::class)
        coEvery { EmbeddedSubtitleTimelineLoader.load(source, emptyMap()) } returns IndexedEmbeddedTimeline(
            tracks = listOf(ReferenceTrack("synthetic-embedded", "en", reference)),
            source = "synthetic-index", bytesDownloaded = 0, rangeRequests = 0, loadMs = 0)
        coEvery { AutoSyncSubtitleHttp.get(any(), any(), any(), any()) } answers {
            val url = firstArg<String>()
            val body = when (url) {
                selectedUrl, initialAlternativeUrl -> {
                    originalRequested = true
                    // The real matcher requires at least four cues: this initial pool cannot fit.
                    "1\n00:00:10,000 --> 00:00:11,000\nOne original cue.\n"
                }
                lateUrl -> {
                    assertTrue(exhaustedPoolObserved)
                    lateRequested = true
                    srt(reference)
                }
                else -> error("Unexpected synthetic subtitle request")
            }
            AutoSyncRawHttpResponse(200, "OK", url, body, emptyMap())
        }
        coEvery { anyConstructed<AutoSyncLateCandidateWait>().awaitNext(any()) } answers {
            assertTrue(originalRequested)
            // The production scheduler reaches this boundary only after loads and matcher
            // work for its initial pool are exhausted. Deliver a new provider result now.
            exhaustedPoolObserved = true
            lateVisible = true
            firstArg<() -> Boolean>().invoke()
        }

        val result = AutomaticSubtitleSync.findTimelineRetime(
            sourceKey = source, selectedSubtitleUrl = selectedUrl, selectedSubtitleHeaders = emptyMap(),
            preferredLanguage = "en", alternativeSubtitles = listOf(selected, initialAlternative),
            alternativeSubtitlesProvider = {
                if (lateVisible) listOf(selected, initialAlternative, late) else listOf(selected, initialAlternative)
            },
            allowLiveReference = false)

        assertTrue(exhaustedPoolObserved)
        assertTrue(lateRequested)
        assertEquals(lateUrl, result?.subtitleUrl)
        coVerify(exactly = 1) { AutoSyncSubtitleHttp.get(lateUrl, any(), any(), any()) }
    }

    private fun srt(cues: List<SubtitleSyncCue>) = cues.mapIndexed { index, cue ->
        "${index + 1}\n${timestamp(cue.startTimeMs)} --> ${timestamp(cue.endTimeMs)}\n${cue.text}\n"
    }.joinToString("\n")

    private fun timestamp(ms: Long) = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
        ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000)
}
