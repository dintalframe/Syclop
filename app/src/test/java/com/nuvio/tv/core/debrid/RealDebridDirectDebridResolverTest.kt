package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.RealDebridAddTorrentDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.data.remote.dto.RealDebridUnrestrictLinkDto
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class RealDebridDirectDebridResolverTest {
    @Test
    fun `resolve adds magnet selects file and unrestricts generated link`() = runTest {
        val api = FakeRealDebridApi(
            addResponse = Response.success(201, RealDebridAddTorrentDto(id = "rd_torrent")),
            infoResponses = mutableListOf(
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_torrent",
                        status = "waiting_files_selection",
                        files = listOf(
                            RealDebridTorrentFileDto(id = 1, path = "/wrong.mkv", bytes = 10),
                            RealDebridTorrentFileDto(id = 7, path = "/right.mkv", bytes = 20)
                        )
                    )
                ),
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_torrent",
                        status = "downloaded",
                        files = listOf(RealDebridTorrentFileDto(id = 7, path = "/right.mkv", bytes = 20, selected = 1)),
                        links = listOf("https://real-debrid.example/link")
                    )
                )
            ),
            unrestrictResponse = Response.success(
                RealDebridUnrestrictLinkDto(
                    filename = "right.mkv",
                    filesize = 20,
                    download = "https://cdn.example/right.mkv"
                )
            )
        )
        val resolver = resolver(api)

        val result = resolver.resolve(stream(fileIdx = 7), season = null, episode = null)

        assertTrue(result is DirectDebridResolveResult.Success)
        result as DirectDebridResolveResult.Success
        assertEquals("https://cdn.example/right.mkv", result.url)
        assertEquals("right.mkv", result.filename)
        assertEquals(20L, result.videoSize)
        assertEquals(1, api.addCalls)
        assertEquals(2, api.infoCalls)
        assertEquals(1, api.selectCalls)
        assertEquals("7", api.lastSelectedFiles)
        assertEquals(1, api.unrestrictCalls)
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun `resolve reports uncached torrent and preserves cloud job`() = runTest {
        val api = FakeRealDebridApi(
            addResponse = Response.success(201, RealDebridAddTorrentDto(id = "rd_torrent")),
            infoResponses = mutableListOf(
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_torrent",
                        status = "waiting_files_selection",
                        files = listOf(RealDebridTorrentFileDto(id = 7, path = "/right.mkv", bytes = 20))
                    )
                ),
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_torrent",
                        status = "downloading",
                        files = listOf(RealDebridTorrentFileDto(id = 7, path = "/right.mkv", bytes = 20, selected = 1)),
                        links = emptyList()
                    )
                )
            )
        )
        val resolver = resolver(api)

        val result = resolver.resolve(stream(fileIdx = 7), season = null, episode = null)

        assertTrue(result is DirectDebridResolveResult.NotCached)
        assertEquals(0, api.deleteCalls)
        assertEquals(0, api.unrestrictCalls)
    }

    @Test
    fun `downloaded torrent without a usable link is stale and preserves cloud job`() = runTest {
        val api = FakeRealDebridApi(
            addResponse = Response.success(201, RealDebridAddTorrentDto(id = "rd_torrent")),
            infoResponses = mutableListOf(
                Response.success(RealDebridTorrentInfoDto(status = "waiting_files_selection",
                    files = listOf(RealDebridTorrentFileDto(id = 7, path = "/right.mkv")))),
                Response.success(RealDebridTorrentInfoDto(status = "downloaded", links = emptyList()))
            )
        )
        assertEquals(DirectDebridResolveResult.Stale, resolver(api).resolve(stream(7), null, null))
        assertEquals(0, api.deleteCalls)
        assertEquals(0, api.unrestrictCalls)
    }

    @Test
    fun `initial processing without selectable files reports not cached without selecting polling or deleting`() = runTest {
        for (status in listOf("magnet_conversion", "queued", "downloading", "compressing", "uploading")) {
            val api = FakeRealDebridApi(
                addResponse = Response.success(201, RealDebridAddTorrentDto(id = "preserved_cloud_job")),
                infoResponses = mutableListOf(Response.success(RealDebridTorrentInfoDto(status = status, files = emptyList())))
            )
            assertEquals(DirectDebridResolveResult.NotCached, resolver(api).resolve(stream(7), null, null))
            assertEquals(1, api.addCalls)
            assertEquals(1, api.infoCalls)
            assertEquals(0, api.selectCalls)
            assertEquals(0, api.unrestrictCalls)
            assertEquals(0, api.deleteCalls)
        }
    }

    @Test
    fun `initial downloaded waiting selection unknown and error states do not hide missing files as processing`() = runTest {
        for (status in listOf("downloaded", "waiting_files_selection", "magnet_error", "error", "virus", "dead", "unexpected", null)) {
            val api = FakeRealDebridApi(
                addResponse = Response.success(201, RealDebridAddTorrentDto(id = "preserved_cloud_job")),
                infoResponses = mutableListOf(Response.success(RealDebridTorrentInfoDto(status = status)))
            )
            assertEquals(DirectDebridResolveResult.Stale, resolver(api).resolve(stream(7), null, null))
            assertEquals(1, api.infoCalls)
            assertEquals(0, api.selectCalls)
            assertEquals(0, api.unrestrictCalls)
            assertEquals(0, api.deleteCalls)
        }
    }

    @Test
    fun `present file mismatch or ambiguous names remain terminal even during processing`() = runTest {
        val candidates = listOf(
            listOf(RealDebridTorrentFileDto(id = 7, path = "/other.mkv")),
            listOf(RealDebridTorrentFileDto(id = 7, path = "/pack-a/right.mkv"),
                RealDebridTorrentFileDto(id = 8, path = "/pack-b/right.mkv"))
        )
        for (status in listOf("downloaded", "magnet_conversion")) for (files in candidates) {
            val api = FakeRealDebridApi(
                addResponse = Response.success(201, RealDebridAddTorrentDto(id = "preserved_cloud_job")),
                infoResponses = mutableListOf(Response.success(RealDebridTorrentInfoDto(status = status, files = files)))
            )
            assertEquals(DirectDebridResolveResult.Stale, resolver(api).resolve(stream(0), null, null))
            assertEquals(1, api.infoCalls)
            assertEquals(0, api.selectCalls)
            assertEquals(0, api.unrestrictCalls)
            assertEquals(0, api.deleteCalls)
        }
    }

    @Test
    fun `RD diagnostics retain only fixed labels counts and booleans`() {
        val privateValue = "private-token https://private.invalid/id secret-file.mkv"
        val metadata = stream(7).clientResolve!!.copy(filename = privateValue, magnetUri = privateValue)
        val info = RealDebridTorrentInfoDto(id = privateValue, filename = privateValue, status = privateValue,
            files = listOf(RealDebridTorrentFileDto(id = 1234567, path = "/private/secret-file.mkv", selected = 1)),
            links = listOf(privateValue))
        val fields = RealDebridResolveDiagnostics.fields(privateValue, privateValue, 9999, info, metadata, null, null)
        assertTrue(fields.contains("stage=unknown result=unknown httpStatus=none status=unknown"))
        assertTrue(fields.contains("fileCount=1 playableCount=1 selectedCount=1 linkCount=1"))
        assertTrue(fields.contains("filenameHint=true rawFilenameHint=false fileIndexHint=true episodeHint=false"))
        for (fragment in listOf(privateValue, "private-token", "private.invalid", "secret-file", "1234567"))
            assertFalse(fields.contains(fragment))
        val known = RealDebridResolveDiagnostics.fields("file_select", "not_cached", 200,
            info.copy(status = "magnet_conversion"), metadata, null, null)
        assertTrue(known.contains("stage=file_select result=not_cached httpStatus=200 status=magnet_conversion"))
    }

    @Test
    fun `HTTP 451 add refusal is a service error without metadata requests or cloud deletion`() = runTest {
        val api = FakeRealDebridApi(
            addResponse = Response.error(451, "restricted".toResponseBody()),
            infoResponses = mutableListOf()
        )
        assertEquals(DirectDebridResolveResult.Error, resolver(api).resolve(stream(7), null, null))
        assertEquals(1, api.addCalls)
        assertEquals(0, api.infoCalls)
        assertEquals(0, api.selectCalls)
        assertEquals(0, api.unrestrictCalls)
        assertEquals(0, api.deleteCalls)
    }

    private fun resolver(api: RealDebridApi): RealDebridDirectDebridResolver {
        val dataStore = mockk<DebridSettingsDataStore>()
        every { dataStore.settings } returns flowOf(
            DebridSettings(
                enabled = true,
                realDebridApiKey = "rd_token"
            )
        )
        return RealDebridDirectDebridResolver(
            dataStore = dataStore,
            api = api,
            fileSelector = RealDebridFileSelector()
        )
    }

    @Test
    fun `explicit HTTP hoster source is unrestricted without creating a torrent`() = runTest {
        val api = FakeRealDebridApi(
            addResponse = Response.success(201, RealDebridAddTorrentDto(id = "unused")),
            infoResponses = mutableListOf(),
            unrestrictResponse = Response.success(RealDebridUnrestrictLinkDto(download = "https://cdn.example/file.mkv", filename = "file.mkv"))
        )
        val original = stream(null)
        val hoster = original.copy(url = "https://host.example/file", clientResolve = original.clientResolve!!.copy(
            type = "hoster", infoHash = null, magnetUri = null))
        val result = resolver(api).resolve(hoster, null, null)
        assertTrue(result is DirectDebridResolveResult.Success)
        assertEquals("https://cdn.example/file.mkv", (result as DirectDebridResolveResult.Success).url)
        assertEquals(0, api.addCalls)
        assertEquals(1, api.unrestrictCalls)
    }

    @Test
    fun `reused cloud job never resolves first link when selection is ambiguous or wrong`() = runTest {
        val requested = RealDebridTorrentFileDto(id = 7, path = "/right.mkv", selected = 1)
        val other = RealDebridTorrentFileDto(id = 1, path = "/wrong.mkv", selected = 1)
        for (selected in listOf(listOf(other, requested), listOf(other))) {
            val api = FakeRealDebridApi(
                addResponse = Response.success(201, RealDebridAddTorrentDto(id = "existing_cloud_job")),
                infoResponses = mutableListOf(
                    Response.success(RealDebridTorrentInfoDto(status = "downloaded", files = listOf(other, requested))),
                    Response.success(RealDebridTorrentInfoDto(status = "downloaded", files = selected,
                        links = selected.map { "https://real-debrid.example/${it.id}" }))
                ),
                selectResponse = Response.success(202, "".toResponseBody())
            )
            assertEquals(DirectDebridResolveResult.Stale, resolver(api).resolve(stream(7), null, null))
            assertEquals("7", api.lastSelectedFiles)
            assertEquals(0, api.unrestrictCalls)
            assertEquals(0, api.deleteCalls)
        }
    }

    private fun stream(fileIdx: Int?): Stream = Stream(
        name = "Direct Debrid",
        title = "Title",
        description = "Description",
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = DebridProviders.instantName(DebridProviders.REAL_DEBRID_ID),
        addonLogo = null,
        clientResolve = StreamClientResolve(
            type = "debrid",
            infoHash = "abcdef",
            fileIdx = fileIdx,
            magnetUri = "magnet:?xt=urn:btih:abcdef",
            sources = null,
            torrentName = "Torrent",
            filename = "right.mkv",
            mediaType = "movie",
            mediaId = "tt1",
            mediaOnlyId = "tt1",
            title = "Title",
            season = null,
            episode = null,
            service = "realdebrid",
            serviceIndex = 0,
            serviceExtension = null,
            isCached = true
        )
    )

    private class FakeRealDebridApi(
        private val addResponse: Response<RealDebridAddTorrentDto>,
        private val infoResponses: MutableList<Response<RealDebridTorrentInfoDto>>,
        private val unrestrictResponse: Response<RealDebridUnrestrictLinkDto> = Response.success(
            RealDebridUnrestrictLinkDto(download = "")
        ),
        private val selectResponse: Response<ResponseBody> = Response.success("".toResponseBody())
    ) : RealDebridApi {
        var addCalls = 0
        var infoCalls = 0
        var selectCalls = 0
        var unrestrictCalls = 0
        var deleteCalls = 0
        var lastSelectedFiles: String? = null

        override suspend fun getUser(authorization: String): Response<ResponseBody> {
            return Response.success("{}".toResponseBody())
        }

        override suspend fun listTorrents(authorization: String, page: Int, limit: Int): Response<List<RealDebridTorrentInfoDto>> =
            Response.success(emptyList())

        override suspend fun addMagnet(
            authorization: String,
            magnet: String
        ): Response<RealDebridAddTorrentDto> {
            addCalls++
            return addResponse
        }

        override suspend fun getTorrentInfo(
            authorization: String,
            id: String
        ): Response<RealDebridTorrentInfoDto> {
            infoCalls++
            return infoResponses.removeAt(0)
        }

        override suspend fun selectFiles(
            authorization: String,
            id: String,
            files: String
        ): Response<ResponseBody> {
            selectCalls++
            lastSelectedFiles = files
            return selectResponse
        }

        override suspend fun unrestrictLink(
            authorization: String,
            link: String
        ): Response<RealDebridUnrestrictLinkDto> {
            unrestrictCalls++
            return unrestrictResponse
        }

        override suspend fun deleteTorrent(
            authorization: String,
            id: String
        ): Response<ResponseBody> {
            deleteCalls++
            return Response.success("".toResponseBody())
        }
    }
}
