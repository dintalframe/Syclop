package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test
    fun `Settings starts at connected services with one Advanced entry`() {
        assertEquals(
            listOf(SettingsCategory.ACCOUNTS, SettingsCategory.APP, SettingsCategory.ADVANCED_GROUP),
            visibleSettingsCategories()
        )
    }

    @Test
    fun `the connection menu groups trackers playback sources and debrid`() {
        for (primary in listOf(true, false)) {
            assertEquals(
                listOf(SettingsCategory.TRACKING, SettingsCategory.CONTENT_DISCOVERY, SettingsCategory.STREAMING_SERVICES),
                settingsSubcategories(SettingsCategory.ACCOUNTS, primary, false, false)
            )
        }
    }

    @Test
    fun `app tools keep primary profile and watch party access gates`() {
        val app = SettingsCategory.APP
        assertEquals(listOf(SettingsCategory.ABOUT, SettingsCategory.ACCOUNT, SettingsCategory.PROFILES, SettingsCategory.WATCH_PARTY),
            settingsSubcategories(app, true, true, false, isWatchPartyAvailable = true))
        assertEquals(listOf(SettingsCategory.ABOUT, SettingsCategory.PROFILES),
            settingsSubcategories(app, true, true, false, localOnlyBuild = true))
        assertEquals(listOf(SettingsCategory.ABOUT),
            settingsSubcategories(app, false, true, false))
        assertEquals(listOf(SettingsCategory.ABOUT, SettingsCategory.WATCH_PARTY),
            settingsSubcategories(app, false, true, false, isWatchPartyAvailable = true))
    }

    @Test
    fun `technical controls sit behind the Advanced entry in either experience`() {
        for (essential in listOf(true, false)) {
            val advanced = settingsSubcategories(SettingsCategory.ADVANCED_GROUP, true, essential, false,
                pluginsEnabled = false)
            assertEquals(listOf(SettingsCategory.PLAYBACK, SettingsCategory.APPEARANCE, SettingsCategory.LAYOUT,
                SettingsCategory.LIBRARY, SettingsCategory.INTEGRATION, SettingsCategory.ADVANCED), advanced)
        }
    }

    @Test
    fun `diagnostics and plugins retain their build and experience gates`() {
        val advanced = settingsSubcategories(SettingsCategory.ADVANCED_GROUP, true, false, true)
        assertTrue(SettingsCategory.DEBUG in advanced)
        assertTrue(SettingsCategory.PLUGINS in advanced)
        for ((essential, debug) in listOf(false to false, true to true)) {
            assertFalse(SettingsCategory.DEBUG in settingsSubcategories(SettingsCategory.ADVANCED_GROUP, true, essential, debug))
        }
        assertFalse(SettingsCategory.PLUGINS in settingsSubcategories(SettingsCategory.ADVANCED_GROUP, true, true, true))
        assertFalse(SettingsCategory.PLUGINS in settingsSubcategories(SettingsCategory.ADVANCED_GROUP, true, false, true,
            pluginsEnabled = false))
    }

    @Test
    fun `every available page has one route from the new Settings rail`() {
        val rail = visibleSettingsCategories()
        val subcategories = rail.flatMap { settingsSubcategories(it, true, false, true, true) }
        val reachable = rail + subcategories
        assertEquals(reachable.size, reachable.toSet().size)
        assertEquals(availableSettingsCategories(true, false, true, true).toSet(), reachable.toSet())
        assertFalse(rail.startsNewGroup(0))
    }
}
