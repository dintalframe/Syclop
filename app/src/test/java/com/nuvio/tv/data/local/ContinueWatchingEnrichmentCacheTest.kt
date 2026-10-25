package com.nuvio.tv.data.local

import android.content.Context
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContinueWatchingEnrichmentCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val profile = MutableStateFlow(1)
    private val profiles = mockk<ProfileManager>().apply {
        every { activeProfileId } returns profile
        every { activeProfileSelectionRevision } returns 10L
    }
    private fun cache(): ContinueWatchingEnrichmentCache {
        val context = mockk<Context>()
        every { context.filesDir } returns temporary.root
        return ContinueWatchingEnrichmentCache(context, profiles)
    }
    private fun value() = CachedInProgressItem("tt42", "movie", "Fixture", null, null, null,
        "tt42", null, null, null, 10L, 100L, 0L, null)
    private fun snapshot() = File(temporary.root, "cw_enrichment/inprogress_1.json").apply { parentFile!!.mkdirs() }

    @Test fun `disk cache filters a malformed row without touching the original file`() = runTest {
        val file = snapshot()
        val original = "[{\"a\":\"legacy\"},${Gson().toJson(value())}]"
        file.writeText(original)
        assertEquals(listOf(value()), cache().getInProgressSnapshot())
        assertEquals(original, file.readText())
    }
    @Test fun `away and back before IO cannot return old viewer cache`() = runTest {
        snapshot().writeText(Gson().toJson(listOf(value())))
        every { profiles.activeProfileSelectionRevision } returnsMany listOf(10L, 11L)
        assertTrue(cache().getInProgressSnapshot().isEmpty())
    }
    @Test fun `away and back before queued write does not overwrite cached viewer data`() = runTest {
        val file = snapshot().apply { writeText("[]") }
        every { profiles.activeProfileSelectionRevision } returnsMany listOf(10L, 11L)
        cache().saveInProgressSnapshot(listOf(value()), force = true)
        assertEquals("[]", file.readText())
    }
    @Test fun `valid live snapshot saves canonical identity and restores`() = runTest {
        val cache = cache()
        cache.saveInProgressSnapshot(listOf(value()), force = true)
        assertEquals(listOf(value()), cache.getInProgressSnapshot())
        assertTrue(snapshot().readText().contains("\"contentId\""))
    }
}
