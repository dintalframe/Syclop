package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.*
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class DebridBitratePresentationTest {
    private val presentation=DebridStreamPresentation(mockk(),mockk(),DebridStreamFormatter(DebridStreamTemplateEngine()))
    private fun settings(provider:String)=DebridSettings(enabled=true,realDebridApiKey="test-rd",allDebridApiKey="test-ad",
        preferredResolverProviderId=provider,streamPreferences=DebridStreamPreferences(maxResults=1,
            sortCriteria=DebridStreamSortCriterion.bitrateFirstOrder()))
    @Test fun `Magnify RD candidates without cache-check support participate in global limit and ranking`() {
        val low=source("First","2160p",100);val high=source("Second","1080p",200)
        val groups=listOf(AddonStreams("First",null,listOf(low)),AddonStreams("Second",null,listOf(high)))
        val displayed=presentation.apply(groups,settings("realdebrid"))
        assertEquals(2,displayed.sumOf { it.streams.size }) // No per-group premature limit.
        assertEquals(listOf(high),presentation.rankAll(displayed.flatMap { it.streams },settings("realdebrid")))
    }
    @Test fun `Magnify AllDebrid unknown cache state is sortable despite lacking cache-check endpoint`() {
        val unknown=source("First","2160p",null);val known=source("Second","1080p",100)
        assertFalse(DebridProviders.AllDebrid.supports(DebridProviderCapability.LocalTorrentCacheCheck))
        assertEquals(listOf(known),presentation.rankAll(listOf(unknown,known),settings("alldebrid")))
    }
    @Test fun `unlimited global view keeps stable tie order`() {
        val one=source("A","1080p",100);val two=source("B","1080p",100)
        assertEquals(listOf(one,two),presentation.rankAll(listOf(one,two),settings("alldebrid"),false))
    }
    private fun source(addon:String,title:String,bytes:Long?)=Stream(null,title,null,null,null,"a".repeat(40),0,null,null,addon,null,
        nativeFileMetadata=bytes?.let { StreamFileMetadata(it,1000,StreamDurationOrigin.CATALOG_MOVIE) })
}
