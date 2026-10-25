package com.nuvio.tv.core.providers

import com.nuvio.tv.core.express.ExpressHttpClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProviderManifestClientTest {
    private fun client()=ProviderManifestClient(ExpressHttpClient(OkHttpClient(),true),Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())
    @Test fun `Stremio JSON uses isolated public transport and validates resource manifest`()=runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"fixture","name":"Fixture","version":"1","resources":["stream"],"types":["movie"]}"""))
            val result=client().fetch(server.url("/manifest.json").toString())
            assertEquals("fixture",result.id)
            val request=server.takeRequest();assertNull(request.getHeader("Authorization"));assertNull(request.getHeader("Cookie"))
        }
    }
    @Test fun `unsupported or excessive manifest cannot become valid replacement data`()=runTest {
        MockWebServer().use { server ->
            val values=List(101){"\"stream\""}.joinToString()
            server.enqueue(MockResponse().setBody("{\"id\":\"fixture\",\"name\":\"Fixture\",\"version\":\"1\",\"resources\":[$values]}"))
            assertTrue(runCatching { client().fetch(server.url("/manifest.json").toString()) }.isFailure)
        }
    }
}
