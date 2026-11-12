package com.nuvio.tv.ui.screens.stream

import android.content.Context
import android.content.res.Resources
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DirectDebridFailure
import com.nuvio.tv.core.debrid.DirectDebridFailureReason
import com.nuvio.tv.core.debrid.DirectDebridResolveResult
import com.nuvio.tv.core.party.PartyRepository
import com.nuvio.tv.core.party.PartyUiState
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Real final-failure publication and acknowledgement; no provider, account or player tasks run. */
class StreamSourceRepairViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test fun `transient notification acknowledgement cannot erase a typed resolution failure`() {
        for (reason in DirectDebridFailureReason.entries) withFixture { fixture ->
            val expected = when (reason) {
                DirectDebridFailureReason.SOURCE_REJECTED -> "Real-Debrid rejected this source"
                DirectDebridFailureReason.RATE_LIMITED -> "Real-Debrid is limiting requests"
                else -> EXPLANATION
            }
            fixture.fail(reason)
            assertEquals(expected, fixture.vm.uiState.value.playbackErrorMessage)

            fixture.vm.onPlaybackErrorShown()

            assertEquals(reason.name, expected, fixture.vm.uiState.value.playbackErrorMessage)
            assertEquals(reason, fixture.vm.uiState.value.sourceResolutionFailure?.reason)
            assertEquals(listOf(fixture.source), fixture.vm.uiState.value.filteredStreams)
            assertEquals(listOf(fixture.source), fixture.vm.uiState.value.allStreams)
        }
    }

    @Test fun `source refusal and rate limits name registered providers`() = withFixture { fixture ->
        val providerNames = mapOf(
            "torbox" to "TorBox",
            "realdebrid" to "Real-Debrid",
            "alldebrid" to "AllDebrid",
            "unregistered-provider" to null,
            null to null
        )
        val reasons = mapOf(
            DirectDebridFailureReason.SOURCE_REJECTED to "rejected this source",
            DirectDebridFailureReason.RATE_LIMITED to "is limiting requests"
        )
        for ((reason, explanation) in reasons) {
            for ((providerId, providerName) in providerNames) {
                fixture.fail(DirectDebridResolveResult.Failure(DirectDebridFailure(reason, providerId)))
                val expected = providerName?.let { "$it $explanation" } ?: EXPLANATION
                assertEquals(expected, fixture.vm.uiState.value.playbackErrorMessage)
            }
        }
    }

    @Test fun `choosing a source clears the previous resolution explanation`() = withFixture { fixture ->
        fixture.fail(DirectDebridFailureReason.AUTH_EXPIRED)

        fixture.vm.onEvent(StreamScreenEvent.OnStreamSelected(fixture.source))

        assertNull(fixture.vm.uiState.value.playbackErrorMessage)
        assertNull(fixture.vm.uiState.value.sourceResolutionFailure)
        assertEquals(listOf(fixture.source), fixture.vm.uiState.value.allStreams)
    }

    @Test fun `explicit search refresh clears the previous resolution explanation`() = withFixture { fixture ->
        fixture.fail(DirectDebridFailureReason.NETWORK_FAILURE)

        fixture.vm.onEvent(StreamScreenEvent.OnRefresh)

        assertNull(fixture.vm.uiState.value.playbackErrorMessage)
        assertNull(fixture.vm.uiState.value.sourceResolutionFailure)
        assertEquals(listOf(fixture.source), fixture.vm.uiState.value.allStreams)
    }

    @Test fun `unchanged resume reload retains the typed resolution explanation`() = withFixture { fixture ->
        // Establish current owner revisions before simulating discovery canceled by source selection.
        fixture.vm.onEvent(StreamScreenEvent.OnResume)
        fixture.vm.cancelStreamsLoad()
        fixture.fail(DirectDebridFailureReason.AUTH_EXPIRED)
        fixture.vm.onHostStopped()

        fixture.vm.onEvent(StreamScreenEvent.OnResume)

        assertEquals(EXPLANATION, fixture.vm.uiState.value.playbackErrorMessage)
        assertEquals(DirectDebridFailureReason.AUTH_EXPIRED, fixture.vm.uiState.value.sourceResolutionFailure?.reason)
        assertEquals(listOf(fixture.source), fixture.vm.uiState.value.filteredStreams)
        assertEquals(listOf(fixture.source), fixture.vm.uiState.value.allStreams)
    }

    @Test fun `stale source automatic refresh retains its transient explanation until acknowledged`() = withFixture { fixture ->
        fixture.vm.cancelStreamsLoad()

        fixture.fail(DirectDebridResolveResult.Stale)

        assertEquals(EXPLANATION, fixture.vm.uiState.value.playbackErrorMessage)
        assertNull(fixture.vm.uiState.value.sourceResolutionFailure)
        fixture.vm.onPlaybackErrorShown()
        assertNull(fixture.vm.uiState.value.playbackErrorMessage)
    }

    @Test fun `untyped transport notification remains transient after a typed failure`() = withFixture { fixture ->
        fixture.fail(DirectDebridFailureReason.AUTH_EXPIRED)
        fixture.showTransportError("Unsupported transport")

        fixture.vm.onPlaybackErrorShown()

        assertNull(fixture.vm.uiState.value.playbackErrorMessage)
        assertNull(fixture.vm.uiState.value.sourceResolutionFailure)
    }

    private inline fun withFixture(check: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { check(fixture) } finally { fixture.close() }
    }

    private class Fixture {
        val source = Stream(name = "Synthetic source", title = null, description = null, url = null,
            ytId = null, infoHash = "a".repeat(40), fileIdx = null, externalUrl = null,
            behaviorHints = null, addonName = "Fixture", addonLogo = null)
        private val testResources = mockk<Resources> {
            every { getString(any()) } returns EXPLANATION
            for (provider in listOf("TorBox", "Real-Debrid", "AllDebrid")) {
                every { getString(R.string.syclop_resolver_source_rejected_by_provider, provider) } returns
                    "$provider rejected this source"
                every { getString(R.string.syclop_resolver_rate_limited_by_provider, provider) } returns
                    "$provider is limiting requests"
            }
        }
        private val context = mockk<Context> {
            every { getString(any()) } returns EXPLANATION
            every { resources } returns testResources
        }
        private val debridStore = mockk<DebridSettingsDataStore>(relaxed = true) {
            every { settings } returns emptyFlow()
            every { activeProfileId } returns MutableStateFlow(1)
            every { activeProfileSelectionRevision } returns 1L
            every { activeCredentialRevision } returns 1L
        }
        private val playerStore = mockk<PlayerSettingsDataStore> {
            every { playerSettings } returns emptyFlow()
        }
        private val badgeStore = mockk<StreamBadgeSettingsDataStore> {
            every { settings } returns emptyFlow()
        }
        private val torrentStore = mockk<TorrentSettings> {
            every { settings } returns emptyFlow()
        }
        private val party = mockk<PartyRepository> {
            every { state } returns MutableStateFlow(PartyUiState())
            every { sharedStream } returns MutableStateFlow(null)
        }
        private val watchProgress = mockk<WatchProgressRepository> {
            every { getProgress(any(), any()) } returns flowOf(null)
        }
        val vm = StreamScreenViewModel(context, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), playerStore, mockk(relaxed = true),
            mockk(relaxed = true), badgeStore, mockk(relaxed = true), torrentStore,
            watchProgress, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), debridStore,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), party,
            mockk(relaxed = true), SavedStateHandle(mapOf("profileId" to "1", "videoId" to "fixture",
                "contentType" to "movie", "title" to "Fixture", "manualSelection" to "true")))
        private val models = ViewModelStore().apply { put("fixture", vm) }

        init {
            @Suppress("UNCHECKED_CAST")
            val state = field("_uiState").get(vm) as MutableStateFlow<StreamScreenUiState>
            state.value = state.value.copy(isLoading = false, allStreams = listOf(source),
                filteredStreams = listOf(source))
        }

        fun fail(reason: DirectDebridFailureReason) =
            fail(DirectDebridResolveResult.Failure(DirectDebridFailure(reason, "realdebrid", 60L)))

        fun fail(result: DirectDebridResolveResult) {
            StreamScreenViewModel::class.java.getDeclaredMethod("showDirectDebridResolutionFailure",
                DirectDebridResolveResult::class.java).apply { isAccessible = true }
                .invoke(vm, result)
        }

        fun showTransportError(message: String) {
            StreamScreenViewModel::class.java.getDeclaredMethod("showDirectDebridPlaybackError",
                String::class.java, Boolean::class.javaPrimitiveType, DirectDebridFailure::class.java)
                .apply { isAccessible = true }.invoke(vm, message, false, null)
        }

        fun close() = models.clear()
        private fun field(name: String) = StreamScreenViewModel::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
    }

    companion object { private const val EXPLANATION = "Fixed resolver explanation" }
}
