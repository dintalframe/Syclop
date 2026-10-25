package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class SelectedSourceRankingTest {
    private fun source(name:String,bytes:Long,millis:Long,video:VerifiedSourceVideo?=null)=Stream(null,name,null,null,null,"a".repeat(40),0,null,null,
        "Magnify",null,nativeFileMetadata=StreamFileMetadata(bytes,millis,StreamDurationOrigin.NATIVE_CONTAINER,
            verifiedTracks=video?.let { VerifiedSourceMediaFacts(video=it) }))
    private fun settings()=DebridSettings(enabled=true,allDebridApiKey="synthetic",preferredResolverProviderId="alldebrid",
        streamPreferences=DebridStreamPreferences(sortCriteria=DebridStreamSortCriterion.bitrateFirstOrder()))
    @Test fun `equal bitrate and resolution prefer larger selected file before filename release quality`() {
        val small=source("1080p BluRay REMUX",100,1000)
        val large=source("1080p WEBRip",200,2000)
        assertEquals(listOf(large,small),DirectDebridStreamFilter.applyPreferences(listOf(small,large),settings()))
    }
    @Test fun `actual selected dimensions override a false filename resolution for ranking`() {
        val verified=source("2160p",100,1000,VerifiedSourceVideo(1920,1080))
        val native4k=source("1080p",100,1000,VerifiedSourceVideo(3840,2160))
        assertEquals(listOf(native4k,verified),DirectDebridStreamFilter.applyPreferences(listOf(verified,native4k),settings()))
    }
    @Test fun `selected file bytes override provider pack and text size hints`() {
        val file=source("100 GB season pack",100,1000).copy(behaviorHints=StreamBehaviorHints(null,null,null,null,videoSize=100_000_000_000L))
        assertEquals(100L,StreamTextSizeParser.effectiveSizeBytes(file))
    }
    @Test fun `new default tie never repeats size criterion or replaces a later custom order`() {
        val custom=listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE,DebridStreamSortDirection.ASC),
            DebridStreamSortCriterion(DebridStreamSortKey.LANGUAGE))
        assertEquals(custom,DebridStreamSortCriterion.upgradeLegacySizeTie(custom))
        assertTrue(DebridStreamSortCriterion.upgradeLegacySizeTie(emptyList()).isEmpty())
        assertEquals(1,DebridStreamSortCriterion.bitrateFirstOrder(custom).count { it.key==DebridStreamSortKey.SIZE })
    }
}
