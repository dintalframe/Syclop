package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.AllDebridEnvelope
import com.nuvio.tv.data.remote.dto.AllDebridLinkDto
import com.nuvio.tv.data.remote.dto.AllDebridStreamDto
import com.nuvio.tv.data.remote.dto.AllDebridFileNodeDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetsDataDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.data.remote.dto.RealDebridUnrestrictLinkDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamAvailabilityScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class NativeDebridAccountLibraryTest {
    private val rd = mockk<RealDebridApi>()
    private val ad = mockk<AllDebridApi>()
    private val allDebrid = AllDebridService(ad)
    private val library = NativeDebridAccountLibrary(rd, ad, allDebrid)
    private val scope = StreamAvailabilityScope(1, 2, 3)
    private val rdAccount = DebridServiceCredential(DebridProviders.RealDebrid, "synthetic-rd")
    private val adAccount = DebridServiceCredential(DebridProviders.AllDebrid, "synthetic-ad")
    private val stream = Stream("Show", null, null, null, null, HASH, null, null, null, "Synthetic", null)
    private val file = RealDebridTorrentFileDto(9, "/Show.S02E03.mkv", 100L, 1)
    private val torrent = RealDebridTorrentInfoDto(id = "existing", hash = HASH, status = "downloaded",
        files = listOf(file), links = listOf("https://real-debrid.example/episode"))

    @Test fun `Real Debrid only reads exact downloaded hashes and unlocks just the revalidated episode on selection`() = runTest {
        coEvery { rd.listTorrents("Bearer synthetic-rd", 1, any()) } returns Response.success(listOf(
            torrent.copy(id = "other", hash = OTHER), torrent.copy(id = "queued", status = "queued"),
            torrent.copy(hash = HASH.uppercase())
        ))
        coEvery { rd.getTorrentInfo("Bearer synthetic-rd", "existing") } returns Response.success(torrent)
        val operation = operation(rdAccount)
        val results = library.find(rdAccount, scope, setOf(HASH), operation)
        val evidence = results[HASH]?.exactFile(stream, 2, 3, null)
        assertNotNull(evidence)
        assertNull(results[HASH]?.exactFile(stream, 2, 4, null))
        coVerify(exactly = 1) { rd.getTorrentInfo(any(), "existing") }
        coVerify(exactly = 0) { rd.getTorrentInfo(any(), "other") }
        coVerify(exactly = 0) { rd.unrestrictLink(any(), any()) }

        coEvery { rd.unrestrictLink("Bearer synthetic-rd", "https://real-debrid.example/episode") } returns
            Response.success(RealDebridUnrestrictLinkDto(filename = "Show.S02E03.mkv", filesize = 100L,
                download = "https://video.example/episode.mkv"))
        val result = library.resolve(rdAccount, scope, HASH, evidence!!, stream, 2, 3, operation)
        assertEquals("https://video.example/episode.mkv", (result as DirectDebridResolveResult.Success).url)
        assertNull(result.createdCloudItem)
        coVerify(exactly = 2) { rd.getTorrentInfo(any(), "existing") }
        coVerify(exactly = 1) { rd.unrestrictLink(any(), any()) }
        coVerify(exactly = 0) { rd.addMagnet(any(), any()) }
        coVerify(exactly = 0) { rd.selectFiles(any(), any(), any()) }
        coVerify(exactly = 0) { rd.deleteTorrent(any(), any()) }
    }

    @Test fun `a short account inventory snapshot serves progressive hashes but never another revision`() = runTest {
        coEvery { rd.listTorrents(any(), 1, any()) } returns Response.success(listOf(torrent))
        coEvery { rd.getTorrentInfo(any(), "existing") } returns Response.success(torrent)
        val operation = operation(rdAccount)
        assertTrue(library.find(rdAccount, scope, setOf(OTHER), operation).isEmpty())
        assertTrue(HASH in library.find(rdAccount, scope, setOf(HASH), operation))
        coVerify(exactly = 1) { rd.listTorrents(any(), 1, any()) }

        library.find(rdAccount, scope.copy(credentialRevision = 4), setOf(HASH), operation)
        coVerify(exactly = 2) { rd.listTorrents(any(), 1, any()) }
        coVerify(exactly = 0) { rd.unrestrictLink(any(), any()) }
    }

    @Test fun `Real Debrid changed hash or multiple links fail closed before unlock`() = runTest {
        coEvery { rd.listTorrents(any(), 1, any()) } returns Response.success(listOf(torrent))
        coEvery { rd.getTorrentInfo(any(), "existing") } returnsMany listOf(Response.success(torrent),
            Response.success(torrent.copy(hash = OTHER)),
            Response.success(torrent.copy(links = listOf("https://real-debrid.example/episode", "https://real-debrid.example/other"))))
        val operation = operation(rdAccount)
        val evidence = library.find(rdAccount, scope, setOf(HASH), operation)[HASH]?.exactFile(stream, 2, 3, null)!!
        repeat(2) {
            assertFalse(library.resolve(rdAccount, scope, HASH, evidence, stream, 2, 3, operation)
                is DirectDebridResolveResult.Success)
        }
        coVerify(exactly = 0) { rd.unrestrictLink(any(), any()) }
        coVerify(exactly = 0) { rd.addMagnet(any(), any()) }
    }

    @Test fun `generic episode index cannot disambiguate RD encodes and selected HTTP rejection stays typed`() = runTest {
        val alternate = file.copy(id = 10, path = "/Alt.Show.S02E03.mkv", selected = 0)
        val multiEncode = AccountLibraryItem("existing", listOf(
            AccountLibraryFile("Show.S02E03.mkv", 100, "9", available = true),
            AccountLibraryFile("Alt.Show.S02E03.mkv", 100, "10", available = false)
        ))
        val indexed = stream.copy(fileIdx = 0)
        assertNull(multiEncode.exactFile(indexed, 2, 3, null))
        val named = indexed.copy(behaviorHints = com.nuvio.tv.domain.model.StreamBehaviorHints(
            null, null, null, null, filename = "Show.S02E03.mkv"))
        coEvery { rd.getTorrentInfo(any(), "existing") } returns Response.success(torrent.copy(files = listOf(file, alternate)))
        coEvery { rd.unrestrictLink(any(), any()) } returns
            Response.error(451, "".toResponseBody())
        val operation = operation(rdAccount)
        val evidence = multiEncode.exactFile(named, 2, 3, null)!!
        val result = library.resolve(rdAccount, scope, HASH, evidence, named, 2, 3, operation)
        assertEquals(DirectDebridFailureReason.SOURCE_REJECTED, (result as DirectDebridResolveResult.Failure).detail.reason)
        coVerify(exactly = 1) { rd.unrestrictLink(any(), any()) }
        coVerify(exactly = 0) { rd.addMagnet(any(), any()) }
    }

    @Test fun `AllDebrid uses exact hash provenance and refuses ambiguous episode leaves`() = runTest {
        val operation = operation(adAccount)
        val missingHash = AllDebridMagnetDto(id = 42, statusCode = 4)
        val selected = AllDebridFileNodeDto("Show.S02E03.mkv", 100L, "https://alldebrid.example/episode")
        coEvery { ad.magnetStatus(any(), null) } returns magnets(missingHash)
        assertTrue(library.find(adAccount, scope, setOf(HASH), operation).isEmpty())
        coVerify(exactly = 0) { ad.magnetFiles(any(), any()) }

        allDebrid.rememberSelectedMagnet(scope, HASH, missingHash.copy(hash = HASH))
        assertTrue(library.find(adAccount, scope.copy(credentialRevision = 4), setOf(HASH), operation).isEmpty())
        coEvery { ad.magnetFiles(any(), listOf(42L)) } returns magnets(missingHash.copy(files = listOf(
            selected, AllDebridFileNodeDto("Alt.Show.S02E03.mkv", 200L, "https://alldebrid.example/alternate"))))
        assertNull(library.find(adAccount, scope, setOf(HASH), operation)[HASH]?.exactFile(stream, 2, 3, null))
        coEvery { ad.magnetStatus(any(), 42) } returns magnets(missingHash.copy(files = listOf(selected)))
        coEvery { ad.magnetFiles(any(), listOf(42L)) } returns magnets(missingHash.copy(files = listOf(selected,
            AllDebridFileNodeDto("Show.S02E04.mkv", 200L, "https://alldebrid.example/wrong"))))
        val evidence = library.find(adAccount, scope, setOf(HASH), operation)[HASH]?.exactFile(stream, 2, 3, null)
        assertNotNull(evidence)
        coVerify(exactly = 0) { ad.unlockLink(any(), any()) }

        coEvery { ad.unlockLink("Bearer synthetic-ad", "https://alldebrid.example/episode") } returns
            Response.success(AllDebridEnvelope("success", com.nuvio.tv.data.remote.dto.AllDebridLinkDto(
                link = "https://video.example/episode.mkv", filename = "Show.S02E03.mkv", filesize = 100L)))
        assertTrue(library.resolve(adAccount, scope, HASH, evidence!!, stream, 2, 3, operation)
            is DirectDebridResolveResult.Success)
        coVerify(exactly = 1) { ad.unlockLink(any(), any()) }
        coVerify(exactly = 0) { ad.uploadMagnet(any(), any()) }
        coVerify(exactly = 0) { ad.deleteMagnet(any(), any()) }
    }

    @Test fun `AllDebrid account link follows streaming and delayed responses and checks final metadata`() = runTest {
        val evidence = allDebridEpisode()
        coEvery { ad.unlockLink(any(), "https://alldebrid.example/episode") } returns link(AllDebridLinkDto(
            id = "selected", filename = "Show.S02E03.mkv", filesize = 100L,
            streams = listOf(AllDebridStreamDto("hd", "1080"), AllDebridStreamDto("sd", "480"))))
        coEvery { ad.streamingLink(any(), "selected", "hd") } returns link(AllDebridLinkDto(delayed = 7L))
        val ready = AllDebridLinkDto(link = "https://video.example/episode.mkv")
        coEvery { ad.delayedLink(any(), 7L) } returnsMany listOf(
            link(ready), link(ready.copy(filename = "Show.S02E04.mkv")), link(ready.copy(filesize = 101L)),
            link(ready.copy(link = "http://video.example/episode.mkv")))
        val operation = operation(adAccount)

        val result = library.resolve(adAccount, scope, HASH, evidence, stream, 2, 3, operation)
        assertEquals(DirectDebridResolveResult.Success("https://video.example/episode.mkv", "Show.S02E03.mkv", 100L), result)
        repeat(3) {
            assertEquals(DirectDebridFailureReason.UNAVAILABLE,
                (library.resolve(adAccount, scope, HASH, evidence, stream, 2, 3, operation)
                    as DirectDebridResolveResult.Failure).detail.reason)
        }
        coVerify(exactly = 4) { ad.unlockLink(any(), "https://alldebrid.example/episode") }
        coVerify(exactly = 4) { ad.streamingLink(any(), "selected", "hd") }
        coVerify(exactly = 4) { ad.delayedLink(any(), 7L) }
        coVerify(exactly = 0) { ad.uploadMagnet(any(), any()) }
    }

    @Test fun `AllDebrid account change during delayed wait cancels before the next request`() = runTest {
        val evidence = allDebridEpisode()
        var current = true
        val operation = CapturedDebridOperation(adAccount.provider.id, adAccount.apiKey) { current }
        coEvery { ad.unlockLink(any(), "https://alldebrid.example/episode") } returns link(AllDebridLinkDto(delayed = 7L))
        val pending = async { runCatching { library.resolve(adAccount, scope, HASH, evidence, stream, 2, 3, operation) } }
        yield()
        coVerify(exactly = 1) { ad.unlockLink(any(), any()) }
        current = false
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        coVerify(exactly = 0) { ad.delayedLink(any(), any()) }
        coVerify(exactly = 0) { ad.uploadMagnet(any(), any()) }
    }

    private fun allDebridEpisode(): com.nuvio.tv.domain.model.StreamDebridLibraryFile {
        val file = AllDebridFileNodeDto("Show.S02E03.mkv", 100L, "https://alldebrid.example/episode")
        coEvery { ad.magnetStatus(any(), 42L) } returns magnets(AllDebridMagnetDto(id = 42, hash = HASH,
            statusCode = 4, files = listOf(file)))
        return AccountLibraryItem("42", listOf(AccountLibraryFile(file.name, file.size, null, true)))
            .exactFile(stream, 2, 3, null)!!
    }

    private fun link(item: AllDebridLinkDto) = Response.success(AllDebridEnvelope("success", item))

    @Test fun `credential change between proof and unlock cancels the account operation`() = runTest {
        var current = true
        coEvery { rd.listTorrents(any(), 1, any()) } returns Response.success(listOf(torrent))
        coEvery { rd.getTorrentInfo(any(), "existing") } returns Response.success(torrent)
        val operation = CapturedDebridOperation(rdAccount.provider.id, rdAccount.apiKey) { current }
        val evidence = library.find(rdAccount, scope, setOf(HASH), operation)[HASH]?.exactFile(stream, 2, 3, null)!!
        coEvery { rd.getTorrentInfo(any(), "existing") } coAnswers { current = false; Response.success(torrent) }
        try {
            library.resolve(rdAccount, scope, HASH, evidence, stream, 2, 3, operation)
            fail("Expected stale account cancellation")
        } catch (_: CancellationException) { }
        coVerify(exactly = 0) { rd.unrestrictLink(any(), any()) }
    }

    private fun operation(account: DebridServiceCredential) = CapturedDebridOperation(account.provider.id, account.apiKey) { true }

    private fun magnets(vararg items: AllDebridMagnetDto) =
        Response.success(AllDebridEnvelope("success", AllDebridMagnetsDataDto(items.toList())))

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef01234567"
        const val OTHER = "fedcba9876543210fedcba9876543210fedcba98"
    }
}
