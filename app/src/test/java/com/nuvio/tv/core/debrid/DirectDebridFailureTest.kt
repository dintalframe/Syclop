package com.nuvio.tv.core.debrid

import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class DirectDebridFailureTest {
    @Test fun `HTTP 451 remains an explicit source refusal distinct from account or network failure`() {
        val failure = directDebridHttpFailure(451, "realdebrid").detail
        assertEquals(DirectDebridFailureReason.SOURCE_REJECTED, failure.reason)
        assertEquals("realdebrid", failure.providerId)
        assertNull(failure.retryAfterSeconds)
        assertEquals(com.nuvio.tv.R.string.syclop_resolver_source_rejected, failure.messageRes)
        assertEquals(DirectDebridFailureReason.NETWORK_FAILURE, directDebridHttpFailure(503, "realdebrid").detail.reason)
        assertEquals(DirectDebridFailureReason.UNAVAILABLE, directDebridHttpFailure(404, "realdebrid").detail.reason)
    }
    @Test fun `HTTP and transport classifications never copy credential bearing messages`() {
        assertEquals(DirectDebridFailureReason.AUTH_EXPIRED, directDebridHttpFailure(401, "realdebrid").detail.reason)
        assertEquals(DirectDebridFailureReason.RATE_LIMITED, directDebridHttpFailure(429, "realdebrid", "120").detail.reason)
        assertEquals(120L, directDebridHttpFailure(429, "realdebrid", "120").detail.retryAfterSeconds)
        assertEquals(DirectDebridFailureReason.TIMED_OUT,
            directDebridExceptionFailure(SocketTimeoutException("https://private.invalid/token"), "realdebrid").detail.reason)
        val result = directDebridExceptionFailure(AllDebridApiException("private-token https://private.invalid",
            200, DirectDebridFailureReason.AUTH_EXPIRED), "alldebrid")
        assertEquals(DirectDebridFailureReason.AUTH_EXPIRED, result.detail.reason)
        assertFalse(result.toString().contains("private"))
    }

    @Test fun `retry after accepts delta seconds and HTTP date but rejects malformed values`() {
        assertEquals(60L, parseDebridRetryAfter("60", 0L))
        assertEquals(60L, parseDebridRetryAfter("Thu, 01 Jan 1970 00:01:00 GMT", 0L))
        assertNull(parseDebridRetryAfter("-1", 0L))
        assertNull(parseDebridRetryAfter("60 seconds", 0L))
        assertNull(parseDebridRetryAfter("Thu, 01 Jan 1970 00:01:00 GMT trailing", 0L))
    }
}
