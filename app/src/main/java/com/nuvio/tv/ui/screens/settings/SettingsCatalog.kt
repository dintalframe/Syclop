package com.nuvio.tv.ui.screens.settings

internal enum class SettingsRailGroup {
    YOU,
    LOOK,
    WATCH,
    SERVICES,
    SYSTEM
}

internal enum class SettingsCategory(val group: SettingsRailGroup) {
    ACCOUNTS(SettingsRailGroup.SERVICES),
    APP(SettingsRailGroup.YOU),
    SOURCES(SettingsRailGroup.WATCH),
    APPEARANCE_GROUP(SettingsRailGroup.LOOK),
    LIBRARY(SettingsRailGroup.SERVICES),
    ADVANCED_GROUP(SettingsRailGroup.SYSTEM),
    ACCOUNT(SettingsRailGroup.YOU),
    PROFILES(SettingsRailGroup.YOU),
    WATCH_PARTY(SettingsRailGroup.YOU),
    APPEARANCE(SettingsRailGroup.LOOK),
    LAYOUT(SettingsRailGroup.LOOK),
    CONTENT_DISCOVERY(SettingsRailGroup.WATCH),
    PLAYBACK(SettingsRailGroup.WATCH),
    INTEGRATION(SettingsRailGroup.SERVICES),
    STREAMING_SERVICES(SettingsRailGroup.SERVICES),
    PLUGINS(SettingsRailGroup.SYSTEM),
    TRACKING(SettingsRailGroup.SERVICES),
    ADVANCED(SettingsRailGroup.SYSTEM),
    ABOUT(SettingsRailGroup.SYSTEM),
    DEBUG(SettingsRailGroup.SYSTEM),
    EXPERIENCE(SettingsRailGroup.SYSTEM)
}

internal fun visibleSettingsCategories(): List<SettingsCategory> =
    listOf(SettingsCategory.ACCOUNTS, SettingsCategory.APP, SettingsCategory.ADVANCED_GROUP)

internal fun availableSettingsCategories(
    isPrimaryProfile: Boolean,
    isEssentialMode: Boolean,
    isDebugBuild: Boolean,
    isWatchPartyAvailable: Boolean = false,
    pluginsEnabled: Boolean = true,
    localOnlyBuild: Boolean = false
): List<SettingsCategory> = SettingsCategory.entries.filter { category ->
    when (category) {
        SettingsCategory.ACCOUNT -> isPrimaryProfile && !localOnlyBuild
        SettingsCategory.PROFILES -> isPrimaryProfile
        SettingsCategory.WATCH_PARTY -> isWatchPartyAvailable
        SettingsCategory.PLUGINS -> pluginsEnabled && !isEssentialMode
        SettingsCategory.DEBUG -> isDebugBuild && !isEssentialMode
        SettingsCategory.SOURCES, SettingsCategory.APPEARANCE_GROUP, SettingsCategory.EXPERIENCE -> false
        else -> true
    }
}

/** Leaf pages keep their existing identity; the rail shows the everyday task that owns them. */
internal fun settingsRailCategory(category: SettingsCategory): SettingsCategory = when (category) {
    SettingsCategory.ACCOUNT, SettingsCategory.PROFILES, SettingsCategory.WATCH_PARTY,
    SettingsCategory.ABOUT -> SettingsCategory.APP
    SettingsCategory.TRACKING, SettingsCategory.CONTENT_DISCOVERY, SettingsCategory.STREAMING_SERVICES,
    SettingsCategory.SOURCES -> SettingsCategory.ACCOUNTS
    SettingsCategory.PLAYBACK, SettingsCategory.APPEARANCE, SettingsCategory.LAYOUT, SettingsCategory.LIBRARY,
    SettingsCategory.ADVANCED, SettingsCategory.INTEGRATION, SettingsCategory.DEBUG, SettingsCategory.PLUGINS,
    SettingsCategory.APPEARANCE_GROUP, SettingsCategory.EXPERIENCE -> SettingsCategory.ADVANCED_GROUP
    else -> category
}

internal fun settingsSubcategories(
    category: SettingsCategory,
    isPrimaryProfile: Boolean,
    isEssentialMode: Boolean,
    isDebugBuild: Boolean,
    isWatchPartyAvailable: Boolean = false,
    pluginsEnabled: Boolean = true,
    localOnlyBuild: Boolean = false
): List<SettingsCategory> {
    val available = availableSettingsCategories(isPrimaryProfile, isEssentialMode, isDebugBuild,
        isWatchPartyAvailable, pluginsEnabled, localOnlyBuild)
    val entries = when (category) {
        SettingsCategory.ACCOUNTS -> listOf(SettingsCategory.TRACKING, SettingsCategory.CONTENT_DISCOVERY,
            SettingsCategory.STREAMING_SERVICES)
        SettingsCategory.APP -> listOf(SettingsCategory.ABOUT, SettingsCategory.ACCOUNT, SettingsCategory.PROFILES,
            SettingsCategory.WATCH_PARTY)
        SettingsCategory.ADVANCED_GROUP -> listOf(SettingsCategory.PLAYBACK, SettingsCategory.APPEARANCE,
            SettingsCategory.LAYOUT, SettingsCategory.LIBRARY, SettingsCategory.INTEGRATION, SettingsCategory.ADVANCED,
            SettingsCategory.PLUGINS, SettingsCategory.DEBUG)
        else -> emptyList()
    }
    return entries.filter { it in available }
}

/** Returning focus to the same group must not replace an open page after the rail's delay. */
internal fun settingsCategoryOnRailFocus(current: SettingsCategory, focused: SettingsCategory): SettingsCategory =
    if (settingsRailCategory(current) == focused) current else focused

internal fun settingsBackTarget(current: SettingsCategory): SettingsCategory? =
    settingsRailCategory(current).takeIf { it != current }

internal fun settingsMenuFocusTarget(entries: List<SettingsCategory>, preferred: SettingsCategory?): SettingsCategory? =
    preferred?.takeIf { it in entries } ?: entries.firstOrNull()

internal fun List<SettingsCategory>.startsNewGroup(index: Int): Boolean =
    index > 0 && this[index - 1].group != this[index].group
