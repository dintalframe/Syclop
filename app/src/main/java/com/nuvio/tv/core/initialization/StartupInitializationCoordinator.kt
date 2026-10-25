package com.nuvio.tv.core.initialization

import com.nuvio.tv.core.profile.ProfileManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Singleton
class StartupInitializationCoordinator @Inject constructor(
    private val profiles: ProfileManager,
    preferences: InitializationPreferences,
    trakt: TraktInitializationParticipant,
    simkl: SimklInitializationParticipant,
    mal: MalInitializationParticipant
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = FirstUseInitialization(preferences)
    private val participants = listOf(trakt, simkl, mal)
    private var work: Job? = null
    private var homeEligible = false
    private var entryDecisionMade = false
    private var owner: Pair<Int, Long>? = null
    @Volatile private var accountVersion = 0L
    private var checkedVersion = -1L
    private var runId = 0L
    val state = engine.state

    init {
        scope.launch {
            combine(participants.map { it.accountChanges }) { values -> values.toList() }
                .distinctUntilChanged().collect {
                    synchronized(this@StartupInitializationCoordinator) {
                        accountVersion++
                        if (homeEligible && state.value.phase == InitializationPhase.CONTINUED) {
                            checkedVersion = accountVersion
                        } else if (homeEligible) {
                            runId++
                            work?.cancel()
                            work = null
                            beginLocked(force = true)
                        }
                    }
                }
        }
    }

    /** Only Home is gated. OAuth callbacks, setup, profiles, player and deep links continue normally. */
    @Synchronized fun onHomeEntry(profile: Int, selection: Long) {
        homeEligible = true
        entryDecisionMade = true
        val next = profile to selection
        if (owner != next) {
            work?.cancel()
            owner = next
            engine.reset(profile, selection)
            checkedVersion = -1L
        }
        beginLocked(force = false)
    }

    @Synchronized fun onLeaveHome() {
        homeEligible = false
        entryDecisionMade = true
        runId++
        work?.cancel()
        work = null
    }

    @Synchronized fun retry() { if (homeEligible) beginLocked(force = true) }

    @Synchronized fun continueWithSavedData() {
        runId++
        work?.cancel()
        work = null
        checkedVersion = accountVersion
        engine.continueWithSavedData()
    }

    val shouldDeferForegroundRefresh: Boolean get() = synchronized(this) {
        !entryDecisionMade || (homeEligible && (work?.isActive == true || checkedVersion != accountVersion))
    }

    private fun beginLocked(force: Boolean) {
        if (!homeEligible || work?.isActive == true) return
        val captured = owner ?: return
        if (!force && checkedVersion == accountVersion && state.value.phase in
            setOf(InitializationPhase.READY, InitializationPhase.CONTINUED, InitializationPhase.NEEDS_ATTENTION)) return
        engine.reset(captured.first, captured.second)
        val version = accountVersion
        val ownRun = ++runId
        work = scope.launch {
            try {
                engine.run(captured.first, captured.second, participants) {
                    profiles.activeProfileId.value == captured.first &&
                        profiles.activeProfileSelectionRevision == captured.second && accountVersion == version
                }
                synchronized(this@StartupInitializationCoordinator) {
                    if (owner == captured && accountVersion == version) checkedVersion = version
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: InitializationReadException) {
                // The next Home/account/profile event captures a fresh owner; stale work never marks completion.
            } finally {
                synchronized(this@StartupInitializationCoordinator) {
                    if (runId == ownRun) {
                        work = null
                        if (homeEligible && accountVersion != version) beginLocked(force = true)
                    }
                }
            }
        }
    }
}
