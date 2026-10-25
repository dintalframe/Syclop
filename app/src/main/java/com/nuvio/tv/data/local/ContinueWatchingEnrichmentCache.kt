package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Keep
data class CachedNextUpItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val episodeDescription: String? = null,
    val thumbnail: String?,
    val released: String? = null,
    val hasAired: Boolean = true,
    val airDateLabel: String? = null,
    val lastWatched: Long,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val sortTimestamp: Long,
    val releaseTimestamp: Long? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val contentLanguage: String? = null,
    val ratingVisible: Boolean = true
)

@Keep
data class CachedInProgressItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val progressPercent: Float?,
    val episodeThumbnail: String? = null,
    val episodeDescription: String? = null,
    val episodeImdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val contentLanguage: String? = null,
    val ratingVisible: Boolean = true
)

@Singleton
class ContinueWatchingEnrichmentCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "CwEnrichCache"
        private const val THROTTLE_MS = 1_000L
    }

    private val gson = Gson()
    private val mutex = Mutex()
    @Volatile private var lastNextUpWriteMs = 0L
    @Volatile private var lastInProgressWriteMs = 0L
    @Volatile private var lastNextUpHash = 0
    @Volatile private var lastInProgressHash = 0

    /** Incremented when cache is cleared; observers can collect to trigger refresh. */
    private val _cacheCleared = kotlinx.coroutines.flow.MutableStateFlow(0)
    val cacheCleared: kotlinx.coroutines.flow.StateFlow<Int> = _cacheCleared

    /** Incremented on every successful snapshot write; channel sync observes this. */
    private val _snapshotVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val snapshotVersion: kotlinx.coroutines.flow.StateFlow<Int> = _snapshotVersion

    // --- Next Up snapshot cache ---

    private fun nextUpFile(profileId: Int): File {
        val dir = File(context.filesDir, "cw_enrichment")
        dir.mkdirs()
        return File(dir, "nextup_${profileId}.json")
    }

    suspend fun getNextUpSnapshot(): List<CachedNextUpItem> {
        val profileId = profileManager.activeProfileId.value
        val selection = profileManager.activeProfileSelectionRevision
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!currentProfile(profileId, selection)) return@withContext emptyList()
                val items = try {
                    val file = nextUpFile(profileId)
                    if (!file.exists() || file.length() > ContinueWatchingCacheCodec.MAX_BYTES) return@withContext emptyList()
                    ContinueWatchingCacheCodec.nextUp(file.readText())
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Log.w(TAG, "Failed to read next-up cache")
                    emptyList()
                }
                if (currentProfile(profileId, selection)) items else emptyList()
            }
        }
    }

    /**
     * @param force bypass throttle and content-change check (use at end of pipeline or for clears)
     */
    suspend fun saveNextUpSnapshot(items: List<CachedNextUpItem>, force: Boolean = false) {
        val profileId = profileManager.activeProfileId.value
        val selection = profileManager.activeProfileSelectionRevision
        withContext(Dispatchers.IO) {
            val contentHash = items.hashCode()
            if (!force) {
                if (contentHash == lastNextUpHash) return@withContext
                val now = System.currentTimeMillis()
                if (now - lastNextUpWriteMs < THROTTLE_MS) return@withContext
            }
            mutex.withLock {
                if (!currentProfile(profileId, selection)) return@withContext
                try {
                    val file = nextUpFile(profileId)
                    atomicWrite(file, gson.toJson(items))
                    lastNextUpWriteMs = System.currentTimeMillis()
                    lastNextUpHash = contentHash
                    _snapshotVersion.value++
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Log.w(TAG, "Failed to write next-up cache")
                }
            }
        }
    }

    // --- In-progress snapshot cache ---

    private fun inProgressFile(profileId: Int): File {
        val dir = File(context.filesDir, "cw_enrichment")
        dir.mkdirs()
        return File(dir, "inprogress_${profileId}.json")
    }

    suspend fun getInProgressSnapshot(): List<CachedInProgressItem> {
        val profileId = profileManager.activeProfileId.value
        val selection = profileManager.activeProfileSelectionRevision
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!currentProfile(profileId, selection)) return@withContext emptyList()
                val items = try {
                    val file = inProgressFile(profileId)
                    if (!file.exists() || file.length() > ContinueWatchingCacheCodec.MAX_BYTES) return@withContext emptyList()
                    ContinueWatchingCacheCodec.inProgress(file.readText())
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Log.w(TAG, "Failed to read in-progress cache")
                    emptyList()
                }
                if (currentProfile(profileId, selection)) items else emptyList()
            }
        }
    }

    /**
     * @param force bypass throttle and content-change check (use at end of pipeline or for clears)
     */
    suspend fun saveInProgressSnapshot(items: List<CachedInProgressItem>, force: Boolean = false) {
        val profileId = profileManager.activeProfileId.value
        val selection = profileManager.activeProfileSelectionRevision
        withContext(Dispatchers.IO) {
            val contentHash = items.hashCode()
            if (!force) {
                if (contentHash == lastInProgressHash) return@withContext
                val now = System.currentTimeMillis()
                if (now - lastInProgressWriteMs < THROTTLE_MS) return@withContext
            }
            mutex.withLock {
                if (!currentProfile(profileId, selection)) return@withContext
                try {
                    val file = inProgressFile(profileId)
                    atomicWrite(file, gson.toJson(items))
                    lastInProgressWriteMs = System.currentTimeMillis()
                    lastInProgressHash = contentHash
                    _snapshotVersion.value++
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Log.w(TAG, "Failed to write in-progress cache")
                }
            }
        }
    }

    /**
     * Deletes all CW enrichment cache files for the active profile.
     */
    suspend fun clearAll() {
        val profileId = profileManager.activeProfileId.value
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    nextUpFile(profileId).delete()
                    inProgressFile(profileId).delete()
                    lastNextUpHash = 0
                    lastInProgressHash = 0
                    Log.d(TAG, "Cleared CW enrichment cache")
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Log.w(TAG, "Failed to clear CW enrichment cache")
                }
            }
            _cacheCleared.value++
        }
    }

    private fun currentProfile(profileId: Int, selection: Long): Boolean =
        profileManager.activeProfileId.value == profileId && profileManager.activeProfileSelectionRevision == selection

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // renameTo can fail on some filesystems; fall back to copy+delete
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

}
