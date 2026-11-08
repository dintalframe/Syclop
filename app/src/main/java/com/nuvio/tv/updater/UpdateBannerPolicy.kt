package com.nuvio.tv.updater

internal object UpdateBannerPolicy {
    const val DEFER_MS = 24L * 60 * 60 * 1000
    private const val CHECK_INTERVAL_MS = 60L * 60 * 1000

    fun canCheckAutomatically(nowMs: Long, lastCheckMs: Long): Boolean =
        lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= CHECK_INTERVAL_MS

    fun shouldShow(
        isRemoteNewer: Boolean,
        force: Boolean,
        bannerEnabled: Boolean,
        dismissedTag: String?,
        updateTag: String,
        deferredUntilMs: Long = Long.MAX_VALUE,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isRemoteNewer) return false
        if (force) return true
        return bannerEnabled && (dismissedTag != updateTag || nowMs >= deferredUntilMs)
    }
}
