package com.nuvio.tv.core.trakt

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** Shared with the existing playback auth path; refresh tokens must not race. */
@Singleton
class TraktTokenRefreshCoordinator @Inject constructor() {
    internal val mutex = Mutex()
}
