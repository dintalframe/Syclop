package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.debrid.DebridBitrateMetadataService
import com.nuvio.tv.core.debrid.DebridStreamFormatter
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.DebridStreamTemplateEngine
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.debrid.ReportedStreamFacts
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlayerSourcePanelMetadataTest {
    private val movie = PlayerSourceMetadataContext("tt1234567", "movie", null, null, 600_000L, StreamDurationOrigin.CATALOG_MOVIE)
    private val settings = DebridSettings(enabled = true, allDebridApiKey = "synthetic-key",
        preferredResolverProviderId = "alldebrid", streamPreferences = DebridStreamPreferences(
            sortCriteria = DebridStreamSortCriterion.bitrateFirstOrder()))
    private val ranking = DebridStreamPresentation(mockk(), mockk(), DebridStreamFormatter(DebridStreamTemplateEngine())) { false }

    @Test fun `main movie size-runtime reported estimate appears in Sources without changing raw stream`() {
        val raw = source("a").copy(behaviorHints = StreamBehaviorHints(null, null, null, null, videoSize = 2_670_000_000L))
        val subject = PlayerSourcePanelMetadata { _, _, _ -> emptyList() }
        val shown = subject.displayStreams(request(listOf(raw)), PlayerSourceMetadataSnapshot()).single()
        assertEquals(35_600_000L, ReportedStreamFacts.averageBitsPerSecond(shown))
        assertTrue(ReportedStreamFacts.bitrateLabel(shown)!!.contains("35.6 Mbps"))
        assertNull(raw.reportedMovieRuntimeMillis)
        assertEquals(raw, shown.copy(reportedMovieRuntimeMillis = null))
        assertEquals(raw.stableKey(0), shown.stableKey(0))
    }

    @Test fun `cache-only native facts outrank reported facts and unknown remains last globally`() = runTest {
        val unknown = source("a")
        val reported = source("b").copy(behaviorHints = StreamBehaviorHints(null, null, null, null, videoSize = 2_670_000_000L))
        val native = source("c")
        val metadata = StreamFileMetadata(selectedFileBytes = 1_000_000_000L, durationMillis = 100_000L,
            durationOrigin = StreamDurationOrigin.NATIVE_CONTAINER)
        val subject = PlayerSourcePanelMetadata { streams, context, profile ->
            assertEquals(movie, context); assertEquals(1, profile)
            listOf(streams.single { it.infoHash == native.infoHash } to native.copy(nativeFileMetadata = metadata))
        }
        val request = request(listOf(unknown, reported, native))
        val snapshot = subject.enrich(request, { ranking.rankAll(it, settings) }) { true }
        val shown = ranking.rankAll(subject.displayStreams(request, snapshot), settings)
        assertEquals(listOf(native.infoHash, reported.infoHash, unknown.infoHash), shown.map { it.infoHash })
        assertEquals(listOf(unknown, reported, native), request.streams)
        assertNull(native.nativeFileMetadata)
    }

    @Test fun `episode pack never gains movie size divided by episode duration`() {
        val pack = source("a").copy(title = "Series S01 Complete", behaviorHints = StreamBehaviorHints(null, null, null, null, videoSize = 20_000_000_000L))
        val context = movie.copy(nativeVideoId = "mal:59978:1", mediaType = "series", season = 1, episode = 1,
            durationOrigin = StreamDurationOrigin.CATALOG_EPISODE)
        val shown = PlayerSourcePanelMetadata { _, _, _ -> emptyList() }
            .displayStreams(request(listOf(pack)).copy(context = context), PlayerSourceMetadataSnapshot()).single()
        assertNull(shown.reportedMovieRuntimeMillis)
        assertNull(ReportedStreamFacts.averageBitsPerSecond(shown))
    }

    @Test fun `cached runtime requires exact metadata identity and exact episode ID and coordinates`() {
        val meta = meta("tt1234567", "10 min")
        assertEquals(600_000L, PlayerSourcePanelMetadata.context("movie", "tt1234567", null, null, meta.id, meta).durationMillis)
        assertNull(PlayerSourcePanelMetadata.context("movie", "tt7654321", null, null, "tt7654321", meta).durationMillis)
        assertNull(PlayerSourcePanelMetadata.context("series", "tt1234567:1:1", 1, 1, meta.id, meta).durationMillis)
        val episode = Video(id = "mal:59978:1", title = "Episode", released = null, thumbnail = null,
            season = 1, episode = 1, overview = null, runtime = 24)
        val anime = meta("mal:59978", "10 min").copy(type = ContentType.SERIES, rawType = "series", videos = listOf(episode))
        val exact = PlayerSourcePanelMetadata.context("series", "mal:59978:1", 1, 1, anime.id, anime)
        assertEquals(1_440_000L, exact.durationMillis)
        assertEquals(StreamDurationOrigin.CATALOG_EPISODE, exact.durationOrigin)
        assertEquals("mal:59978:1", exact.nativeVideoId)
        assertNull(PlayerSourcePanelMetadata.context("series", "mal:59978:2", 1, 1, anime.id, anime).durationMillis)
    }

    @Test fun `malformed negative fractional overflow or excessive runtime stays unknown`() {
        for (runtime in listOf("-10 min", "+10 min", "10.5 min", "71582790h", "99999999999999999999 min", "0", "1441 min")) {
            val metadata = meta("tt1234567", runtime)
            assertNull(runtime, PlayerSourcePanelMetadata.context("movie", metadata.id, null, null, metadata.id, metadata).durationMillis)
        }
    }

    @Test fun `stale before and after cached lookup prevents publication without accepting foreign stream values`() = runTest {
        val raw = source("a")
        var current = false
        var calls = 0
        val subject = PlayerSourcePanelMetadata { _, _, _ ->
            calls++; current = false
            listOf(raw to raw.copy(url = "https://synthetic-debrid.example/expiring-link", infoHash = null,
                nativeFileMetadata = StreamFileMetadata(selectedFileBytes = 1_000)))
        }
        assertNull(subject.enrich(request(listOf(raw)), { it }) { current }.request)
        assertEquals(0, calls)
        current = true
        assertNull(subject.enrich(request(listOf(raw)), { it }) { current }.request)
        assertEquals(1, calls)
        assertNull(raw.url)
    }

    @Test fun `cached signed URL headers or source identity never replace raw resolver input`() = runTest {
        val raw = source("a")
        val subject = PlayerSourcePanelMetadata { streams, _, _ -> listOf(streams.single() to raw.copy(
            url = "https://synthetic-debrid.example/private-link", infoHash = null,
            nativeFileMetadata = StreamFileMetadata(selectedFileBytes = 2_000))) }
        val input = request(listOf(raw))
        val snapshot = subject.enrich(input, { it }) { true }
        val shown = subject.displayStreams(input, snapshot).single()
        assertNull(shown.url); assertEquals(raw.infoHash, shown.infoHash)
        assertEquals(raw.clientResolve, shown.clientResolve)
        assertEquals(2_000L, shown.nativeFileMetadata!!.selectedFileBytes)
        assertSame(raw, input.streams.single())
    }

    @Test fun `new source or credential revision cannot reuse an old display snapshot`() = runTest {
        val raw = source("a")
        val subject = PlayerSourcePanelMetadata { streams, _, _ -> listOf(streams.single() to raw.copy(
            nativeFileMetadata = StreamFileMetadata(selectedFileBytes = 2_000))) }
        val input = request(listOf(raw))
        val snapshot = subject.enrich(input, { it }) { true }
        for (changed in listOf(input.copy(credentialRevision = 2), input.copy(selectionRevision = 3),
            input.copy(mediaOriginKey = "different-media"), input.copy(context = movie.copy(nativeVideoId = "tt7654321"))))
            assertNull(subject.displayStreams(changed, snapshot).single().nativeFileMetadata)
    }

    @Test fun `actual cache-only service forwards native context and never resolves unknown raw hash`() = runTest {
        val store = mockk<DebridSettingsDataStore> {
            every { activeProfileId } returns MutableStateFlow(1)
            every { activeProfileSelectionRevision } returns 0L
            every { activeCredentialRevision } returns 1L
        }
        val resolver = mockk<DirectDebridResolver>()
        val raw = source("a")
        coEvery { resolver.cachedPlayableStream(any(), null, null, "tt1234567", "movie") } returns null
        val service = DebridBitrateMetadataService(resolver, store)
        val subject = PlayerSourcePanelMetadata { streams, context, profile -> service.enrichKnownFiles(streams,
            context.durationMillis, context.durationOrigin, context.season, context.episode, profile,
            context.nativeVideoId, context.mediaType) }
        assertTrue(subject.enrich(request(listOf(raw)), { it }) { true }.metadata.isEmpty())
        coVerify(exactly = 1) { resolver.cachedPlayableStream(any(), null, null, "tt1234567", "movie") }
        coVerify(exactly = 0) { resolver.resolve(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `cancellation propagates and only twelve ranked candidates are inspected`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        val subject = PlayerSourcePanelMetadata { streams, _, _ ->
            assertEquals(12, streams.size); entered.complete(Unit); wait.await(); emptyList()
        }
        val task = async { subject.enrich(request((0..20).map { source(('a'.code + it).toChar().toString()) }), { it }) { true } }
        entered.await(); task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertFalse(wait.isCompleted)
    }

    @Test fun `playing raw source keeps one of twelve cache slots even below the current rank window`()=runTest {
        val streams=(0..20).map { source(('a'.code+it).toChar().toString()) }
        val playing=streams.last()
        val capture=com.nuvio.tv.core.debrid.ActivePlayerFileDuration(1,0,1,movie.nativeVideoId,movie.mediaType,
            null,null,"a".repeat(64),600_000L,playing.stableKey(0))
        var inspected=emptyList<Stream>()
        val subject=PlayerSourcePanelMetadata { candidates,_,_ -> inspected=candidates;emptyList() }
        subject.enrich(request(streams).copy(context=movie.copy(activeFileDuration=capture)),{it}) {true}
        assertEquals(12,inspected.size)
        assertEquals(playing.stableKey(0),inspected.first().stableKey(0))
        assertEquals(11,inspected.count { it.stableKey(0) != playing.stableKey(0) })
        assertEquals(21,streams.size)
    }

    @Test fun `playing identity requires a unique exact source and never guesses between pack entries`() {
        val first=source("a").copy(addonName="Same",fileIdx=1)
        val second=first.copy(fileIdx=2)
        val state=PlayerUiState(sourceAllStreams=listOf(first,second),currentStreamInfoHash=first.infoHash,
            currentStreamFileIdx=2,currentStreamAddonName="Same")
        assertEquals(second.stableKey(0),PlayerSourcePanelMetadata.currentSourceKey(state))
        assertNull(PlayerSourcePanelMetadata.currentSourceKey(state.copy(currentStreamFileIdx=null)))
        assertNull(PlayerSourcePanelMetadata.currentSourceKey(state.copy(currentStreamAddonName="Other")))
        val direct=first.copy(url="https://native.example/file",infoHash=null)
        assertEquals(direct.stableKey(0),PlayerSourcePanelMetadata.currentSourceKey(state.copy(
            sourceAllStreams=listOf(direct),currentStreamInfoHash=null,currentStreamUrl=direct.url)))
    }

    private fun request(streams: List<Stream>) = PlayerSourceMetadataRequest(movie, streams, 1, 0, 1,
        "movie|tt1234567|-1|-1", "opaque-current-media")
    private fun source(hash: String) = Stream(null, "Movie", null, null, null, hash.repeat(40), 0, null, null, "Source", null)
    private fun meta(id: String, runtime: String?) = Meta(id, ContentType.MOVIE, name = "Movie", poster = null,
        posterShape = PosterShape.POSTER, background = null, logo = null, description = null, releaseInfo = null,
        imdbRating = null, genres = emptyList(), runtime = runtime, director = emptyList(), cast = emptyList(),
        videos = emptyList(), country = null, awards = null, language = null, links = emptyList())
}
