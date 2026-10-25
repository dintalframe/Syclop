package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.anime.NativeAnimeEpisodeProof
import com.nuvio.tv.core.anime.NativeAnimeMappingCodec
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import org.junit.Assert.*
import org.junit.Test

class NativeAnimeDebridFileSelectionTest {
    private val proof = requireNotNull(NativeAnimeMappingCodec.parse(
        """{"titles":{"en":"Frieren 2nd Season"},"mappings":{"mal_id":59978,"thetvdb_id":424536},"episodes":{"1":{"tvdbShowId":424536,"tvdbId":10719109,"seasonNumber":2,"episodeNumber":1,"absoluteEpisodeNumber":29,"episode":"1"}}}""", 59978
    )?.episodes?.get(1))

    @Test fun `MAL relative one selects mapped season two actual file rather than season one`() {
        val wrong = File("Frieren.S01E01.mkv", 9000)
        val correct = File("Frieren.S02E01.mkv", 100)
        assertEquals(correct, select(listOf(wrong, correct), resolve(season = 1, episode = 1)))
        assertEquals(correct, select(listOf(correct, wrong), resolve(season = 1, episode = 1)))
    }

    @Test fun `absolute mapping selects actual twenty nine without largest fallback`() {
        val correct = File("Frieren - 29 [1080p].mkv", 100)
        assertEquals(correct, select(listOf(File("Frieren - 28.mkv", 9000), correct), resolve()))
        assertNull(select(listOf(File("Frieren - 28.mkv", 9000)), resolve()))
    }

    @Test fun `one unknown file never substitutes for missing verified episode`() {
        assertNull(select(listOf(File("movie.mkv", 9000)), resolve()))
        assertNull(select(listOf(File("Frieren.S02E01/video.mkv", 9000)), resolve()))
        assertNull(select(emptyList(), resolve()))
    }

    @Test fun `multiple proof matching encodes are ambiguous unless all explicit hints identify one`() {
        val first = File("Frieren.S02E01.720p.mkv", 100)
        val second = File("Frieren.S02E01.1080p.mkv", 200)
        assertNull(select(listOf(first, second), resolve()))
        assertEquals(second, select(listOf(first, second), resolve(filename = second.path, index = 1)))
    }

    @Test fun `filename for wrong actual episode cannot bypass proof even with matching relative UI`() {
        val wrong = File("Frieren.S01E01.mkv", 100)
        assertNull(select(listOf(wrong, File("Frieren.S02E01.mkv", 200)), resolve(filename = wrong.path)))
        assertNull(select(listOf(wrong), resolve(index = 0)))
    }

    @Test fun `conflicting preferred and raw filename hints are rejected rather than prioritized`() {
        val first = File("Frieren.S02E01.720p.mkv", 100)
        val second = File("Frieren.S02E01.1080p.mkv", 200)
        val hints = resolve(filename = first.path).copy(stream = StreamClientResolveStream(raw(second.path)))
        assertNull(select(listOf(first, second), hints))
    }

    @Test fun `matching filename and raw filename hints identify same actual file`() {
        val correct = File("Pack/Season 2/Frieren.S02E01.mkv", 100)
        val hints = resolve(filename = "Season 2\\Frieren.S02E01.mkv", index = 1)
            .copy(stream = StreamClientResolveStream(raw("Frieren.S02E01.mkv")))
        assertEquals(correct, select(listOf(File("readme.txt", 20), correct), hints))
    }

    @Test fun `filename and index conflicts never select either accepted encode`() {
        val first = File("Frieren.S02E01.720p.mkv", 100)
        val second = File("Frieren.S02E01.1080p.mkv", 200)
        assertNull(select(listOf(first, second), resolve(filename = first.path, index = 1)))
    }

    @Test fun `missing blank and invalid index hints never fall through to a matching file`() {
        val files = listOf(File("Frieren.S02E01.mkv", 100))
        assertNull(select(files, resolve(filename = "missing.mkv")))
        assertNull(select(files, resolve(filename = "  ")))
        for (index in listOf(-1, 1, 100)) assertNull(select(files, resolve(index = index)))
    }

    @Test fun `index includes nonplayable entries and must identify a playable proof matching file`() {
        val correct = File("Frieren.S02E01.mkv", 100)
        val files = listOf(File("readme.txt", 9000), correct)
        assertEquals(correct, select(files, resolve(index = 1)))
        assertNull(select(files, resolve(index = 0)))
        assertNull(select(listOf(File("Frieren.S02E01.zip", 100)), resolve(index = 0)))
    }

    @Test fun `same basename in two directories remains ambiguous even with an index`() {
        val files = listOf(File("A/Frieren.S02E01.mkv", 100), File("B/Frieren.S02E01.mkv", 200))
        assertNull(select(files, resolve(filename = "Frieren.S02E01.mkv", index = 1)))
        assertEquals(files[1], select(files, resolve(filename = "B/Frieren.S02E01.mkv", index = 1)))
    }

    @Test fun `fractional x notation and underscore compound hints cannot select adjacent episodes`() {
        for (name in listOf("Frieren.2x01.5.mkv", "Frieren.E29_30.mkv", "Frieren.S02E01_02.mkv", "Frieren.E29-30v2.mkv", "Frieren.S02E01_02v2.mkv", "Frieren.2x01.5v2.mkv", "Frieren.E29-30dub.mkv")) {
            val file = File(name, 100)
            assertNull(name, select(listOf(file), resolve()))
            assertNull(name, select(listOf(file), resolve(filename = name, index = 0)))
        }
    }

    @Test fun `non MAL behavior keeps its existing filename priority episode and largest rules`() {
        val first = File("Frieren.S01E01.mkv", 100)
        val second = File("Frieren.S01E02.mkv", 9000)
        val hints = resolve(filename = second.path).copy(stream = StreamClientResolveStream(raw(first.path)))
        assertEquals(second, select(listOf(first, second), hints, nativeProof = null))
        assertEquals(first, select(listOf(first, second), resolve(season = 1, episode = 1), nativeProof = null))
        assertEquals(second, select(listOf(first, second), resolve(), nativeProof = null))
    }

    @Test fun `exact wrong show episode hints and index cannot override franchise proof`() {
        for (wrong in listOf("Night.On.Earth.S02E01.mkv", "Welcome.to.Derry.E29.mkv", "[Frieren] Other.S02E01.mkv")) {
            val file = File(wrong, 29_700_000_000)
            assertNull(wrong, select(listOf(file), resolve()))
            assertNull(wrong, select(listOf(file), resolve(filename = wrong, index = 0)))
        }
        val correct = File("Frieren.S02E01.mkv", 100)
        val wrong = File("Other.S02E01.mkv", 9000)
        assertEquals(correct, select(listOf(wrong, correct), resolve()))
        assertNull(select(listOf(wrong, correct), resolve(index = 0)))
    }

    private data class File(val path: String, val bytes: Long)
    private fun select(files: List<File>, metadata: StreamClientResolve, nativeProof: NativeAnimeEpisodeProof? = proof) =
        selectDebridFile(files, metadata, 1.takeIf { metadata.episode != null }, metadata.episode,
            path = { it.path }, isPlayable = { it.path.hasDebridVideoExtension() }, size = { it.bytes }, animeProof = nativeProof)

    private fun resolve(filename: String? = null, index: Int? = null, season: Int? = null, episode: Int? = null) = StreamClientResolve(
        type = "torrent", infoHash = null, fileIdx = index, magnetUri = null, sources = null,
        torrentName = null, filename = filename, mediaType = "series", mediaId = null, mediaOnlyId = null,
        title = null, season = season, episode = episode, service = null, serviceIndex = null,
        serviceExtension = null, isCached = null,
    )
    private fun raw(filename: String) = StreamClientResolveRaw(
        torrentName = null, filename = filename, size = null, folderSize = null, tracker = null,
        indexer = null, network = null, parsed = null,
    )
}
