package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlayerAutomaticDefaultsTest {
    @Test fun `new profiles play ranked first source and continue the next episode by default`() = runTest {
        val h = Harness()
        assertEquals(StreamAutoPlayMode.FIRST_STREAM, PlayerSettings().streamAutoPlayMode)
        assertTrue(PlayerSettings().streamAutoPlayNextEpisodeEnabled)
        for (profile in listOf(1, 2)) {
            h.active.value = profile
            val settings = h.store().playerSettings.first()
            assertEquals(StreamAutoPlayMode.FIRST_STREAM, settings.streamAutoPlayMode)
            assertTrue(settings.streamAutoPlayNextEpisodeEnabled)
            assertTrue(h.memory.getValue(profile).value.asMap().keys.none {
                it.name == "stream_auto_play_mode" || it.name == "stream_auto_play_next_episode_enabled"
            })
        }
    }

    @Test fun `invalid automatic settings use defaults without dropping other preferences or rewriting values`() = runTest {
        val mode = stringPreferencesKey("stream_auto_play_mode")
        val malformedNextEpisode = stringPreferencesKey("stream_auto_play_next_episode_enabled")
        val regex = stringPreferencesKey("stream_auto_play_regex")
        val h = Harness(TestPreferencesStore(mutablePreferencesOf(
            mode to "future-or-invalid-mode", malformedNextEpisode to "invalid-boolean", regex to "preferred-group"
        )))
        val settings = h.store().playerSettings.first()
        assertEquals(StreamAutoPlayMode.FIRST_STREAM, settings.streamAutoPlayMode)
        assertTrue(settings.streamAutoPlayNextEpisodeEnabled)
        assertEquals("preferred-group", settings.streamAutoPlayRegex)
        assertEquals("future-or-invalid-mode", h.memory.getValue(1).value[mode])
        assertEquals("invalid-boolean", h.memory.getValue(1).value[malformedNextEpisode])
    }

    @Test fun `explicit manual selection and next episode opt out survive store recreation`() = runTest {
        val h = Harness()
        h.store().setStreamAutoPlayMode(StreamAutoPlayMode.MANUAL)
        h.store().setStreamAutoPlayNextEpisodeEnabled(false)
        val settings = h.store().playerSettings.first()
        assertEquals(StreamAutoPlayMode.MANUAL, settings.streamAutoPlayMode)
        assertFalse(settings.streamAutoPlayNextEpisodeEnabled)
        assertEquals("MANUAL", h.memory.getValue(1).value[stringPreferencesKey("stream_auto_play_mode")])
        assertEquals(false, h.memory.getValue(1).value[booleanPreferencesKey("stream_auto_play_next_episode_enabled")])
    }

    @Test fun `manual and custom source preferences remain isolated between profiles`() = runTest {
        val h = Harness()
        val store = h.store()
        store.setStreamAutoPlayMode(StreamAutoPlayMode.MANUAL)
        store.setStreamAutoPlayNextEpisodeEnabled(false)
        h.active.value = 2
        assertEquals(StreamAutoPlayMode.FIRST_STREAM, store.playerSettings.first().streamAutoPlayMode)
        assertTrue(store.playerSettings.first().streamAutoPlayNextEpisodeEnabled)
        store.setStreamAutoPlayMode(StreamAutoPlayMode.REGEX_MATCH)
        store.setStreamAutoPlayNextEpisodeEnabled(true)
        assertEquals(StreamAutoPlayMode.REGEX_MATCH, h.store().playerSettings.first().streamAutoPlayMode)
        h.active.value = 1
        assertEquals(StreamAutoPlayMode.MANUAL, store.playerSettings.first().streamAutoPlayMode)
        assertFalse(store.playerSettings.first().streamAutoPlayNextEpisodeEnabled)
    }

    private class Harness(first: TestPreferencesStore = TestPreferencesStore()) {
        val active = MutableStateFlow(1)
        // JVM Android stubs have no system Resources. An explicit language exercises the
        // real player DataStore without depending on the unrelated device-locale service.
        fun subtitleReady(store: TestPreferencesStore) = TestPreferencesStore(store.value.toMutablePreferences().apply {
            this[stringPreferencesKey("subtitle_preferred_language")] = "en"
        })
        val memory = mapOf(1 to subtitleReady(first), 2 to subtitleReady(TestPreferencesStore()))
        private val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), "player_settings") } answers { memory.getValue(firstArg()) }
        }
        private val profiles = mockk<ProfileManager> { every { activeProfileId } returns active }
        private val context = mockk<Context>(relaxed = true)
        fun store() = PlayerSettingsDataStore(factory, profiles, context)
    }
}
