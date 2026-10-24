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
    private class Fixture {
        val active=MutableStateFlow(1)
        var selectionRevision=0L
        var credentialRevision=0L
        val settings=mockk<DebridSettingsDataStore>{every{activeProfileId}returns active;every{activeProfileSelectionRevision}answers{selectionRevision};every{activeCredentialRevision}answers{credentialRevision}}
        val resolver=mockk<DirectDebridResolver>{coEvery{isPlaybackAuthorized(any(),any())}returns true}
        val service=DebridBitrateMetadataService(resolver,settings)
        fun stream()=Stream(null,"Fixture",null,"https://native.example/file",null,null,null,null,
            StreamBehaviorHints(null,null,null,null,videoSize=100_000_000_000L),"Magnify",null)
    }
}
