package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.cloud.CloudLibraryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NormalDebridCleanupProvenanceTest {
    private val provider = DebridProviders.RealDebrid
    private val file = CloudLibraryFile("file-1", "sample.mkv")

    @Test
    fun newlyCreatedSingleFileCanBeBoundToPlayback() {
        val item = newSingleFileCloudTorrent(setOf("existing-item"), provider, "new-item", file)

        assertEquals("new-item", item?.id)
        assertEquals(provider.id, item?.providerId)
        assertEquals(file, item?.files?.singleOrNull())
    }

    @Test
    fun unknownInventoryOrExistingItemCannotBeBoundToPlayback() {
        assertNull(newSingleFileCloudTorrent(null, provider, "old-item", file))
        assertNull(newSingleFileCloudTorrent(setOf("old-item"), provider, "old-item", file))
    }

    @Test
    fun incompleteItemOrFileIdentityCannotBeBoundToPlayback() {
        assertNull(newSingleFileCloudTorrent(emptySet(), provider, "", file))
        assertNull(newSingleFileCloudTorrent(emptySet(), provider, "new-item", file.copy(id = null)))
        assertNull(newSingleFileCloudTorrent(emptySet(), provider, "new-item", file.copy(playable = false)))
    }
}
