package com.nuvio.tv.core.initialization

import java.security.MessageDigest

const val INITIALIZATION_SCHEMA = 1

data class InitializationMarkerKey(val profileId: Int, val provider: InitializationProvider, val accountHash: String) {
    init { require(profileId in 1..6 && accountHash.matches(Regex("[a-f0-9]{64}"))) }
    val stored: String get() = "v$INITIALIZATION_SCHEMA:${provider.name}:$accountHash"
    companion object {
        fun fromAccount(profile: Int, provider: InitializationProvider, identity: String): InitializationMarkerKey? {
            val value = identity.trim().takeIf { it.isNotEmpty() && it.length <= 512 && it.none(Char::isISOControl) } ?: return null
            val bytes = "Syclop initialization v$INITIALIZATION_SCHEMA\u0000${provider.name}\u0000$value".toByteArray(Charsets.UTF_8)
            return InitializationMarkerKey(profile, provider, MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) })
        }
    }
}

enum class InitializationStageStatus { WAITING, READING, COMPLETE, FAILED }
enum class InitializationStep(val label: String) {
    HISTORY("Reading watched history and resume positions"), LIBRARY("Reading lists and library"),
    ACCOUNT("Checking connected accounts")
}
data class InitializationStage(val provider: InitializationProvider, val status: InitializationStageStatus,
    val count: Int? = null, val truncated: Boolean = false, val failure: InitializationFailure? = null,
    val step: InitializationStep? = null)
enum class InitializationPhase { CHECKING, IMPORTING, NEEDS_ATTENTION, READY, CONTINUED }
data class StartupInitializationState(
    val profileId: Int = -1, val selectionRevision: Long = -1,
    val phase: InitializationPhase = InitializationPhase.CHECKING,
    val stages: List<InitializationStage> = emptyList()
) {
    val blocksHome: Boolean get() = phase !in setOf(InitializationPhase.READY, InitializationPhase.CONTINUED)
}

interface InitializationMarkerStore {
    suspend fun contains(key: InitializationMarkerKey): Boolean
    suspend fun invalidateProvider(profileId: Int, provider: InitializationProvider, current: () -> Boolean)
    /** Check ownership again inside the durable edit, never mark after a stale/cancelled read. */
    suspend fun complete(key: InitializationMarkerKey, current: () -> Boolean): Boolean
}

interface InitializationSession {
    val marker: InitializationMarkerKey
    val scope: InitializationReadScope
    suspend fun read(progress: (InitializationStep) -> Unit): InitializationReadResult
}

interface InitializationParticipant {
    val provider: InitializationProvider
    val accountChanges: kotlinx.coroutines.flow.Flow<String>
    /** Null means disconnected; an authenticated account without stable identity fails capture. */
    suspend fun capture(profileId: Int, selectionRevision: Long): InitializationSession?
}
