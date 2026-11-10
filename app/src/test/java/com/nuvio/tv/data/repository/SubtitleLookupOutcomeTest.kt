package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.SubtitleItemDto
import com.nuvio.tv.data.remote.dto.SubtitleResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Subtitle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

/** Real repository and safeApiCall; fake AddonApi returns owned responses without networking. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleLookupOutcomeTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `HTTP failure is observable and cannot masquerade as completed empty`() = runTest {
        val f = Fixture(listOf(addon("failed")))
        coEvery { f.api.getSubtitles(any()) } returns Response.error(503, "owned response".toResponseBody())

        assertTrue(f.lookup().isEmpty())

        assertTrue("HTTP failure needs an error event", f.events().lastOrNull() is NetworkResult.Error)
        assertFalse("A failed lookup cannot announce completed empty", f.completedEmpty())
        assertEquals(listOf(0, 1), f.progress.toList())
        coVerify(exactly = 1) { f.api.getSubtitles(any()) }
    }

    @Test fun `all completed empty is an explicit successful empty result`() = runTest {
        val f = Fixture(listOf(addon("empty")))
        coEvery { f.api.getSubtitles(any()) } returns Response.success(SubtitleResponseDto(emptyList()))

        assertTrue(f.lookup().isEmpty())

        assertTrue("Successful zero results must differ from failure", f.completedEmpty())
        assertFalse(f.events().any { it is NetworkResult.Error })
        assertEquals(listOf(0, 1), f.progress.toList())
    }

    @Test fun `valid partial results survive a different addon failure without a zero results claim`() = runTest {
        val f = Fixture(listOf(addon("working"), addon("failed")))
        coEvery { f.api.getSubtitles(match { it.startsWith("https://working.example.invalid/") }) } returns
            Response.success(SubtitleResponseDto(listOf(subtitleDto())))
        coEvery { f.api.getSubtitles(match { it.startsWith("https://failed.example.invalid/") }) } returns
            Response.error(503, "owned response".toResponseBody())

        val result = f.lookup()

        assertEquals(listOf("owned-en"), result.map { it.id })
        assertTrue("Partial valid results must still be emitted", f.events().any {
            it is NetworkResult.Success<*> && (it.data as? List<*>)?.filterIsInstance<Subtitle>()?.any { sub -> sub.id == "owned-en" } == true
        })
        assertTrue("A partial failure must remain observable", f.events().lastOrNull() is NetworkResult.Error)
        assertFalse(f.completedEmpty())
        assertEquals(2, f.progress.last())
    }

    @Test(timeout = 35_000) fun `real per-addon timeout is an error rather than completed empty`() = runTest {
        val f = Fixture(listOf(addon("timeout")))
        coEvery { f.api.getSubtitles(any()) } coAnswers { awaitCancellation() }

        assertTrue(f.lookup().isEmpty())

        assertTrue("The production 20 second timeout must remain distinguishable", f.events().lastOrNull() is NetworkResult.Error)
        assertFalse(f.completedEmpty())
        assertEquals(listOf(0, 1), f.progress.toList())
    }

    @Test fun `canceling a lookup does not publish completion or a provider failure`() = runTest {
        val f = Fixture(listOf(addon("cancel")))
        val entered = CompletableDeferred<Unit>()
        coEvery { f.api.getSubtitles(any()) } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        val lookup = launch { f.lookup() }
        entered.await()

        lookup.cancelAndJoin()

        assertTrue(lookup.isCancelled)
        assertEquals("Canceled work must not acknowledge addon completion", listOf(0), f.progress.toList())
        assertTrue("Canceled work must not emit final success/error", f.events().isEmpty())
    }

    @Test fun `valid complete results remain the final list and successful event`() = runTest {
        val f = Fixture(listOf(addon("working")))
        coEvery { f.api.getSubtitles(any()) } returns Response.success(SubtitleResponseDto(listOf(subtitleDto())))

        val result = f.lookup()

        assertEquals(listOf("owned-en"), result.map { it.id })
        val event = f.events().lastOrNull()
        assertTrue(event is NetworkResult.Success<*>)
        assertEquals(result, (event as NetworkResult.Success<*>).data)
        assertFalse(f.events().any { it is NetworkResult.Error })
    }

    @Test fun `no eligible addons is completed empty without calling an endpoint`() = runTest {
        val f = Fixture(emptyList())

        assertTrue(f.lookup().isEmpty())

        assertTrue(f.completedEmpty())
        assertFalse(f.events().any { it is NetworkResult.Error })
        coVerify(exactly = 0) { f.api.getSubtitles(any()) }
    }

    private class Fixture(addons: List<Addon>) {
        val api = mockk<AddonApi>()
        private val context = mockk<Context>()
        private val installed = mockk<AddonRepositoryImpl>()
        private val received = Collections.synchronizedList(mutableListOf<Any>())
        val progress = Collections.synchronizedList(mutableListOf<Int>())
        private val repository: SubtitleRepositoryImpl
        init {
            every { context.getString(any()) } returns "Local lookup unavailable"
            every { installed.getInstalledAddons() } returns flowOf(addons)
            repository = SubtitleRepositoryImpl(context, api, installed)
        }
        suspend fun lookup(): List<Subtitle> = repository.getSubtitles(
            type = "series", id = "tt-owned", videoId = "tt-owned:1:1",
            videoHash = "owned-hash", videoSize = 128L, filename = "owned.mp4",
            onProgress = { completed, _, _ -> progress.add(completed) },
            // Any compiles against both callback types. Old List behavior fails assertions.
            onSubtitlesEmitted = { received.add(it) }
        )
        fun events(): List<Any> = synchronized(received) { received.toList() }
        fun completedEmpty(): Boolean = events().any {
            it is NetworkResult.Success<*> && (it.data as? List<*>)?.isEmpty() == true
        }
    }

    private companion object {
        fun addon(name: String) = Addon(
            id = name, name = name, version = "1", description = null, logo = null,
            baseUrl = "https://$name.example.invalid", catalogs = emptyList(),
            types = listOf(ContentType.SERIES),
            resources = listOf(AddonResource("subtitles", listOf("series"), null))
        )
        fun subtitleDto() = SubtitleItemDto("owned-en", "https://captions.example.invalid/owned-en.vtt", "en")
    }
}
