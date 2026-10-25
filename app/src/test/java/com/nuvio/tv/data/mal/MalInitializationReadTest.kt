package com.nuvio.tv.data.mal

import com.nuvio.tv.core.initialization.InitializationFailure
import com.nuvio.tv.core.initialization.InitializationReadException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MalInitializationReadTest {
    @Test
    fun `settings refresh and initialization share one full library read and preserve truncation`() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.api.library(any()) } coAnswers {
            fixture.libraryCalls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            MalLibrary(listOf(ANIME, ANIME.copy(id = 2)), truncated = true)
        }
        val refreshing = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.refresh() }
        withTimeout(TIMEOUT_MS) { entered.await() }
        val importing = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.repository.awaitInitializationImport(fixture.repository.captureInitializationReadScope())
        }
        release.complete(Unit)
        withTimeout(TIMEOUT_MS) { refreshing.await() }
        val result = withTimeout(TIMEOUT_MS) { importing.await() }
        assertEquals(1, fixture.libraryCalls.get())
        assertEquals(2, result.count ?: -1)
        assertTrue(result.truncated)
        assertEquals(listOf(1L, 2L), fixture.repository.state.value.items.map { it.id })
        assertEquals(result, fixture.repository.awaitInitializationImport(fixture.repository.captureInitializationReadScope()))
        assertEquals(1, fixture.libraryCalls.get()) // Also reuse an already-finished actual startup read.
        coVerify(exactly = 0) { fixture.api.edit(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.api.remove(any(), any()) }
        coVerify(exactly = 0) { fixture.api.update(any(), any(), any(), any()) }
    }

    @Test
    fun `refresh cannot overwrite a same-account list edit queued during its network read`() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val editEntered = CompletableDeferred<Unit>()
        coEvery { fixture.api.library(any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            MalLibrary(listOf(ANIME.copy(watchedEpisodes = 1)))
        }
        coEvery { fixture.api.edit(any(), any(), any()) } answers { editEntered.complete(Unit); Unit }
        coEvery { fixture.api.detail(ANIME.id, any()) } returns ANIME.copy(watchedEpisodes = 5)
        val refreshing = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.refresh() }
        withTimeout(TIMEOUT_MS) { entered.await() }
        val editing = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.repository.update(ANIME, MalListEdit(watched = 5))
        }
        assertFalse(editEntered.isCompleted)
        release.complete(Unit)
        withTimeout(TIMEOUT_MS) { refreshing.await(); editing.await() }
        assertTrue(editEntered.isCompleted)
        assertEquals(5, fixture.repository.state.value.items.single().watchedEpisodes)
    }

    @Test
    fun `replaced account rejects an old in-flight list response and keeps the new scope empty`() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.api.library(any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            MalLibrary(listOf(ANIME))
        }
        val importing = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { fixture.repository.awaitInitializationImport(fixture.repository.captureInitializationReadScope()) }
        }
        withTimeout(TIMEOUT_MS) { entered.await() }
        val replacement = fixture.account.value.scope.copy(generation = 2)
        fixture.account.value = fixture.account.value.copy(scope = replacement, username = "other-viewer")
        release.complete(Unit)
        val failure = withTimeout(TIMEOUT_MS) { importing.await() }.exceptionOrNull()
        assertTrue(failure is InitializationReadException)
        assertEquals(InitializationFailure.ACCOUNT_CHANGED, (failure as InitializationReadException).reason)
        withTimeout(TIMEOUT_MS) {
            while (fixture.repository.state.value.scope != replacement) delay(1)
        }
        assertTrue(fixture.repository.state.value.items.isEmpty())
    }

    @Test
    fun `cancelled read propagates cancellation preserves data and does not poison the next import`() = runBlocking {
        val fixture = Fixture()
        coEvery { fixture.api.library(any()) } returns MalLibrary(listOf(ANIME))
        fixture.repository.refresh()
        val entered = CompletableDeferred<Unit>()
        coEvery { fixture.api.library(any()) } coAnswers { entered.complete(Unit); awaitCancellation() }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.refresh() }
        withTimeout(TIMEOUT_MS) { entered.await() }
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        assertFalse(fixture.repository.state.value.loading)
        assertEquals(listOf(ANIME), fixture.repository.state.value.items)
        coEvery { fixture.api.library(any()) } returns MalLibrary(listOf(ANIME.copy(watchedEpisodes = 4)))
        fixture.repository.refresh()
        val next = fixture.repository.awaitInitializationImport(fixture.repository.captureInitializationReadScope())
        assertTrue(next.complete)
        assertEquals(4, fixture.repository.state.value.items.single().watchedEpisodes)
    }

    private class Fixture {
        val api = mockk<MalApiClient>()
        val account = MutableStateFlow(MalAccountState(scope = MalScope(1, 1), connected = true, username = "viewer"))
        val libraryCalls = AtomicInteger()
        val repository: MalRepository
        init {
            val store = mockk<MalAuthStore>()
            every { store.isCurrent(any()) } answers { firstArg<MalScope>() == account.value.scope }
            val auth = mockk<MalAuthRepository>()
            every { auth.state } returns account
            every { auth.store } returns store
            coEvery { auth.authorized<Any?>(any(), any()) } coAnswers {
                secondArg<suspend (String) -> Any?>().invoke("test-token")
            }
            repository = MalRepository(auth, api)
        }
    }

    private companion object {
        val ANIME = MalAnime(1, "Known anime", totalEpisodes = 12, onList = true)
        const val TIMEOUT_MS = 5_000L
    }
}
