package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.AllDebridErrorDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetDto
import org.junit.Assert.*
import org.junit.Test

class AllDebridReadinessTest {
    @Test fun `upload ready remains sufficient without requiring a status response`() {
        val result = AllDebridReadiness.classify(true, 42, emptyList())
        assertEquals(AllDebridReadiness.Classification.READY, result.classification)
        assertEquals("uploadedReady=true statusCount=0 matchingId=false matchedStatusCode=unknown classification=READY", result.diagnosticFields())
    }
    @Test fun `only the four documented processing codes are processing`() {
        for (code in 0..3) assertEquals(AllDebridReadiness.Classification.PROCESSING, classify(code).classification)
    }
    @Test fun `code four is ready even when the plain status description differs`() {
        val result = AllDebridReadiness.classify(false, 42, listOf(AllDebridMagnetDto(id = 42, statusCode = 4, status = "synthetic-text")))
        assertEquals(AllDebridReadiness.Classification.READY, result.classification)
    }
    @Test fun `service error and future positive error codes never become processing`() {
        for (code in listOf(5, 12, 13, 999, Int.MAX_VALUE)) assertEquals(AllDebridReadiness.Classification.SERVICE_ERROR, classify(code).classification)
    }
    @Test fun `missing and invalid codes cannot be mistaken for a cache miss`() {
        for (code in listOf(null, -1, Int.MIN_VALUE)) assertEquals(AllDebridReadiness.Classification.MISSING_STATUS, classify(code).classification)
    }
    @Test fun `empty and mismatched ID results do not establish availability`() {
        assertEquals(AllDebridReadiness.Classification.MISSING_STATUS, AllDebridReadiness.classify(false, 42, emptyList()).classification)
        val mismatch = AllDebridReadiness.classify(false, 42, listOf(AllDebridMagnetDto(id = 43, statusCode = 4)))
        assertEquals(AllDebridReadiness.Classification.MISSING_STATUS, mismatch.classification)
        assertFalse(mismatch.matchingId); assertNull(mismatch.matchedStatusCode)
    }
    @Test fun `duplicate matching items fail closed even if one claims ready`() {
        val result = AllDebridReadiness.classify(false, 42, listOf(AllDebridMagnetDto(id = 42, statusCode = 4), AllDebridMagnetDto(id = 42, statusCode = 1)))
        assertEquals(AllDebridReadiness.Classification.MISSING_STATUS, result.classification)
        assertTrue(result.matchingId); assertNull(result.matchedStatusCode)
    }
    @Test fun `item error cannot be overridden by a contradictory ready code`() {
        val result = AllDebridReadiness.classify(false, 42, listOf(AllDebridMagnetDto(id = 42, statusCode = 4, error = AllDebridErrorDto("synthetic-private"))))
        assertEquals(AllDebridReadiness.Classification.SERVICE_ERROR, result.classification)
        assertFalse(result.diagnosticFields().contains("synthetic-private"))
    }
    @Test fun `absent upload readiness remains identifiable without inventing false`() {
        val result = AllDebridReadiness.classify(null, 42, listOf(AllDebridMagnetDto(id = 42, statusCode = 1)))
        assertEquals(AllDebridReadiness.Classification.PROCESSING, result.classification)
        assertTrue(result.diagnosticFields().startsWith("uploadedReady=missing "))
    }
    @Test fun `diagnostics cap counts and refuse unbounded numeric codes or private DTO content`() {
        val privateId = 876543210123L
        val items = List(1_005) { index -> AllDebridMagnetDto(id = if (index == 0) privateId else index.toLong(),
            statusCode = Int.MAX_VALUE, filename = "synthetic-private-name", hash = "synthetic-private-hash", status = "synthetic-private-status") }
        val result = AllDebridReadiness.classify(false, privateId, items)
        assertEquals(1_000, result.statusCount); assertNull(result.matchedStatusCode)
        assertEquals("uploadedReady=false statusCount=1000 matchingId=true matchedStatusCode=unknown classification=SERVICE_ERROR", result.diagnosticFields())
        assertFalse(result.diagnosticFields().contains(privateId.toString()))
        assertFalse(result.diagnosticFields().contains("synthetic-private"))
    }
    private fun classify(code: Int?) = AllDebridReadiness.classify(false, 42, listOf(AllDebridMagnetDto(id = 42, statusCode = code)))
}
