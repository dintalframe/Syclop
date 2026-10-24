package com.nuvio.tv.core.player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Only installed, exported HTTPS/video handlers are offered; no broad installed-app query. */
object ExternalPlayerCatalog {
    const val UNAVAILABLE_MESSAGE = ExternalPlayerChoiceRules.UNAVAILABLE_MESSAGE
    const val CHOOSE_MESSAGE = ExternalPlayerChoiceRules.CHOOSE_MESSAGE
    private fun probe() = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.invalid/syclop-video"), "video/*")
        .addCategory(Intent.CATEGORY_DEFAULT)

    fun installed(context: Context): List<InstalledExternalPlayer> {
        val manager = context.packageManager
        val found = runCatching { manager.queryIntentActivities(probe(), PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
        return ExternalPlayerChoiceRules.ordered(found.mapNotNull { resolved ->
            val info = resolved.activityInfo ?: return@mapNotNull null
            if (!info.enabled || !info.exported || !info.applicationInfo.enabled) return@mapNotNull null
            val label = runCatching { resolved.loadLabel(manager).toString() }.getOrDefault(info.packageName)
            InstalledExternalPlayer(info.packageName, label)
        }, context.packageName)
    }

    fun requireAvailable(context: Context, packageName: String?) { resolvePackage(context, packageName) }

    fun resolvePackage(context: Context, packageName: String?): String {
        val available = installed(context)
        if (packageName != null) return ExternalPlayerChoiceRules.resolvePackage(available, packageName, null)
        // Resolve a real default before attaching the signed file URL. Never send it to an OS
        // chooser that could wait across a viewer/account change after the final grant check.
        val chosen = runCatching { context.packageManager.resolveActivity(probe(), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo }
            .getOrNull()?.takeIf { it.enabled && it.exported && it.applicationInfo.enabled }?.packageName
        return ExternalPlayerChoiceRules.resolvePackage(available, null, chosen)
    }
}
