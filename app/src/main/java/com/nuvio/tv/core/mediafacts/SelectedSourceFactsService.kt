package com.nuvio.tv.core.mediafacts

import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.VerifiedSourceMediaFacts
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

internal data class SelectedSourceProbeResult(val tracks: VerifiedSourceMediaFacts?, val fileBytes: Long?)

/** Only invoked for a selected, already-native-resolved file; never from result-list ranking. */
@Singleton
class SelectedSourceFactsService @Inject constructor(private val settings: DebridSettingsDataStore) {
    private val worker = SelectedSourceProbeWorker()

    internal suspend fun inspect(url: String, expectedProfileId: Int, sourceSelection: StateFlow<Long>, sourceTicket: Long): SelectedSourceProbeResult? = coroutineScope {
        val selection = settings.activeProfileSelectionRevision
        val credentials = settings.activeCredentialRevision
        fun current() = sourceSelection.value == sourceTicket && credentials >= 0L && settings.activeProfileId.value == expectedProfileId &&
            settings.activeProfileSelectionRevision == selection && settings.activeCredentialRevision == credentials &&
            ConnectedServicePlaybackGrants.registry.permits(url)
        if (!current()) return@coroutineScope null
        val reader = SelectedFileRangeReader(url, ::current)
        val task = async {
            worker.run(4_500L, reader::close) {
                var extractor: MediaExtractor? = null
                try {
                    reader.prime()
                    if (!current()) return@run null
                    val active = MediaExtractor()
                    extractor = active
                    active.setDataSource(object : MediaDataSource() {
                        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) = reader.readAt(position, buffer, offset, size)
                        override fun getSize(): Long = reader.knownSize ?: -1L
                        override fun close() = reader.close()
                    })
                    val formats = (0 until minOf(active.trackCount, 32)).map { index ->
                        safeFormat(active.getTrackFormat(index))
                    }
                    if (current()) SelectedSourceProbeResult(SourceMediaFormatFacts.from(formats), reader.knownSize) else null
                } catch (_: Exception) {
                    if (current()) SelectedSourceProbeResult(null, reader.knownSize) else null
                } finally {
                    reader.close()
                    runCatching { extractor?.release() }
                }
            }
        }
        val invalidations = launch {
            ConnectedServicePlaybackGrants.registry.invalidations.collect {
                if (!current()) { reader.close(); task.cancel() }
            }
        }
        val sourceChanges = launch {
            sourceSelection.collect { if (!current()) { reader.close(); task.cancel() } }
        }
        try {
            val result = task.await()
            if (current()) result else null
        } finally {
            invalidations.cancel()
            sourceChanges.cancel()
            reader.close()
        }
    }

    private fun safeFormat(format: MediaFormat): Map<String, Any> = buildMap {
        for (key in listOf("mime", "codecs-string")) {
            runCatching { format.getString(key) }.getOrNull()?.takeIf { it.length <= 256 }?.let { put(key, it) }
        }
        for (key in listOf("width", "height", "channel-count", "channel-mask", "color-transfer", "color-standard")) {
            runCatching { format.getInteger(key) }.getOrNull()?.let { put(key, it) }
        }
        runCatching { format.getLong("durationUs") }.getOrNull()?.let { put("durationUs", it) }
        runCatching { format.getByteBuffer("hdr-static-info") }.getOrNull()?.duplicate()?.let { buffer ->
            if (buffer.remaining() in 1..128) put("hdr-static-info", ByteArray(buffer.remaining()).also { buffer.get(it) })
        }
    }
}
