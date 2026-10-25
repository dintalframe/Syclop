package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DebridBitrateMetadataServiceTest {
    @Test fun `authenticated native selected-file bytes override untrusted season-pack claim`()=runTest {
        val h=Fixture(); val native=h.stream().copy(nativeFileMetadata=StreamFileMetadata(1_000_000))
        val result=h.service.enrichKnownFiles(listOf(native),1000,StreamDurationOrigin.CATALOG_EPISODE,1,2,1).single().second
        assertEquals(8_000_000L,StreamBitrateFacts.estimatedBitsPerSecond(result))
        assertEquals(native.stableKey(),result.stableKey())
    }
    @Test fun `addon HTTPS size claim is ignored when no private native playback authorization exists`()=runTest {
        val h=Fixture();coEvery { h.resolver.isPlaybackAuthorized(any(),any()) } returns false
        assertTrue(h.service.enrichKnownFiles(listOf(h.stream()),1000,StreamDurationOrigin.CATALOG_MOVIE,null,null,1).isEmpty())
    }
    @Test fun `authorization lost during metadata operation rejects result`()=runTest {
        val h=Fixture();coEvery { h.resolver.isPlaybackAuthorized(any(),any()) } returnsMany listOf(true,false)
        val native=h.stream().copy(nativeFileMetadata=StreamFileMetadata(100))
        assertTrue(h.service.enrichKnownFiles(listOf(native),1000,StreamDurationOrigin.CATALOG_MOVIE,null,null,1).isEmpty())
    }
    @Test fun `profile switch cannot publish previous viewer file metadata`()=runTest {
        val h=Fixture();coEvery { h.resolver.isPlaybackAuthorized(any(),any()) } coAnswers { h.active.value=2;true }
        val native=h.stream().copy(nativeFileMetadata=StreamFileMetadata(100))
        assertTrue(h.service.enrichKnownFiles(listOf(native),1000,StreamDurationOrigin.CATALOG_MOVIE,null,null,1).isEmpty())
    }
    @Test fun `unresolved torrent cache miss performs no new native resolution`()=runTest {
        val h=Fixture();val torrent=h.stream().copy(url=null,infoHash="a".repeat(40))
        coEvery { h.resolver.cachedPlayableStream(any(),any(),any()) } returns null
        assertTrue(h.service.enrichKnownFiles(listOf(torrent),1000,StreamDurationOrigin.CATALOG_EPISODE,1,2,1).isEmpty())
        coVerify(exactly=1){h.resolver.cachedPlayableStream(torrent,1,2)}
        coVerify(exactly=0){h.resolver.isPlaybackAuthorized(any(),any())}
    }
    @Test fun `unavailable duration remains unknown even for native exact bytes`()=runTest {
        val h=Fixture();val native=h.stream().copy(nativeFileMetadata=StreamFileMetadata(100))
        val result=h.service.enrichKnownFiles(listOf(native),null,null,1,2,1).single().second
        assertNull(StreamBitrateFacts.estimatedBitsPerSecond(result))
    }
    @Test fun `cache-resolved metadata preserves raw source for fresh native playback resolve`()=runTest {
        val h=Fixture()
        val raw=h.stream().copy(url=null,infoHash="b".repeat(40),clientResolve=StreamClientResolve("torrent","b".repeat(40),3,null,null,null,null,null,null,null,null,1,2,"realdebrid",null,null,null))
        val cached=raw.copy(url="https://native.example/signed",clientResolve=null,nativeFileMetadata=StreamFileMetadata(200))
        coEvery { h.resolver.cachedPlayableStream(raw,1,2) } returns cached
        val result=h.service.enrichKnownFiles(listOf(raw),1000,StreamDurationOrigin.CATALOG_EPISODE,1,2,1).single().second
        assertNull(result.url)
        assertEquals(raw.infoHash,result.infoHash)
        assertEquals(raw.clientResolve,result.clientResolve)
        assertTrue(result.needsLocalDebridResolve())
        assertEquals(200L,result.nativeFileMetadata?.selectedFileBytes)
    }
    @Test fun `credential change after one child completes rejects whole late batch`()=runTest {
        val h=Fixture();val release=CompletableDeferred<Unit>()
        val first=h.stream().copy(url="https://native.example/first",nativeFileMetadata=StreamFileMetadata(100))
        val second=first.copy(url="https://native.example/second")
        coEvery { h.resolver.isPlaybackAuthorized(second.url!!,any()) } coAnswers { release.await();true }
        val pending=async { h.service.enrichKnownFiles(listOf(first,second),1000,StreamDurationOrigin.CATALOG_MOVIE,null,null,1) }
        runCurrent()
        coVerify(exactly=2){h.resolver.isPlaybackAuthorized(first.url!!,any())}
        h.credentialRevision++
        release.complete(Unit)
        assertTrue(pending.await().isEmpty())
    }
    @Test fun `away and back after child completion rejects whole late batch`()=runTest {
        val h=Fixture();val release=CompletableDeferred<Unit>()
        val first=h.stream().copy(url="https://native.example/first",nativeFileMetadata=StreamFileMetadata(100))
        val second=first.copy(url="https://native.example/second")
        coEvery { h.resolver.isPlaybackAuthorized(second.url!!,any()) } coAnswers { release.await();true }
        val pending=async { h.service.enrichKnownFiles(listOf(first,second),1000,StreamDurationOrigin.CATALOG_MOVIE,null,null,1) }
        runCurrent()
        h.selectionRevision+=2
        release.complete(Unit)
        assertTrue(pending.await().isEmpty())
    }
    @Test fun `credential mutation in progress skips all private metadata reads`()=runTest {
        val h=Fixture();h.credentialRevision=-1L
        assertTrue(h.service.enrichKnownFiles(listOf(h.stream()),1000,StreamDurationOrigin.CATALOG_MOVIE,null,null,1).isEmpty())
        coVerify(exactly=0){h.resolver.isPlaybackAuthorized(any(),any())}
    }
    @Test fun `cache-only enrichment preserves selected container facts and measured runtime`()=runTest {
        val h=Fixture()
        val facts=VerifiedSourceMediaFacts(video=VerifiedSourceVideo(3840,2160,VerifiedSourceVideoCodec.HEVC,VerifiedSourceHdr.PQ),durationMillis=2000)
        val native=h.stream().copy(nativeFileMetadata=StreamFileMetadata(100,2000,StreamDurationOrigin.NATIVE_CONTAINER,verifiedTracks=facts))
        val result=h.service.enrichKnownFiles(listOf(native),1000,StreamDurationOrigin.CATALOG_MOVIE,null,null,1).single().second
        assertEquals(facts,result.nativeFileMetadata?.verifiedTracks)
        assertEquals(2000L,result.nativeFileMetadata?.durationMillis)
        assertEquals(StreamDurationOrigin.NATIVE_CONTAINER,result.nativeFileMetadata?.durationOrigin)
        assertEquals(400L,StreamBitrateFacts.estimatedBitsPerSecond(result))
    }
    @Test fun `native MAL identity reaches both metadata authorization gates without generic fallback`()=runTest {
        val h=Fixture();val native=h.stream().copy(nativeFileMetadata=StreamFileMetadata(100))
        coEvery { h.resolver.isPlaybackAuthorized(native.url!!,1,"mal:59978:1",1,1,"series") } returns true
        val result=h.service.enrichKnownFiles(listOf(native),1000,StreamDurationOrigin.CATALOG_EPISODE,1,1,1,
            nativeVideoId="mal:59978:1",nativeMediaType="series").single().second
        assertEquals(100L,result.nativeFileMetadata?.selectedFileBytes)
        coVerify(exactly=2){h.resolver.isPlaybackAuthorized(native.url!!,1,"mal:59978:1",1,1,"series")}
        coVerify(exactly=0){h.resolver.isPlaybackAuthorized(any(),any(),null,any(),any(),null)}
    }
    @Test fun `active player duration joins only exact authorized native file bytes and never sibling files`()=runTest {
        val h=Fixture()
        val raw=h.stream().copy(url=null,infoHash="c".repeat(40))
        val sibling=raw.copy(infoHash="d".repeat(40))
        val activeUrl="https://native.example/active-file"
        val siblingUrl="https://native.example/other-file"
        coEvery { h.resolver.cachedPlayableStream(raw,1,1,"mal:59978:1","series") } returns raw.copy(
            url=activeUrl,nativeFileMetadata=StreamFileMetadata(selectedFileBytes=3_355_443_200L))
        coEvery { h.resolver.cachedPlayableStream(sibling,1,1,"mal:59978:1","series") } returns sibling.copy(
            url=siblingUrl,nativeFileMetadata=StreamFileMetadata(selectedFileBytes=2_000_000_000L))
        coEvery { h.resolver.isPlaybackAuthorized(any(),any(),any(),any(),any(),any()) } returns true
        val capture=ActivePlayerFileDuration(1,0,0,"mal:59978:1","series",1,1,
            ActivePlayerFileDuration.originKey(activeUrl)!!,1_443_000L,raw.stableKey(0))
        val shown=h.service.enrichKnownFiles(listOf(raw,sibling),null,null,1,1,1,"mal:59978:1","series",capture)
            .associate { it.first.infoHash to it.second }
        val playing=shown.getValue(raw.infoHash)
        assertEquals(3_355_443_200L,playing.nativeFileMetadata?.selectedFileBytes)
        assertEquals(1_443_000L,playing.nativeFileMetadata?.durationMillis)
        assertEquals(StreamDurationOrigin.NATIVE_CONTAINER,playing.nativeFileMetadata?.durationOrigin)
        assertEquals("Estimated avg 18.6 Mbps",StreamBitrateFacts.label(playing))
        assertNull(shown.getValue(sibling.infoHash).nativeFileMetadata?.durationMillis)
        assertNull(playing.url)
        assertEquals(raw.behaviorHints,playing.behaviorHints)
        assertNull(raw.nativeFileMetadata)
        coVerify(exactly=0) { h.resolver.resolve(any(),any(),any(),any(),any(),any(),any(),any()) }
    }

    @Test fun `active duration rejects another file source viewer revision credential or episode and invalid time`()=runTest {
        val h=Fixture()
        val native=h.stream().copy(nativeFileMetadata=StreamFileMetadata(selectedFileBytes=100))
        coEvery { h.resolver.isPlaybackAuthorized(any(),any(),any(),any(),any(),any()) } returns true
        val capture=ActivePlayerFileDuration(1,0,0,"tt1234567","movie",null,null,
            ActivePlayerFileDuration.originKey(native.url)!!,1_000L,native.stableKey(0))
        val changed=listOf(capture.copy(profileId=2),capture.copy(selectionRevision=2),capture.copy(credentialRevision=1),
            capture.copy(nativeVideoId="tt7654321"),capture.copy(mediaType="series"),capture.copy(season=1,episode=1),
            capture.copy(mediaOriginKey="a".repeat(64)),capture.copy(sourceKey="another-source"),
            capture.copy(durationMillis=0),capture.copy(durationMillis=-1),capture.copy(durationMillis=Long.MIN_VALUE+1))
        for (other in changed) {
            val shown=h.service.enrichKnownFiles(listOf(native),null,null,null,null,1,"tt1234567","movie",other).single().second
            assertEquals(100L,shown.nativeFileMetadata?.selectedFileBytes)
            assertNull(shown.nativeFileMetadata?.durationMillis)
            assertNull(StreamBitrateFacts.estimatedBitsPerSecond(shown))
        }
    }

    @Test fun `active native duration cannot promote an addon or season pack byte claim`()=runTest {
        val h=Fixture();val reported=h.stream()
        coEvery { h.resolver.isPlaybackAuthorized(any(),any(),any(),any(),any(),any()) } returns true
        val capture=ActivePlayerFileDuration(1,0,0,"mal:59978:1","series",1,1,
            ActivePlayerFileDuration.originKey(reported.url)!!,1_443_000L,reported.stableKey(0))
        val shown=h.service.enrichKnownFiles(listOf(reported),null,null,1,1,1,"mal:59978:1","series",capture).single().second
        assertNull(shown.nativeFileMetadata?.selectedFileBytes)
        assertNull(StreamBitrateFacts.estimatedBitsPerSecond(shown))
        assertEquals(100_000_000_000L,shown.behaviorHints?.videoSize)
    }

    private class Fixture {
        val active=MutableStateFlow(1)
        var selectionRevision=0L
        var credentialRevision=0L
        val settings=mockk<DebridSettingsDataStore>{every{activeProfileId}returns active;every{activeProfileSelectionRevision}answers{selectionRevision};every{activeCredentialRevision}answers{credentialRevision}}
        val resolver=mockk<DirectDebridResolver>{coEvery{isPlaybackAuthorized(any(),any(),null,any(),any(),null)}returns true}
        val service=DebridBitrateMetadataService(resolver,settings)
        fun stream()=Stream(null,"Fixture",null,"https://native.example/file",null,null,null,null,
            StreamBehaviorHints(null,null,null,null,videoSize=100_000_000_000L),"Magnify",null)
    }
}
