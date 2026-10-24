package com.nuvio.tv.core.player

import java.util.Locale

data class InstalledExternalPlayer(val packageName: String, val label: String)

/** Pure selection policy. A saved app choice never changes the internal-player default. */
object ExternalPlayerChoiceRules {
    const val UNAVAILABLE_MESSAGE = "The selected video player is unavailable. Choose an installed app in Playback settings."
    const val CHOOSE_MESSAGE = "Choose an installed video player in Playback settings before opening this file."
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    fun validPackage(value: String?): Boolean = value != null && value.length in 3..255 && packagePattern.matches(value)
    fun resolvePackage(installed: List<InstalledExternalPlayer>, requested: String?, resolvedDefault: String?): String {
        if (requested != null) {
            check(validPackage(requested) && installed.any { it.packageName == requested }) { UNAVAILABLE_MESSAGE }
            return requested
        }
        check(validPackage(resolvedDefault) && installed.any { it.packageName == resolvedDefault }) { CHOOSE_MESSAGE }
        return requireNotNull(resolvedDefault)
    }
    fun knownLabel(packageName: String): String? = when (packageName) {
        "com.brouken.player" -> "Just Player"
        "org.videolan.vlc" -> "VLC"
        else -> null
    }
    fun ordered(installed: List<InstalledExternalPlayer>, ownPackage: String): List<InstalledExternalPlayer> = installed
        .filter { validPackage(it.packageName) && it.packageName != ownPackage }
        .map { it.copy(label = knownLabel(it.packageName) ?: it.label.filterNot { Character.isISOControl(it) }.trim().take(80).ifBlank { it.packageName }) }
        .sortedWith(compareBy<InstalledExternalPlayer> { when(it.packageName) {
            "com.brouken.player" -> 0; "org.videolan.vlc" -> 1; else -> 2
        } }.thenBy { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName })
        .distinctBy { it.packageName }
}
