package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.api.RealDebridApi
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class DebridAccountValidatorTest {
    @Test
    fun `real debrid rejects blank success bodies and validates actual account`() = runTest {
        val api = mockk<RealDebridApi>()
        val validator = DebridAccountValidator(api, mockk())
        coEvery { api.getUser("Bearer test-token") } returns Response.success("{}".toResponseBody())
        assertFalse(validator.validate(DebridProviders.REAL_DEBRID_ID, "test-token"))
        coEvery { api.getUser("Bearer test-token") } returns Response.success("""{"id":123,"username":"demo","type":"premium"}""".toResponseBody())
        assertTrue(validator.validate(DebridProviders.REAL_DEBRID_ID, "test-token"))
    }

    @Test
    fun `real debrid invalid token is not accepted or thrown to caller`() = runTest {
        val api = mockk<RealDebridApi>()
        coEvery { api.getUser(any()) } returns Response.error(401, """{"error":"bad_token"}""".toResponseBody())
        assertFalse(DebridAccountValidator(api, mockk()).validate(DebridProviders.REAL_DEBRID_ID, "invalid"))
    }
}
