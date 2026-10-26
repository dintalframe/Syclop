package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamAvailabilityScope
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Actual scoped service with mocked account storage and cache APIs; no real credentials or network. */
class LocalDebridAvailabilityServiceTest {
    @Test fun `markChecking marks valid local hash with account provenance and leaves direct URL alone`() = runTest {
        val h = harness()
        val result = h.service.markChecking(listOf(group(listOf(
            stream(url = "https://example.com/video.mkv", infoHash = null), stream(infoHash = HASH_A.uppercase())
        )))).single().streams
        assertNull(result[0].debridCacheStatus)
        assertTrue(result[0].nativeDebridAvailability.isEmpty())
        assertEquals(StreamDebridCacheState.CHECKING, result[1].debridCacheStatus?.state)
        assertEquals(DebridProviders.TORBOX_ID, result[1].debridCacheStatus?.providerId)
        assertEquals(h.scope(), result[1].debridCacheStatus?.nativeScope)
        assertEquals(HASH_A, result[1].debridCacheStatus?.infoHash)
        assertEquals(1_000L, result[1].debridCacheStatus?.checkedAtMs)
        assertEquals(6_000L, result[1].debridCacheStatus?.expiresAtMs)
        coVerify(exactly = 0) { h.backend.checkCached(any(), any()) }
    }

    @Test fun `cached and negative results retain separate native observations and canonical hashes`() = runTest {
        val h = harness()
        coEvery { h.backend.checkCached(any(), any()) } returns mapOf(HASH_A to LocalDebridCachedItem("cached.mkv", 123L))
        val result = h.service.annotateCachedAvailability(listOf(group(listOf(stream(infoHash = HASH_A.uppercase()),
            stream(infoHash = HASH_B))))).single().streams
        assertEquals(StreamDebridCacheState.CACHED, result[0].debridCacheStatus?.state)
        assertEquals("cached.mkv", result[0].debridCacheStatus?.cachedName)
        assertEquals(123L, result[0].debridCacheStatus?.cachedSize)
        assertEquals(31_000L, result[0].debridCacheStatus?.expiresAtMs)
        assertEquals(StreamDebridCacheState.NOT_CACHED, result[1].debridCacheStatus?.state)
        assertEquals(11_000L, result[1].debridCacheStatus?.expiresAtMs)
        assertTrue(result.all { it.nativeDebridAvailability.single().nativeScope == h.scope() })
        coVerify(exactly = 1) { h.backend.checkCached(any(), listOf(HASH_A, HASH_B)) }
    }

    @Test fun `failed cache check is unknown rather than negative`() = runTest {
        val h = harness()
        val row = h.service.annotateCachedAvailability(listOf(group(listOf(stream())))).single().streams.single()
        assertEquals(StreamDebridCacheState.UNKNOWN, row.debridCacheStatus?.state)
        assertEquals(6_000L, row.debridCacheStatus?.expiresAtMs)
        assertFalse(row.allNativeAccountsUnavailable(h.settings.value, h.scope(), h.now.get()))
    }

    @Test fun `preferred Premiumize negative cannot hide a TorBox positive`() = runTest {
        val h = harness(DebridSettings(enabled = true, premiumizeApiKey = "synthetic-pm", torboxApiKey = "synthetic-tb",
            preferredResolverProviderId = DebridProviders.PREMIUMIZE_ID))
        coEvery { h.backend.checkCached(match { it.provider.id == DebridProviders.PREMIUMIZE_ID }, any()) } returns emptyMap()
        coEvery { h.backend.checkCached(match { it.provider.id == DebridProviders.TORBOX_ID }, any()) } returns
            mapOf(HASH_A to LocalDebridCachedItem("episode.mkv", 1_024L))
        val row = h.service.annotateCachedAvailability(listOf(group(listOf(stream())))).single().streams.single()
        assertEquals(setOf(DebridProviders.PREMIUMIZE_ID, DebridProviders.TORBOX_ID), row.nativeDebridAvailability.map { it.providerId }.toSet())
        assertEquals(StreamDebridCacheState.NOT_CACHED, row.nativeDebridAvailability.first { it.providerId == DebridProviders.PREMIUMIZE_ID }.state)
        assertEquals(StreamDebridCacheState.CACHED, row.debridCacheStatus?.state)
        assertEquals(DebridProviders.TORBOX_ID, row.debridCacheStatus?.providerId)
        assertFalse(row.allNativeAccountsUnavailable(h.settings.value, h.scope(), h.now.get()))
        coVerify(exactly = 1) { h.backend.checkCached(match { it.provider.id == DebridProviders.PREMIUMIZE_ID }, listOf(HASH_A)) }
        coVerify(exactly = 1) { h.backend.checkCached(match { it.provider.id == DebridProviders.TORBOX_ID }, listOf(HASH_A)) }
    }

    @Test fun `AllDebrid and Real Debrid have unknown bulk availability and remain eligible`() = runTest {
        val h = harness(DebridSettings(enabled = true, allDebridApiKey = "synthetic-ad", realDebridApiKey = "synthetic-rd",
            preferredResolverProviderId = DebridProviders.ALL_DEBRID_ID))
        val row = h.service.annotateCachedAvailability(listOf(group(listOf(stream())))).single().streams.single()
        assertEquals(setOf(DebridProviders.ALL_DEBRID_ID, DebridProviders.REAL_DEBRID_ID), row.nativeDebridAvailability.map { it.providerId }.toSet())
        assertTrue(row.nativeDebridAvailability.all { it.state == StreamDebridCacheState.UNKNOWN })
        assertFalse(row.allNativeAccountsUnavailable(h.settings.value, h.scope(), h.now.get()))
        coVerify(exactly = 0) { h.backend.checkCached(any(), any()) }
    }

    @Test fun `provider reported CACHED claim is replaced by native checking rather than trusted`() = runTest {
        val h = harness()
        val claimed = stream().copy(debridCacheStatus = StreamDebridCacheStatus(DebridProviders.TORBOX_ID,
            "Provider claim", StreamDebridCacheState.CACHED, "unverified-name.mkv", 999L))
        val row = h.service.markChecking(listOf(group(listOf(claimed)))).single().streams.single()
        assertEquals(StreamDebridCacheState.CHECKING, row.debridCacheStatus?.state)
        assertNotNull(row.debridCacheStatus?.nativeScope)
        assertNull(row.debridCacheStatus?.cachedName)
        assertNull(row.debridCacheStatus?.cachedSize)
        assertEquals(row.debridCacheStatus, row.nativeDebridAvailability.single())
    }

    @Test fun `positive negative and unknown observations recheck exactly at their TTL`() = runTest {
        for ((response, ttl) in listOf(
            mapOf(HASH_A to LocalDebridCachedItem("file.mkv", 123L)) to 30_000L,
            emptyMap<String, LocalDebridCachedItem>() to 10_000L,
            null to 5_000L
        )) {
            val h = harness()
            coEvery { h.backend.checkCached(any(), any()) } returns response
            val groups = listOf(group(listOf(stream())))
            h.service.annotateCachedAvailability(groups)
            h.now.addAndGet(ttl - 1)
            h.service.annotateCachedAvailability(groups)
            coVerify(exactly = 1) { h.backend.checkCached(any(), any()) }
            h.now.incrementAndGet()
            h.service.annotateCachedAvailability(groups)
            coVerify(exactly = 2) { h.backend.checkCached(any(), any()) }
        }
    }

    @Test fun `profile revision changed during a check discards its observation and old scope cache`() = runTest {
        val h = harness()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { h.backend.checkCached(any(), any()) } coAnswers {
            entered.complete(Unit); release.await(); mapOf(HASH_A to LocalDebridCachedItem("old-scope.mkv", 123L))
        }
        val result = async { h.service.annotateCachedAvailability(listOf(group(listOf(stream())))) }
        entered.await()
        h.selectionRevision.incrementAndGet()
        release.complete(Unit)
        val discarded = result.await().single().streams.single()
        assertTrue(discarded.nativeDebridAvailability.isEmpty())
        assertNull(discarded.debridCacheStatus)
        val fresh = h.service.annotateCachedAvailability(listOf(group(listOf(stream())))).single().streams.single()
        assertEquals(h.scope(), fresh.nativeDebridAvailability.single().nativeScope)
        coVerify(exactly = 2) { h.backend.checkCached(any(), any()) }
    }

    @Test fun `captured search settings are cancelled if account changes during deferred response`() = runTest {
        val h = harness()
        val expected = h.settings.value
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { h.backend.checkCached(any(), any()) } coAnswers { entered.complete(Unit); release.await(); emptyMap() }
        val result = async { h.service.annotateCachedAvailability(listOf(group(listOf(stream()))), expected) }
        entered.await()
        h.settings.value = expected.copy(torboxApiKey = "synthetic-replacement")
        h.credentialRevision.incrementAndGet()
        release.complete(Unit)
        try { result.await(); fail("Expected stale search cancellation") } catch (_: CancellationException) { }
    }

    @Test fun `mismatched expected settings reject a search before any API calls`() = runTest {
        val h = harness()
        val expected = h.settings.value.copy(torboxApiKey = "different-synthetic-account")
        try {
            h.service.annotateCachedAvailability(listOf(group(listOf(stream()))), expected)
            fail("Expected stale settings cancellation")
        } catch (_: CancellationException) { }
        try {
            h.service.markChecking(listOf(group(listOf(stream()))), expected)
            fail("Expected stale settings cancellation")
        } catch (_: CancellationException) { }
        coVerify(exactly = 0) { h.backend.checkCached(any(), any()) }
    }

    @Test fun `same hash is rechecked for a new account instead of reusing another account result`() = runTest {
        val h = harness()
        coEvery { h.backend.checkCached(match { it.apiKey == "synthetic-tb" }, any()) } returns
            mapOf(HASH_A to LocalDebridCachedItem("first-account.mkv", 1L))
        coEvery { h.backend.checkCached(match { it.apiKey == "replacement-tb" }, any()) } returns emptyMap()
        val groups = listOf(group(listOf(stream())))
        assertEquals(StreamDebridCacheState.CACHED, h.service.annotateCachedAvailability(groups).single().streams.single().debridCacheStatus?.state)
        h.settings.value = h.settings.value.copy(torboxApiKey = "replacement-tb")
        h.credentialRevision.incrementAndGet()
        val row = h.service.annotateCachedAvailability(groups).single().streams.single()
        assertEquals(StreamDebridCacheState.NOT_CACHED, row.debridCacheStatus?.state)
        assertEquals(h.scope(), row.debridCacheStatus?.nativeScope)
        coVerify(exactly = 2) { h.backend.checkCached(any(), any()) }
    }

    @Test fun `Base32 BTIH is canonicalized and deduplicated against the equivalent hex hash`() = runTest {
        val h = harness()
        val hex = "0".repeat(40)
        coEvery { h.backend.checkCached(any(), any()) } returns mapOf(hex to LocalDebridCachedItem("file.mkv", 123L))
        val rows = h.service.annotateCachedAvailability(listOf(group(listOf(stream(infoHash = "A".repeat(32)),
            stream(infoHash = hex))))).single().streams
        assertTrue(rows.all { it.nativeDebridAvailability.single().infoHash == hex })
        assertTrue(rows.all { it.debridCacheStatus?.state == StreamDebridCacheState.CACHED })
        coVerify(exactly = 1) { h.backend.checkCached(any(), listOf(hex)) }
    }

    @Test fun `invalid hashes never reach cache APIs or acquire native provenance`() = runTest {
        val h = harness()
        val rows = h.service.annotateCachedAvailability(listOf(group(listOf("ABC123", "g".repeat(40),
            "8".repeat(32), "magnet:?xt=urn:btih:$HASH_A").map { stream(infoHash = it) }))).single().streams
        assertTrue(rows.all { it.nativeDebridAvailability.isEmpty() && it.debridCacheStatus == null })
        coVerify(exactly = 0) { h.backend.checkCached(any(), any()) }
    }

    @Test fun `bounded cache check timeout remains unknown and does not hide the source`() = runTest {
        val h = harness()
        coEvery { h.backend.checkCached(any(), any()) } coAnswers { delay(6_000); emptyMap() }
        val row = h.service.annotateCachedAvailability(listOf(group(listOf(stream())))).single().streams.single()
        assertEquals(StreamDebridCacheState.UNKNOWN, row.debridCacheStatus?.state)
        assertFalse(row.allNativeAccountsUnavailable(h.settings.value, h.scope(), h.now.get()))
    }

    @Test fun `base32 normalization is independent of Turkish device locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("42".repeat(20), canonicalAvailabilityHash("IJBEEQSCIJBEEQSCIJBEEQSCIJBEEQSC"))
        } finally { Locale.setDefault(original) }
    }

    @Test fun `a later chunk timeout preserves completed observations without marking remaining hashes unavailable`() = runTest {
        val h = harness()
        val hashes = (1..65).map { it.toString(16).padStart(40, '0') }
        coEvery { h.backend.checkCached(any(), any()) } coAnswers {
            val requested = secondArg<List<String>>()
            if (requested.size == 64) mapOf(hashes.first() to LocalDebridCachedItem("episode.mkv", 123L))
            else { delay(6_000); emptyMap() }
        }
        val rows = h.service.annotateCachedAvailability(listOf(group(hashes.map { stream(infoHash = it) })))
            .single().streams
        assertEquals(StreamDebridCacheState.CACHED, rows.first().debridCacheStatus?.state)
        assertEquals(StreamDebridCacheState.NOT_CACHED, rows[1].debridCacheStatus?.state)
        assertEquals(StreamDebridCacheState.UNKNOWN, rows.last().debridCacheStatus?.state)
    }

    @Test fun `bulk rate rejection remains unknown honors account cooldown and permits a replacement account`() = runTest {
        val h = harness()
        coEvery { h.backend.checkCached(any(), any()) } throws DirectDebridHttpException(429, 60L)
        val groups = listOf(group(listOf(stream())))
        val first = h.service.annotateCachedAvailability(groups).single().streams.single()
        assertEquals(StreamDebridCacheState.UNKNOWN, first.debridCacheStatus?.state)
        assertFalse(first.allNativeAccountsUnavailable(h.settings.value, h.scope(), h.now.get()))
        h.now.addAndGet(5_001L)
        h.service.annotateCachedAvailability(groups)
        coVerify(exactly = 1) { h.backend.checkCached(any(), any()) }
        h.settings.value = h.settings.value.copy(torboxApiKey = "new-synthetic-tb")
        h.credentialRevision.incrementAndGet()
        h.service.annotateCachedAvailability(groups)
        coVerify(exactly = 2) { h.backend.checkCached(any(), any()) }
        h.now.addAndGet(60_001L)
        h.service.annotateCachedAvailability(groups)
        coVerify(exactly = 3) { h.backend.checkCached(any(), any()) }
    }

    @Test fun `bulk authorization rejection stops remaining chunks without negative observations`() = runTest {
        val h = harness()
        coEvery { h.backend.checkCached(any(), any()) } throws DirectDebridHttpException(401, null)
        val hashes = (1..65).map { it.toString(16).padStart(40, '0') }
        val rows = h.service.annotateCachedAvailability(listOf(group(hashes.map { stream(infoHash = it) })))
            .single().streams
        assertTrue(rows.all { it.debridCacheStatus?.state == StreamDebridCacheState.UNKNOWN })
        coVerify(exactly = 1) { h.backend.checkCached(any(), any()) }
    }

    @Test fun `another collector rate limit stops later chunks while retaining its completed positive observations`() = runTest {
        val h = harness()
        val hashes = (1..65).map { it.toString(16).padStart(40, '0') }
        val rejectedHash = "f".repeat(40)
        val firstChunkEntered = CompletableDeferred<Unit>()
        val releaseFirstChunk = CompletableDeferred<Unit>()
        coEvery { h.backend.checkCached(any(), any()) } coAnswers {
            val requested = secondArg<List<String>>()
            when {
                requested.size == 64 -> {
                    firstChunkEntered.complete(Unit)
                    releaseFirstChunk.await()
                    mapOf(hashes.first() to LocalDebridCachedItem("completed-positive.mkv", 123L))
                }
                requested == listOf(rejectedHash) -> throw DirectDebridHttpException(429, 60L)
                else -> emptyMap()
            }
        }
        val ongoing = async {
            h.service.annotateCachedAvailability(listOf(group(hashes.map { stream(infoHash = it) })))
        }
        firstChunkEntered.await()
        val rejected = h.service.annotateCachedAvailability(listOf(group(listOf(stream(infoHash = rejectedHash)))))
        assertEquals(StreamDebridCacheState.UNKNOWN, rejected.single().streams.single().debridCacheStatus?.state)
        releaseFirstChunk.complete(Unit)
        val rows = ongoing.await().single().streams
        assertEquals(StreamDebridCacheState.CACHED, rows.first().debridCacheStatus?.state)
        assertEquals("completed-positive.mkv", rows.first().debridCacheStatus?.cachedName)
        assertEquals(StreamDebridCacheState.NOT_CACHED, rows[1].debridCacheStatus?.state)
        assertEquals(StreamDebridCacheState.UNKNOWN, rows.last().debridCacheStatus?.state)
        assertTrue(rows.all { it.nativeDebridAvailability.single().nativeScope == h.scope() })
        coVerify(exactly = 2) { h.backend.checkCached(any(), any()) }
        coVerify(exactly = 0) { h.backend.checkCached(any(), listOf(hashes.last())) }
    }

    @Test fun `late shorter retry after cannot shorten another collector longer account cooldown`() = runTest {
        val h = harness()
        val longEntered = CompletableDeferred<Unit>()
        val shortEntered = CompletableDeferred<Unit>()
        val releaseLong = CompletableDeferred<Unit>()
        val releaseShort = CompletableDeferred<Unit>()
        coEvery { h.backend.checkCached(any(), any()) } coAnswers {
            when (secondArg<List<String>>().single()) {
                HASH_A -> { longEntered.complete(Unit); releaseLong.await(); throw DirectDebridHttpException(429, 120L) }
                HASH_B -> { shortEntered.complete(Unit); releaseShort.await(); throw DirectDebridHttpException(429, 60L) }
                else -> null
            }
        }
        val longer = async { h.service.annotateCachedAvailability(listOf(group(listOf(stream(infoHash = HASH_A))))) }
        val shorter = async { h.service.annotateCachedAvailability(listOf(group(listOf(stream(infoHash = HASH_B))))) }
        longEntered.await(); shortEntered.await()
        releaseLong.complete(Unit)
        longer.await()
        releaseShort.complete(Unit)
        shorter.await()
        h.now.addAndGet(61_000L)
        val later = h.service.annotateCachedAvailability(listOf(group(listOf(stream(infoHash = "f".repeat(40))))))
        assertEquals(StreamDebridCacheState.UNKNOWN, later.single().streams.single().debridCacheStatus?.state)
        coVerify(exactly = 2) { h.backend.checkCached(any(), any()) }
    }

    private data class Harness(val service: LocalDebridAvailabilityService, val backend: LocalDebridService,
        val settings: MutableStateFlow<DebridSettings>, val profile: MutableStateFlow<Int>,
        val selectionRevision: AtomicLong, val credentialRevision: AtomicLong, val now: AtomicLong) {
        fun scope() = StreamAvailabilityScope(profile.value, selectionRevision.get(), credentialRevision.get())
    }

    private fun harness(initial: DebridSettings = DebridSettings(enabled = true, torboxApiKey = "synthetic-tb")): Harness {
        val settings = MutableStateFlow(initial)
        val profile = MutableStateFlow(1)
        val selection = AtomicLong(7L)
        val credentials = AtomicLong(11L)
        val now = AtomicLong(1_000L)
        val backend = mockk<LocalDebridService>()
        coEvery { backend.checkCached(any(), any()) } returns null
        val store = mockk<DebridSettingsDataStore>()
        every { store.settings } returns settings
        every { store.activeProfileId } returns profile
        every { store.activeProfileSelectionRevision } answers { selection.get() }
        every { store.activeCredentialRevision } answers { credentials.get() }
        coEvery { store.credentialForProfile(any(), any()) } coAnswers { settings.value.apiKeyFor(secondArg()) }
        return Harness(LocalDebridAvailabilityService(store, backend, now::get), backend, settings,
            profile, selection, credentials, now)
    }

    private fun group(streams: List<Stream>) = AddonStreams("Synthetic addon", null, streams)
    private fun stream(name: String = "Torrent", url: String? = null, infoHash: String? = HASH_A) = Stream(
        name, name, null, url, null, infoHash, null, null,
        StreamBehaviorHints(notWebReady = null, bingeGroup = null, countryWhitelist = null,
            proxyHeaders = null, filename = "$name.mkv"), "Synthetic addon", null)
    private companion object {
        const val HASH_A = "0123456789abcdef0123456789abcdef01234567"
        const val HASH_B = "fedcba9876543210fedcba9876543210fedcba98"
    }
}
