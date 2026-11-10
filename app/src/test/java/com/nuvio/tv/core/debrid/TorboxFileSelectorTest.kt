package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.TorboxTorrentFileDto
import com.nuvio.tv.domain.model.StreamClientResolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorboxFileSelectorTest {
    private val selector = TorboxFileSelector()

    @Test
    fun `does not treat torrent index as torbox file id`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 1, name = "wrong.mkv", size = 20),
                file(id = 9, name = "right.mkv", size = 10)
            ),
            resolve = resolve(fileIdx = 9),
            season = null,
            episode = null
        )

        assertNull(selected)
    }

    @Test
    fun `falls back to filename match`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 1, name = "sample.mkv", size = 300),
                file(id = 2, name = "show.s01e02.1080p.mkv", size = 200)
            ),
            resolve = resolve(fileIdx = 88, filename = "show.s01e02.1080p.mkv"),
            season = null,
            episode = null
        )

        assertEquals(2, selected?.id)
    }

    @Test
    fun `falls back to episode pattern before largest video`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 1, name = "show.s01e01.mkv", size = 800),
                file(id = 2, name = "show.s01e02.mkv", size = 300)
            ),
            resolve = resolve(fileIdx = null),
            season = 1,
            episode = 2
        )

        assertEquals(2, selected?.id)
    }

    @Test
    fun `falls back to largest playable video`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 1, name = "small.txt", size = 900),
                file(id = 2, name = "small.mkv", size = 200),
                file(id = 3, name = "large.mp4", size = 500)
            ),
            resolve = resolve(fileIdx = null),
            season = null,
            episode = null
        )

        assertEquals(3, selected?.id)
    }

    @Test
    fun `matches absolute path when full name is missing`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, absolutePath = "/Show/Season 1/Episode.mkv", shortName = "Episode.mkv"),
            TorboxTorrentFileDto(id = 2, absolutePath = "/Show/Season 2/Episode.mkv", shortName = "Episode.mkv"),
        )

        assertEquals(
            2,
            selector.selectFile(files, resolve(null, "Season 2/Episode.mkv"), null, null)?.id,
        )
    }

    @Test
    fun `absolute path disambiguates basename names without selecting larger extras`() {
        val files = listOf(
            file(1, "film.mkv", 200).copy(absolutePath = "/Movie/Full/film.mkv"),
            file(2, "film.mkv", 900).copy(absolutePath = "/Movie/Extras/film.mkv"),
            file(3, "sample.mkv", 1000).copy(absolutePath = "/Movie/Samples/sample.mkv"),
        )

        for (ordered in listOf(files, files.reversed())) {
            for (filename in listOf("Full/film.mkv", "Full\\film.mkv")) {
                assertEquals(1, selector.selectFile(
                    ordered, resolve(1, filename).copy(mediaType = "movie"), null, null,
                )?.id)
            }
        }
    }

    @Test
    fun `full name paths keep priority over different provider storage directories`() {
        val files = listOf(
            file(1, "Movie/Full/film.mkv", 200).copy(absolutePath = "/storage/1/film.mkv"),
            file(2, "Movie/Extras/film.mkv", 900).copy(absolutePath = "/storage/2/film.mkv"),
        )

        for (ordered in listOf(files, files.reversed())) {
            for (names in listOf(ordered, ordered.map { it.copy(name = it.name?.replace('/', '\\')) })) {
                assertEquals(1, selector.selectFile(names, resolve(1, "Full/film.mkv"), null, null)?.id)
            }
        }
    }

    @Test
    fun `absolute path casing cannot change the exact filename winner`() {
        val files = listOf(
            file(1, "Film.mkv", 200).copy(absolutePath = "/Movie/Full/film.mkv"),
            file(2, "film.mkv", 900).copy(absolutePath = "/Movie/Extras/Film.mkv"),
        )

        for (ordered in listOf(files, files.reversed())) {
            assertEquals(1, selector.selectFile(ordered, resolve(1, "Film.mkv"), null, null)?.id)
        }
    }

    @Test
    fun `basename and duplicate exact path ambiguity cannot be resolved by an index`() {
        val files = listOf(
            file(1, "film.mkv", 200).copy(absolutePath = "/Movie/Full/film.mkv"),
            file(2, "film.mkv", 900).copy(absolutePath = "/Movie/Extras/film.mkv"),
        )

        for (ordered in listOf(files, files.reversed())) {
            for (filename in listOf("film.mkv", "Missing/film.mkv")) {
                assertNull(selector.selectFile(ordered, resolve(0, filename), null, null))
            }
            val duplicatePaths = ordered.map { it.copy(absolutePath = "/Movie/Full/film.mkv") }
            assertNull(selector.selectFile(duplicatePaths, resolve(0, "Full/film.mkv"), null, null))
        }
    }

    @Test
    fun `absolute paths cannot replace conflicting episode leaf names or prove a parent folder episode`() {
        val cases = listOf(
            Triple("Show.S01E03.mkv", "/Show/Show.S01E02.mkv", 2),
            Triple("Show.S01E02.mkv", "/Show/Show.S01E03.mkv", 3),
            Triple("video.mkv", "/Show.S01E02/video.mkv", 2),
        )

        for ((name, path, episode) in cases) {
            val files = listOf(file(1, name, 200).copy(absolutePath = path))
            assertNull(selector.selectFile(files, resolve(0, path), 1, episode))
        }
    }

    @Test
    fun `supports short name only and video mime without extension`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, shortName = "Show.S01E01.mkv"),
            TorboxTorrentFileDto(id = 2, name = "Show.S01E02", mimeType = "VIDEO/MATROSKA"),
        )

        assertEquals(1, selector.selectFile(files, resolve(null, "Show.S01E01.mkv"), null, null)?.id)
        assertEquals(2, selector.selectFile(files, resolve(null), 1, 2)?.id)
    }

    private fun file(id: Int, name: String, size: Long): TorboxTorrentFileDto = TorboxTorrentFileDto(
        id = id,
        name = name,
        shortName = null,
        absolutePath = null,
        mimeType = null,
        size = size
    )

    private fun resolve(
        fileIdx: Int?,
        filename: String? = null
    ): StreamClientResolve = StreamClientResolve(
        type = "debrid",
        infoHash = "hash",
        fileIdx = fileIdx,
        magnetUri = "magnet:?xt=urn:btih:hash",
        sources = null,
        torrentName = "show",
        filename = filename,
        mediaType = "series",
        mediaId = "tt1:1:2",
        mediaOnlyId = "tt1",
        title = "show",
        season = null,
        episode = null,
        service = "torbox",
        serviceIndex = 0,
        serviceExtension = null,
        isCached = true
    )
}
