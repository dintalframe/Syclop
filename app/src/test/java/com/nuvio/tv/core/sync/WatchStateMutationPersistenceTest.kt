package com.nuvio.tv.core.sync

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import com.nuvio.tv.domain.model.mutationKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WatchStateMutationPersistenceTest {
    private val gson = Gson()
    private val progressKey = stringSetPreferencesKey("progress_upserts")
    private val watchedKey = stringSetPreferencesKey("watched_upserts")
    private val watchedDeletesKey = stringSetPreferencesKey("watched_deletes")

    @Test fun `canonical pre-schema Gson wrappers remain readable`() = runTest {
        val progress = progress("movie")
        val watched = watched("show")
        val h = harness(setOf(progressEnvelope(progress, aliases = false)), setOf(watchedEnvelope(watched, false)))
        assertEquals(mapOf("movie" to progress), h.store.pendingProgressUpserts(1))
        assertEquals(mapOf(watched.mutationKey() to watched), h.store.pendingWatchedUpserts(1))
    }

    @Test fun `verified release a b wrappers retain complete kept domain payloads`() = runTest {
        val progress = progress("episode").copy(season = 1, episode = 2, episodeTitle = "Episode",
            addonBaseUrl = "https://example.invalid/manifest.json", progressPercent = 25f,
            traktPlaybackId = 15L, excludedNextUpSeasons = setOf(3))
        val watched = watched("show")
        val h = harness(setOf(progressEnvelope(progress, true)), setOf(watchedEnvelope(watched, true)))
        assertEquals(mapOf("episode_s1e2" to progress), h.store.pendingProgressUpserts(1))
        assertEquals(mapOf(watched.mutationKey() to watched), h.store.pendingWatchedUpserts(1))
    }

    @Test fun `queue edit migrates legacy entries and survives recreation without null constructors`() = runTest {
        val first = progress("first")
        val second = progress("second")
        val firstWatched = watched("first-show")
        val secondWatched = watched("second-show")
        val h = harness(setOf(progressEnvelope(first, true)), setOf(watchedEnvelope(firstWatched, true)))
        h.store.queueProgressUpserts(mapOf("second" to second), 1)
        h.store.queueWatchedUpserts(listOf(secondWatched), 1)
        val recreated = WatchStateMutationStore(h.factory)
        assertEquals(mapOf("first" to first, "second" to second), recreated.pendingProgressUpserts(1))
        assertEquals(setOf(firstWatched, secondWatched), recreated.pendingWatchedUpserts(1).values.toSet())
        (h.memory.value[progressKey].orEmpty() + h.memory.value[watchedKey].orEmpty()).forEach { raw ->
            val objectValue = JsonParser.parseString(raw).asJsonObject
            assertEquals(1, objectValue["schemaVersion"].asInt)
            assertTrue(objectValue.has("key"))
            assertFalse(objectValue.has("a"))
            assertFalse(objectValue.has("b"))
        }
    }

    @Test fun `mixed corrupt wrappers cannot discard valid queued operations or poison later edit`() = runTest {
        val first = progress("first")
        val second = progress("second")
        val valid = progressEnvelope(first, true)
        val invalid = setOf("{}", "null", "[]", "not JSON", "{\"a\":null,\"b\":{}}",
            "{\"key\":\"bad\",\"progress\":null}")
        val h = harness(invalid + valid)
        assertEquals(mapOf("first" to first), h.store.pendingProgressUpserts(1))
        h.store.queueProgressUpserts(mapOf("second" to second), 1)
        assertEquals(mapOf("first" to first, "second" to second), WatchStateMutationStore(h.factory).pendingProgressUpserts(1))
    }

    @Test fun `required semantic fields and exact numeric types are checked before Gson objects escape`() = runTest {
        val valid = progressEnvelope(progress("valid"), true)
        val mutations: List<(JsonObject) -> Unit> = listOf(
            { it.add("contentId", null) }, { it.remove("videoId") }, { it.addProperty("name", 42) },
            { it.addProperty("position", -1) }, { it.addProperty("duration", "1000") },
            { it.addProperty("lastWatched", 1.5) }, { it.addProperty("season", 2147483648L) },
            { it.addProperty("progressPercent", 101) }, { it.addProperty("excludedNextUpSeasons", "bad") }
        )
        val invalid = mutations.map { mutate ->
            JsonParser.parseString(valid).asJsonObject.apply { mutate(getAsJsonObject("b")) }.toString()
        }.toSet()
        val h = harness(invalid + valid)
        assertEquals(mapOf("valid" to progress("valid")), h.store.pendingProgressUpserts(1))
    }

    @Test fun `known aliases never accept ambiguous mixed envelopes or unknown schemas`() = runTest {
        val valid = progressEnvelope(progress("valid"), false)
        val mixed = JsonParser.parseString(valid).asJsonObject.apply { addProperty("a", "other") }.toString()
        val future = JsonParser.parseString(valid).asJsonObject.apply { addProperty("schemaVersion", 2) }.toString()
        val h = harness(setOf(mixed, future, valid))
        assertEquals(mapOf("valid" to progress("valid")), h.store.pendingProgressUpserts(1))
    }

    @Test fun `wrapper identity must match payload instead of rebinding an operation`() = runTest {
        val progress = JsonParser.parseString(progressEnvelope(progress("movie"), true)).asJsonObject.apply {
            addProperty("a", "another-movie")
        }
        val watched = JsonParser.parseString(watchedEnvelope(watched("show"), true)).asJsonObject.apply {
            getAsJsonObject("a").addProperty("episode", 9)
        }
        val h = harness(setOf(progress.toString()), setOf(watched.toString()))
        assertTrue(h.store.pendingProgressUpserts(1).isEmpty())
        assertTrue(h.store.pendingWatchedUpserts(1).isEmpty())
    }

    @Test fun `missing optional old progress defaults normalize without nullable Kotlin fields`() = runTest {
        val progress = progress("movie")
        val raw = JsonParser.parseString(progressEnvelope(progress, true)).asJsonObject.apply {
            getAsJsonObject("b").remove("source")
            getAsJsonObject("b").remove("excludedNextUpSeasons")
        }.toString()
        assertEquals(mapOf("movie" to progress), harness(setOf(raw)).store.pendingProgressUpserts(1))
    }

    @Test fun `legacy acknowledgement removes only matching operation and retains a newer one`() = runTest {
        val first = progress("first")
        val second = progress("second")
        val watched = watched("show")
        val h = harness(setOf(progressEnvelope(first, true), progressEnvelope(second, true)), setOf(watchedEnvelope(watched, true)))
        h.store.acknowledgeProgressUpserts(mapOf("first" to first), 1)
        h.store.acknowledgeWatchedUpserts(listOf(watched.copy(watchedAt = 1L)), 1)
        assertEquals(mapOf("second" to second), WatchStateMutationStore(h.factory).pendingProgressUpserts(1))
        assertEquals(mapOf(watched.mutationKey() to watched), WatchStateMutationStore(h.factory).pendingWatchedUpserts(1))
    }

    @Test fun `invalid watched delete keys never enter typed sets and valid deletes survive edit`() = runTest {
        val valid = WatchedMutationKey("show", 1, 2)
        val h = harness(deletes = setOf(gson.toJson(valid), "{}", "null", "{\"contentId\":null}",
            "{\"contentId\":\"bad\",\"season\":2147483648}"))
        assertEquals(setOf(valid), h.store.pendingWatchedDeletes(1))
        h.store.queueWatchedDeletes(listOf(WatchedMutationKey("other", null, null)), 1)
        assertEquals(setOf(valid, WatchedMutationKey("other", null, null)), WatchStateMutationStore(h.factory).pendingWatchedDeletes(1))
    }

    private fun harness(progress: Set<String> = emptySet(), watched: Set<String> = emptySet(), deletes: Set<String> = emptySet()): Harness {
        val memory = TestPreferencesStore(preferencesOf(progressKey to progress, watchedKey to watched, watchedDeletesKey to deletes))
        val factory = mockk<ProfileDataStoreFactory>()
        // Any access to credential/progress stores or another profile fails this fixture.
        every { factory.get(1, "watch_state_pending_mutations") } returns memory
        return Harness(memory, factory, WatchStateMutationStore(factory))
    }

    private fun progressEnvelope(progress: WatchProgress, aliases: Boolean): String = JsonObject().apply {
        val key = if (progress.season != null && progress.episode != null) "${progress.contentId}_s${progress.season}e${progress.episode}" else progress.contentId
        addProperty(if (aliases) "a" else "key", key)
        add(if (aliases) "b" else "progress", gson.toJsonTree(progress))
    }.toString()

    private fun watchedEnvelope(item: WatchedItem, aliases: Boolean): String = JsonObject().apply {
        add(if (aliases) "a" else "key", gson.toJsonTree(item.mutationKey()))
        add(if (aliases) "b" else "item", gson.toJsonTree(item))
    }.toString()

    private fun progress(id: String) = WatchProgress(id, "movie", id, null, null, null, id,
        null, null, null, 1000L, 10000L, 2000L)
    private fun watched(id: String) = WatchedItem(id, "series", id, 1, 2, 2000L)
    private data class Harness(val memory: TestPreferencesStore, val factory: ProfileDataStoreFactory, val store: WatchStateMutationStore)
}
