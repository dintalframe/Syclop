package com.nuvio.tv.ui.screens.stream

import android.content.ContextWrapper
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.NuvioApplication
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Real JSON and queued DataStore edits in an isolated directory; the current profile is read only. */
@RunWith(AndroidJUnit4::class)
class SourceRecoveryCacheAndroidTest {
    @Test fun queuedStaleWinnerCannotOverwriteTheLastVerifiedLink() = runBlocking {
        withTimeout(15_000L) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val app = context.applicationContext as NuvioApplication
            val profiles = field<ProfileManager>(app.startupSyncService, "profileManager")
            val profile = profiles.activeProfileId.value
            val root = File(context.cacheDir, "source-recovery-cache-${System.nanoTime()}").apply { mkdirs() }
            val isolatedContext = object : ContextWrapper(context) {
                override fun getFilesDir() = root
                override fun getApplicationContext() = this
            }
            val storeName = if (profile == 1) "stream_link_cache" else "stream_link_cache_p$profile"
            assertEquals(File(root, "datastore/$storeName.preferences_pb").canonicalFile,
                isolatedContext.preferencesDataStoreFile(storeName).canonicalFile)
            val factory = ProfileDataStoreFactory(isolatedContext)
            val cache = StreamLinkCacheDataStore(factory, profiles)
            try {
                cache.save("fixture", "https://fixture.invalid/original.mkv", "Original source", null,
                    filename = "original.mkv", bingeGroup = "original-group", profileId = profile)
                val store = factory.get(profile, "stream_link_cache")
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val blocker = async(Dispatchers.IO) {
                    store.updateData { entered.complete(Unit); release.await(); it }
                }
                entered.await()
                val owner = AtomicBoolean(true)
                val staleWrite = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    cache.save("fixture", "https://fixture.invalid/stale.mkv", "Stale source", null,
                        filename = "wrong-episode.mkv", bingeGroup = "stale-group", profileId = profile,
                        isCurrent = owner::get)
                }
                owner.set(false)
                release.complete(Unit)
                blocker.await()
                staleWrite.await()
                val retained = cache.getValid("fixture", 60_000L)
                assertNotNull(retained)
                assertEquals("https://fixture.invalid/original.mkv", retained?.url)
                assertEquals("original-group", retained?.bingeGroup)

                cache.save("fixture", "https://fixture.invalid/winner.mkv", "Winning source", null,
                    filename = "exact-episode.mkv", bingeGroup = "winning-group", profileId = profile,
                    isCurrent = { true })
                val winner = cache.getValid("fixture", 60_000L)
                assertEquals("https://fixture.invalid/winner.mkv", winner?.url)
                assertEquals("exact-episode.mkv", winner?.filename)
                assertEquals("winning-group", winner?.bingeGroup)
            } finally {
                // Close only this fixture's factory; production has no test-only cleanup entry point.
                field<Map<*, *>>(factory, "cache").values.filterNotNull().forEach {
                    field<Job>(it, "job").cancelAndJoin()
                }
                root.deleteRecursively()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as T
}
