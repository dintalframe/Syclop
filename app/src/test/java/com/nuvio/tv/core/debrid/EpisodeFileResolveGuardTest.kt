package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.AllDebridLinkDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetDto
import com.nuvio.tv.data.remote.dto.RealDebridAddTorrentDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.data.remote.dto.RealDebridUnrestrictLinkDto
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** Service-path checks: provider filename/index hints cannot authorize a different episode. */
class EpisodeFileResolveGuardTest {
    @Test
    fun `AllDebrid rejects an actual wrong episode even when provider filename points to it`() = runTest {
        val service = allDebridService(listOf(
            AllDebridFile("Pack/Example.Show.S01E02.mkv", 9_000L, WRONG_FILE_LINK)
        ))

        val result = AllDebridDirectDebridResolver(settings(), service).resolve(
            stream(DebridProviders.ALL_DEBRID_ID, filename = "Example.Show.S01E02.mkv", fileIdx = 0),
            season = 1,
            episode = 4
        )

        assertEquals(wrongDebridFile("alldebrid"), result)
        coVerify(exactly = 1) { service.upload(TOKEN, any()) }
        coVerify(exactly = 1) { service.files(TOKEN, listOf(42L)) }
        coVerify(exactly = 0) { service.unlock(any(), any()) }
    }

    @Test
    fun `AllDebrid rejects compilation compound range and fractional leaves before unlocking`() = runTest {
        for (filename in listOf(
            "Example.Show.S01-S12.Complete.mkv",
            "Example.Show.S01E04E05.mkv",
            "Example.Show.S01E04-E08.mkv",
            "Example.Show.S01E04.5.mkv",
            "Example.Show.S01E04.1080p.E05E06.mkv",
            "Example.Show.S01E04.1080p.S02E05E06.mkv"
        )) {
            val service = allDebridService(listOf(AllDebridFile("Pack/$filename", 90_000L, WRONG_FILE_LINK)))

            val result = AllDebridDirectDebridResolver(settings(), service).resolve(
                stream(DebridProviders.ALL_DEBRID_ID, filename = filename, fileIdx = 0),
                season = 1,
                episode = 4
            )

            assertEquals(filename, wrongDebridFile("alldebrid"), result)
            coVerify(exactly = 1) { service.files(TOKEN, listOf(42L)) }
            coVerify(exactly = 0) { service.unlock(any(), any()) }
        }
    }

    @Test
    fun `AllDebrid resolves only the unique requested episode leaf inside a multi season pack`() = runTest {
        val files = listOf(
            AllDebridFile("Example.Show.S01-S12.Complete/Example.Show.S01E02.mkv", 90_000L, WRONG_FILE_LINK),
            AllDebridFile("Example.Show.S01-S12.Complete/Example.Show.S01E04.mkv", 2_000L, REQUESTED_FILE_LINK),
            AllDebridFile("Example.Show.S01-S12.Complete/Example.Show.S01E04.zip", 190_000L, ARCHIVE_LINK)
        )
        val service = allDebridService(files)
        coEvery { service.unlock(TOKEN, REQUESTED_FILE_LINK) } returns AllDebridLinkDto(
            link = PLAYBACK_URL, filename = "Example.Show.S01E04.mkv", filesize = 2_000L
        )

        val result = AllDebridDirectDebridResolver(settings(), service).resolve(
            stream(DebridProviders.ALL_DEBRID_ID, filename = "Example.Show.S01-S12.Complete", fileIdx = 0),
            season = 1,
            episode = 4
        )

        assertTrue(result is DirectDebridResolveResult.Success)
        result as DirectDebridResolveResult.Success
        assertEquals(PLAYBACK_URL, result.url)
        assertEquals("Example.Show.S01E04.mkv", result.filename)
        assertEquals(2_000L, result.videoSize)
        coVerify(exactly = 1) { service.unlock(TOKEN, REQUESTED_FILE_LINK) }
        coVerify(exactly = 1) { service.unlock(any(), any()) }
        coVerify(exactly = 0) { service.unlock(any(), WRONG_FILE_LINK) }
        coVerify(exactly = 0) { service.unlock(any(), ARCHIVE_LINK) }
    }

    @Test
    fun `RealDebrid rejects a matching wrong filename before selecting or unrestricting files`() = runTest {
        val api = mockk<RealDebridApi>()
        coEvery { api.addMagnet(any(), any()) } returns Response.success(201, RealDebridAddTorrentDto(id = "fixture-job"))
        coEvery { api.getTorrentInfo(any(), "fixture-job") } returns Response.success(
            RealDebridTorrentInfoDto(status = "downloaded", files = listOf(
                RealDebridTorrentFileDto(id = 2, path = "/Pack/Example.Show.S01E02.mkv", bytes = 90_000L, selected = 1)
            ), links = listOf(WRONG_FILE_LINK))
        )

        val result = RealDebridDirectDebridResolver(settings(), api, RealDebridFileSelector()).resolve(
            stream(DebridProviders.REAL_DEBRID_ID, filename = "Example.Show.S01E02.mkv", fileIdx = 0),
            season = 1,
            episode = 4
        )

        assertEquals(wrongDebridFile("realdebrid"), result)
        coVerify(exactly = 1) { api.getTorrentInfo(any(), "fixture-job") }
        coVerify(exactly = 0) { api.selectFiles(any(), any(), any()) }
        coVerify(exactly = 0) { api.unrestrictLink(any(), any()) }
        coVerify(exactly = 0) { api.deleteTorrent(any(), any()) }
    }

    @Test
    fun `RealDebrid selects only the requested episode id and its single returned link`() = runTest {
        val api = mockk<RealDebridApi>()
        val requested = RealDebridTorrentFileDto(id = 7,
            path = "/Example.Show.S01-S12.Complete/Example.Show.S01E04.mkv", bytes = 2_000L)
        coEvery { api.addMagnet(any(), any()) } returns Response.success(201, RealDebridAddTorrentDto(id = "fixture-job"))
        coEvery { api.getTorrentInfo(any(), "fixture-job") } returnsMany listOf(
            Response.success(RealDebridTorrentInfoDto(status = "waiting_files_selection", files = listOf(
                RealDebridTorrentFileDto(id = 2, path = "/Example.Show.S01E02.mkv", bytes = 90_000L),
                requested,
                RealDebridTorrentFileDto(id = 9, path = "/Example.Show.S01E04.zip", bytes = 190_000L)
            ))),
            Response.success(RealDebridTorrentInfoDto(status = "downloaded",
                files = listOf(requested.copy(selected = 1)), links = listOf(REQUESTED_FILE_LINK)))
        )
        coEvery { api.selectFiles(any(), "fixture-job", "7") } returns Response.success("".toResponseBody())
        coEvery { api.unrestrictLink(any(), REQUESTED_FILE_LINK) } returns Response.success(
            RealDebridUnrestrictLinkDto(download = PLAYBACK_URL, filename = "Example.Show.S01E04.mkv", filesize = 2_000L)
        )

        val result = RealDebridDirectDebridResolver(settings(), api, RealDebridFileSelector()).resolve(
            stream(DebridProviders.REAL_DEBRID_ID, filename = "Example.Show.S01-S12.Complete", fileIdx = 0),
            season = 1,
            episode = 4
        )

        assertTrue(result is DirectDebridResolveResult.Success)
        assertEquals(PLAYBACK_URL, (result as DirectDebridResolveResult.Success).url)
        assertEquals("Example.Show.S01E04.mkv", result.filename)
        coVerify(exactly = 1) { api.selectFiles(any(), "fixture-job", "7") }
        coVerify(exactly = 1) { api.selectFiles(any(), any(), any()) }
        coVerify(exactly = 1) { api.unrestrictLink(any(), REQUESTED_FILE_LINK) }
        coVerify(exactly = 1) { api.unrestrictLink(any(), any()) }
        coVerify(exactly = 0) { api.deleteTorrent(any(), any()) }
    }

    private fun settings(): DebridSettingsDataStore = mockk<DebridSettingsDataStore>().also { store ->
        every { store.settings } returns flowOf(DebridSettings(
            enabled = true, allDebridApiKey = TOKEN, realDebridApiKey = TOKEN
        ))
    }

    private fun allDebridService(files: List<AllDebridFile>): AllDebridService = mockk<AllDebridService>().also { service ->
        coEvery { service.upload(TOKEN, any()) } returns AllDebridMagnetDto(id = 42L, ready = true)
        coEvery { service.files(TOKEN, listOf(42L)) } returns mapOf(42L to files)
    }

    private fun stream(service: String, filename: String?, fileIdx: Int?): Stream = Stream(
        name = "Episode fixture", title = "Example Show", description = null, url = null,
        ytId = null, infoHash = HASH, fileIdx = fileIdx, externalUrl = null, behaviorHints = null,
        addonName = "Fixture", addonLogo = null,
        clientResolve = StreamClientResolve(type = "debrid", infoHash = HASH, fileIdx = fileIdx,
            magnetUri = null, sources = null, torrentName = "Example.Show.S01-S12.Complete",
            filename = filename, mediaType = "series", mediaId = "tt-fixture:1:4", mediaOnlyId = "tt-fixture",
            title = "Example Show", season = 1, episode = 4, service = service,
            serviceIndex = null, serviceExtension = null, isCached = true)
    )

    private companion object {
        const val TOKEN = "fixture-token-not-an-account"
        const val HASH = "0123456789012345678901234567890123456789"
        const val WRONG_FILE_LINK = "https://debrid.example.invalid/wrong-episode"
        const val REQUESTED_FILE_LINK = "https://debrid.example.invalid/requested-episode"
        const val ARCHIVE_LINK = "https://debrid.example.invalid/whole-pack.zip"
        const val PLAYBACK_URL = "https://cdn.example.invalid/Example.Show.S01E04.mkv"
    }
}
