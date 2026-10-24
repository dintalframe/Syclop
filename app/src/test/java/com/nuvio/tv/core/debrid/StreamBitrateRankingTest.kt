package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class StreamBitrateRankingTest {
    @Test fun `selected file bytes and exact duration yield labeled container average`() {
        val stream=source("1080p",1_500_000_000L,120_000L)
        assertEquals(100_000_000L,StreamBitrateFacts.estimatedBitsPerSecond(stream))
        assertEquals("Estimated avg 100.0 Mbps",StreamBitrateFacts.label(stream))
    }
    @Test fun `provider season pack bytes without native selected-file evidence remain unknown`() {
        val pack=source("2160p season pack").copy(behaviorHints=StreamBehaviorHints(null,null,null,null,videoSize=100_000_000_000L))
        assertNull(StreamBitrateFacts.estimatedBitsPerSecond(pack))
        assertEquals("Bitrate unknown",StreamBitrateFacts.label(pack))
    }
    @Test fun `zero negative missing and overflowing metadata remain unknown`() {
        for((bytes,millis) in listOf(0L to 1L, -1L to 1L,1L to 0L,Long.MAX_VALUE to 1L))
            assertNull(StreamBitrateFacts.estimatedBitsPerSecond(source("Fixture",bytes,millis)))
        assertNull(StreamBitrateFacts.estimatedBitsPerSecond(source("Fixture",100L,null)))
    }
    @Test fun `explicit provider Mbps is labeled claim and cannot rank as native evidence`() {
        val claim=source("1080p 75.25 Mbps")
        assertEquals(75.25,StreamBitrateFacts.providerClaimMbps(claim)!!,0.001)
        assertNull(StreamBitrateFacts.estimatedBitsPerSecond(claim))
        assertNull(StreamBitrateFacts.providerClaimMbps(source("1080p 25 MB file 30fps")))
    }
    @Test fun `bitrate beats resolution then resolution breaks equal bitrate ties unknown remains last`() {
        val low4k=source("2160p",100_000_000L,100_000L)
        val high1080=source("1080p",200_000_000L,100_000L)
        val tie4k=source("2160p",200_000_000L,100_000L).copy(addonName="Second")
        val unknown=source("2160p claim 999 Mbps")
        val result=DirectDebridStreamFilter.applyPreferences(listOf(unknown,low4k,high1080,tie4k),settings())
        assertEquals(listOf(tie4k,high1080,low4k,unknown),result)
    }
    @Test fun `unknown bitrate remains last also in ascending order`() {
        val known=source("1080p",100_000L,1000L);val unknown=source("2160p")
        val configured=settings().copy(streamPreferences=DebridStreamPreferences(sortCriteria=listOf(
            DebridStreamSortCriterion(DebridStreamSortKey.BITRATE,DebridStreamSortDirection.ASC))))
        assertEquals(listOf(known,unknown),DirectDebridStreamFilter.applyPreferences(listOf(unknown,known),configured))
    }
    @Test fun `global limits are applied after bitrate ranking not incoming provider order`() {
        val first=source("2160p",100_000L,1000L);val second=source("1080p",200_000L,1000L)
        val configured=settings().copy(streamPreferences=settings().streamPreferences.copy(maxResults=1))
        assertEquals(listOf(second),DirectDebridStreamFilter.applyPreferences(listOf(first,second),configured))
    }
    @Test fun `adding bitrate retains existing secondary criteria without duplicate resolution`() {
        val previous=listOf(DebridStreamSortCriterion(DebridStreamSortKey.LANGUAGE),DebridStreamSortCriterion(DebridStreamSortKey.RESOLUTION))
        assertEquals(listOf(DebridStreamSortKey.BITRATE,DebridStreamSortKey.RESOLUTION,DebridStreamSortKey.LANGUAGE),
            DebridStreamSortCriterion.bitrateFirstOrder(previous).map { it.key })
    }
    @Test fun `metadata enrichment preserves source identity through native URL and text changes`() {
        val original=source("Fixture").copy(infoHash="a".repeat(40),fileIdx=2)
        val identity=original.stableKey(0).substringBeforeLast('|')
        val enriched=original.copy(url="https://service.example/private-file",name="Formatted",description="Changed",
            nativeFileMetadata=StreamFileMetadata(100,1000,StreamDurationOrigin.CATALOG_EPISODE,identity))
        assertEquals(original.stableKey(0),enriched.stableKey(0))
        assertNotEquals(enriched.stableKey(0),enriched.stableKey(1))
        assertFalse(enriched.stableKey(0).contains("service.example"))
    }
    private fun settings()=DebridSettings(enabled=true,allDebridApiKey="test-key",preferredResolverProviderId="alldebrid",
        streamPreferences=DebridStreamPreferences(sortCriteria=DebridStreamSortCriterion.bitrateFirstOrder()))
    private fun source(title:String,bytes:Long?=null,millis:Long?=null)=Stream(null,title,null,null,null,"a".repeat(40),0,null,null,
        "Magnify",null,nativeFileMetadata=if(bytes==null&&millis==null)null else StreamFileMetadata(bytes,millis,StreamDurationOrigin.CATALOG_MOVIE))
}
