package com.nuvio.tv.core.initialization

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/** Sequential, read-only imports. The owner controls one job; repositories deduplicate actual reads. */
class FirstUseInitialization(private val markers: InitializationMarkerStore, private val timeoutMs: Long = 45_000L) {
    private val mutableState = MutableStateFlow(StartupInitializationState())
    val state = mutableState.asStateFlow()
    @Volatile private var generation = 0L
    private val hydrated = mutableSetOf<String>()
    private fun loadedKey(marker: InitializationMarkerKey) = "${marker.profileId}:${marker.stored}"

    @Synchronized fun reset(profile: Int, selection: Long) {
        if (mutableState.value.profileId != profile || mutableState.value.selectionRevision != selection) hydrated.clear()
        generation++
        mutableState.value = StartupInitializationState(profile, selection)
    }

    /** Forget RAM warm-up state without deleting any durable account/import completion markers. */
    @Synchronized fun resetForAppRestart() {
        generation++
        hydrated.clear()
        mutableState.value = StartupInitializationState()
    }

    @Synchronized fun continueWithSavedData() {
        generation++
        mutableState.value = mutableState.value.copy(phase = InitializationPhase.CONTINUED)
    }

    suspend fun run(profile: Int, selection: Long, participants: List<InitializationParticipant>,
        current: () -> Boolean) {
        val ownGeneration = generation
        fun owns() = generation == ownGeneration && current()
        suspend fun checkpoint() {
            currentCoroutineContext().ensureActive()
            if (!owns()) throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
        }
        val stages = linkedMapOf<InitializationProvider, InitializationStage>()
        val cached = mutableListOf<InitializationSession>()
        fun publish(phase: InitializationPhase) {
            synchronized(this@FirstUseInitialization) {
                if (owns()) mutableState.value = StartupInitializationState(profile, selection, phase, stages.values.toList())
            }
        }
        for (participant in participants) {
            checkpoint()
            val session = try { withTimeout(timeoutMs) { participant.capture(profile, selection) } }
            catch (_: TimeoutCancellationException) {
                checkpoint()
                stages[participant.provider] = InitializationStage(participant.provider, InitializationStageStatus.FAILED,
                    failure = InitializationFailure.TIMEOUT)
                publish(InitializationPhase.IMPORTING)
                continue
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                stages[participant.provider] = InitializationStage(participant.provider, InitializationStageStatus.FAILED,
                    failure = InitializationFailure.UNAVAILABLE)
                publish(InitializationPhase.IMPORTING)
                continue
            } ?: continue
            checkpoint()
            if (!session.scope.isCurrent()) throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
            val alreadyComplete = try { markers.contains(session.marker) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                stages[participant.provider] = InitializationStage(participant.provider, InitializationStageStatus.FAILED,
                    failure = InitializationFailure.UNAVAILABLE)
                publish(InitializationPhase.IMPORTING)
                continue
            }
            if (alreadyComplete) {
                synchronized(this) { if (loadedKey(session.marker) !in hydrated) cached += session }
                stages[participant.provider] = InitializationStage(participant.provider, InitializationStageStatus.COMPLETE)
                continue
            }
            stages[participant.provider] = InitializationStage(participant.provider, InitializationStageStatus.READING)
            publish(InitializationPhase.IMPORTING)
            try {
                val result = withTimeout(timeoutMs) {
                    session.read { step ->
                        if (owns() && session.scope.isCurrent()) {
                            stages[participant.provider] = InitializationStage(participant.provider,
                                InitializationStageStatus.READING, step = step)
                            publish(InitializationPhase.IMPORTING)
                        }
                    }
                }
                checkpoint()
                session.scope.checkpoint()
                if (!result.complete) throw InitializationReadException(InitializationFailure.INCOMPLETE)
                if (!markers.complete(session.marker) { owns() && session.scope.isCurrent() })
                    throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
                checkpoint()
                synchronized(this) { if (owns()) hydrated += loadedKey(session.marker) }
                stages[participant.provider] = InitializationStage(participant.provider, InitializationStageStatus.COMPLETE,
                    count = result.count?.coerceAtLeast(0), truncated = result.truncated)
            } catch (_: TimeoutCancellationException) {
                checkpoint()
                stages[participant.provider] = InitializationStage(participant.provider, InitializationStageStatus.FAILED,
                    failure = InitializationFailure.TIMEOUT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: InitializationReadException) {
                if (failure.reason == InitializationFailure.ACCOUNT_CHANGED) throw failure
                stages[participant.provider] = InitializationStage(participant.provider, InitializationStageStatus.FAILED,
                    failure = failure.reason)
            } catch (_: Exception) {
                checkpoint()
                stages[participant.provider] = InitializationStage(participant.provider, InitializationStageStatus.FAILED,
                    failure = InitializationFailure.NETWORK)
            }
            publish(InitializationPhase.IMPORTING)
        }
        checkpoint()
        publish(if (stages.values.any { it.status == InitializationStageStatus.FAILED })
            InitializationPhase.NEEDS_ATTENTION else InitializationPhase.READY)
        // Home is already unblocked. RAM-only provider lists still warm on a later cold launch;
        // this does not claim that a durable marker contains a complete offline history backup.
        supervisorScope {
            cached.map { session -> async {
                try {
                    val result = withTimeout(timeoutMs) { session.scope.checkpoint(); session.read { } }
                    if (result.complete) synchronized(this@FirstUseInitialization) {
                        if (owns() && session.scope.isCurrent()) hydrated += loadedKey(session.marker)
                    }
                } catch (_: TimeoutCancellationException) {
                    // A saved-data launch remains usable; ordinary provider refresh can retry later.
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* No marker or saved history is changed on a warm-up failure. */ }
            } }.awaitAll()
        }
    }
}
