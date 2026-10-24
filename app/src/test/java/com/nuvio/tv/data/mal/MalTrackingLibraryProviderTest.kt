package com.nuvio.tv.data.mal

import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.ListMembershipChanges
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MalTrackingLibraryProviderTest {
    private val account = MutableStateFlow(MalAccountState(connected = true))
    private val auth = mockk<MalAuthRepository> { every { state } returns account }
    private val repository = mockk<MalRepository> {
        every { state } returns MutableStateFlow(MalLibraryState())
        coEvery { refresh() } returns Unit
        coEvery { update(any(), any(), any()) } returns Unit
        coEvery { remove(any(), any()) } returns Unit
    }
    private val tracking = mockk<MalTrackingProvider> { every { isAuthenticated } returns MutableStateFlow(true) }
    private val provider = MalTrackingLibraryProvider(auth, repository, tracking)
    private val input = LibraryEntryInput("mal:123", "series", "Exact anime")

    @Test fun `status membership changes patch only status and preserve existing count and rating`() = runTest {
        val item = MalAnime(123, "Exact anime", onList = true, watchedEpisodes = 7, score = 9,
            status = MalListStatus.WATCHING, isRewatching = true, timesRewatched = 2)
        coEvery { repository.detail(123, any()) } returns item
        provider.applyMembershipChanges(input, ListMembershipChanges(mapOf("mal:status:on_hold" to true)), false)
        coVerify(exactly = 1) { repository.update(item, MalListEdit(status = MalListStatus.ON_HOLD), account.value.scope) }
        coVerify(exactly = 0) { repository.remove(any(), any()) }
    }

    @Test fun `removing MAL membership requires explicit destructive confirmation`() = runTest {
        coEvery { repository.detail(123, any()) } returns MalAnime(123, "Exact anime", onList = true)
        val changes = ListMembershipChanges(mapOf("mal:status:plan_to_watch" to false))
        assertNotNull(provider.membershipRemovalConfirmation(input, changes))
        assertTrue(runCatching { provider.applyMembershipChanges(input, changes, false) }.isFailure)
        coVerify(exactly = 0) { repository.remove(any(), any()) }
        provider.applyMembershipChanges(input, changes, true)
        coVerify(exactly = 1) { repository.remove(123, account.value.scope) }
    }

    @Test fun `unknown cross IDs and multiple statuses never guess or write an anime`() = runTest {
        val changes = ListMembershipChanges(mapOf("mal:status:watching" to true))
        assertTrue(runCatching { provider.applyMembershipChanges(input.copy(itemId = "tt123"), changes, false) }.isFailure)
        val conflicting = ListMembershipChanges(mapOf("mal:status:watching" to true, "mal:status:completed" to true))
        assertTrue(runCatching { provider.applyMembershipChanges(input, conflicting, false) }.isFailure)
        coVerify(exactly = 0) { repository.update(any(), any(), any()) }
        coVerify(exactly = 0) { repository.detail(any(), any()) }
    }

    @Test fun `a profile change between read and write carries the original guarded scope`() = runTest {
        val originalScope = account.value.scope
        val item = MalAnime(123, "Exact anime", status = MalListStatus.WATCHING, onList = true)
        coEvery { repository.detail(123, originalScope) } coAnswers {
            account.value = account.value.copy(scope = MalScope(2, 1))
            item
        }
        provider.applyMembershipChanges(input, ListMembershipChanges(mapOf("mal:status:completed" to true)), false)
        // Real MalRepository rejects this old scope before its authorized write; it must never be recaptured as profile 2.
        coVerify(exactly = 1) { repository.update(item, MalListEdit(status = MalListStatus.COMPLETED), originalScope) }
        coVerify(exactly = 0) { repository.update(any(), any(), MalScope(2, 1)) }
    }
}
