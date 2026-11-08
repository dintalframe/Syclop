package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.debrid.DebridProvider

interface CloudLibraryProviderApi {
    val provider: DebridProvider

    suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>>

    suspend fun resolvePlayback(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ): CloudLibraryPlaybackResult

    /** Return null unless the fresh account inventory is complete. */
    suspend fun torrentIdsBeforeCreate(apiKey: String): Set<String>? = null

    /** Recheck that the provider item contains only this file before sending a delete. */
    suspend fun deleteCompletedFile(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile,
        isStillAuthorized: suspend () -> Boolean
    ): Boolean = false
}
