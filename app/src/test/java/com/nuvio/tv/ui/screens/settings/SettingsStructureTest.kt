package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStructureTest {

    @Test
    fun `Back returns a nested page to its menu before leaving for the rail`() {
        val parents = mapOf(
            SettingsCategory.TRACKING to SettingsCategory.ACCOUNTS,
            SettingsCategory.CONTENT_DISCOVERY to SettingsCategory.ACCOUNTS,
            SettingsCategory.STREAMING_SERVICES to SettingsCategory.ACCOUNTS,
            SettingsCategory.ABOUT to SettingsCategory.APP,
            SettingsCategory.PROFILES to SettingsCategory.APP,
            SettingsCategory.PLAYBACK to SettingsCategory.ADVANCED_GROUP,
            SettingsCategory.LIBRARY to SettingsCategory.ADVANCED_GROUP,
            SettingsCategory.LAYOUT to SettingsCategory.ADVANCED_GROUP,
            SettingsCategory.INTEGRATION to SettingsCategory.ADVANCED_GROUP,
            SettingsCategory.ADVANCED to SettingsCategory.ADVANCED_GROUP
        )
        for ((page, parent) in parents) {
            assertEquals(parent, settingsBackTarget(page))
            assertNull(settingsBackTarget(parent))
        }
    }

    @Test
    fun `delayed rail focus preserves the open page when returning to its group`() {
        for (page in listOf(SettingsCategory.LAYOUT, SettingsCategory.TRACKING, SettingsCategory.STREAMING_SERVICES,
            SettingsCategory.INTEGRATION, SettingsCategory.ADVANCED, SettingsCategory.ABOUT)) {
            assertEquals(page, settingsCategoryOnRailFocus(page, settingsRailCategory(page)))
            val anotherRail = if (page == SettingsCategory.ABOUT) SettingsCategory.ACCOUNTS else SettingsCategory.APP
            assertEquals(anotherRail, settingsCategoryOnRailFocus(page, anotherRail))
        }
    }

    @Test
    fun `returning to a menu restores its original action and rejects unavailable profile actions`() {
        val services = settingsSubcategories(SettingsCategory.ACCOUNTS, true, false, true)
        assertEquals(SettingsCategory.STREAMING_SERVICES, settingsMenuFocusTarget(services, SettingsCategory.STREAMING_SERVICES))
        val secondaryApp = settingsSubcategories(SettingsCategory.APP, false, false, true)
        assertEquals(SettingsCategory.ABOUT, settingsMenuFocusTarget(secondaryApp, SettingsCategory.PROFILES))
        assertEquals(SettingsCategory.ABOUT, settingsMenuFocusTarget(secondaryApp, SettingsCategory.ACCOUNT))
        assertNull(settingsMenuFocusTarget(emptyList(), SettingsCategory.LAYOUT))
    }

    @Test
    fun `a restored old rail category lands in its new home`() {
        assertEquals(SettingsCategory.ACCOUNTS, settingsRailCategory(SettingsCategory.SOURCES))
        assertEquals(SettingsCategory.ADVANCED_GROUP, settingsRailCategory(SettingsCategory.APPEARANCE_GROUP))
    }

    @Test
    fun `playback sections follow the documented order`() {
        assertEquals(
            listOf(
                PlaybackSection.PLAYER,
                PlaybackSection.STREAM_SELECTION,
                PlaybackSection.UP_NEXT,
                PlaybackSection.SKIP_SEGMENTS,
                PlaybackSection.PLAYER_INTERFACE,
                PlaybackSection.AUDIO,
                PlaybackSection.SUBTITLES,
                PlaybackSection.VIDEO,
                PlaybackSection.BUFFER_NETWORK,
                PlaybackSection.P2P
            ),
            visiblePlaybackSections(PlayerSettings(internalPlayerEngine = InternalPlayerEngine.EXOPLAYER))
        )
    }

    @Test
    fun `buffer and network is only shown for exoplayer based engines`() {
        assertTrue(PlaybackSection.BUFFER_NETWORK in visiblePlaybackSections(PlayerSettings(internalPlayerEngine = InternalPlayerEngine.EXOPLAYER)))
        assertTrue(PlaybackSection.BUFFER_NETWORK in visiblePlaybackSections(PlayerSettings(internalPlayerEngine = InternalPlayerEngine.AUTO)))
        assertFalse(PlaybackSection.BUFFER_NETWORK in visiblePlaybackSections(PlayerSettings(internalPlayerEngine = InternalPlayerEngine.MVP_PLAYER)))
    }

    @Test
    fun `audio and video are separate sections`() {
        val sections = visiblePlaybackSections(PlayerSettings())
        assertTrue(PlaybackSection.AUDIO in sections)
        assertTrue(PlaybackSection.VIDEO in sections)
    }

    @Test
    fun `layout sections follow the documented order`() {
        assertEquals(
            listOf(
                LayoutSection.HOME_LAYOUT,
                LayoutSection.HOME_CONTENT,
                LayoutSection.SIDEBAR,
                LayoutSection.CONTINUE_WATCHING,
                LayoutSection.FOCUSED_POSTER,
                LayoutSection.POSTER_CARD,
                LayoutSection.CARD_DEPTH,
                LayoutSection.CUSTOM_POSTERS,
                LayoutSection.DETAIL_PAGE,
                LayoutSection.STREAMS
            ),
            visibleLayoutSections(essentialMode = false, focusedPosterHasOptions = true)
        )
    }

    @Test
    fun `essential layout only offers the home layout`() {
        assertEquals(
            listOf(LayoutSection.HOME_LAYOUT),
            visibleLayoutSections(essentialMode = true, focusedPosterHasOptions = true)
        )
    }

    @Test
    fun `focused poster section is hidden when it has nothing to show`() {
        assertFalse(
            LayoutSection.FOCUSED_POSTER in visibleLayoutSections(essentialMode = false, focusedPosterHasOptions = false)
        )
    }

    @Test
    fun `focused poster has no options on the grid layout`() {
        assertFalse(LayoutSettingsUiState(selectedLayout = com.nuvio.tv.domain.model.HomeLayout.GRID).focusedPosterHasOptions())
        assertTrue(LayoutSettingsUiState(selectedLayout = com.nuvio.tv.domain.model.HomeLayout.CLASSIC).focusedPosterHasOptions())
    }
}
