package com.nuvio.tv.updater

internal object InstalledUpdateNotesPolicy {
    fun shouldAcknowledge(ambientShowing: Boolean): Boolean = !ambientShowing

    fun canPresent(
        homeReady: Boolean,
        hasPendingDeepLink: Boolean,
        hasPendingLaunch: Boolean,
        hasAddonConfirmation: Boolean,
        isInstallingAddon: Boolean
    ): Boolean = homeReady && !hasPendingDeepLink && !hasPendingLaunch &&
        !hasAddonConfirmation && !isInstallingAddon

    fun shouldShow(
        installedVersion: String,
        bundledVersion: String,
        installedVersionCode: Int,
        acknowledgedVersionCode: Int?,
        firstInstallTime: Long,
        lastUpdateTime: Long
    ): Boolean = installedVersion.isNotBlank() && installedVersion == bundledVersion &&
        installedVersionCode > 0 && firstInstallTime > 0 && lastUpdateTime > firstInstallTime &&
        (acknowledgedVersionCode == null || installedVersionCode > acknowledgedVersionCode)
}
