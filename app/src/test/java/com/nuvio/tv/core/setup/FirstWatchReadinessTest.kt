package com.nuvio.tv.core.setup

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.DebridSettings
import org.junit.Assert.*
import org.junit.Test

class FirstWatchReadinessTest {
    private val now = 2_000_000L
    private val settings = DebridSettings(realDebridApiKey = "private-test-key")
    private val source = addon(stream = true, catalog = false)

    @Test fun `catalog-only addon does not configure a video source`() {
        val result = project(addons = listOf(addon(stream = false, catalog = true)))
        assertTrue(result.catalogAdded)
        assertFalse(result.sourceAdded)
        assertNull(result.playback)
    }

    @Test fun `source-only addon does not configure a catalog or prove playback`() {
        val result = project()
        assertTrue(result.sourceAdded)
        assertFalse(result.catalogAdded)
        assertNull(result.playback)
    }

    @Test fun `disabled catalog and source addons do not count`() {
        val result = project(addons = listOf(addon(true, true).copy(enabled = false)))
        assertFalse(result.catalogAdded)
        assertFalse(result.sourceAdded)
    }

    @Test fun `saved native playback account is required and initially unknown`() {
        val result = project()
        assertTrue(result.playbackServiceRequired)
        val account = result.accounts.single()
        assertEquals("realdebrid", account.providerId)
        assertNull(account.outcome)
        assertNull(account.ageMs)
        assertNull(result.playback)
    }

    @Test fun `accepted sign-in records its age without claiming a title started`() {
        val result = project(observations = FirstWatchObservations(accounts = mapOf("realdebrid" to account())))
        assertEquals(FirstWatchAccountOutcome.ACCEPTED, result.accounts.single().outcome)
        assertEquals(2_000L, result.accounts.single().ageMs)
        assertNull(result.playback)
    }

    @Test fun `observation is unknown at fifteen minutes and for future or invalid times`() {
        for (checked in listOf(now - 900_000L, now + 1, 0L, -1L)) {
            val result = project(observations = FirstWatchObservations(accounts = mapOf("realdebrid" to account(checked))))
            assertNull("checkedAt=$checked", result.accounts.single().outcome)
        }
        val result = project(observations = FirstWatchObservations(accounts = mapOf("realdebrid" to account(now - 899_999L))))
        assertEquals(FirstWatchAccountOutcome.ACCEPTED, result.accounts.single().outcome)
    }

    @Test fun `known account entitlement deadline expires the observation sooner`() {
        val result = project(observations = FirstWatchObservations(accounts = mapOf("realdebrid" to
            account().copy(entitlementExpiresAtMs = now))))
        assertNull(result.accounts.single().outcome)
        assertEquals(2_000L, result.accounts.single().ageMs)
    }

    @Test fun `replacement credential and profile cannot inherit accepted sign-in`() {
        val evidence = FirstWatchObservations(accounts = mapOf("realdebrid" to account()))
        assertNull(project(settings = settings.copy(realDebridApiKey = "replacement"), observations = evidence)
            .accounts.single().outcome)
        assertNull(project(profileId = 2, observations = evidence).accounts.single().outcome)
    }

    @Test fun `rejected and unavailable checks retain distinct safe outcomes`() {
        for (outcome in listOf(FirstWatchAccountOutcome.REJECTED, FirstWatchAccountOutcome.UNAVAILABLE)) {
            val result = project(observations = FirstWatchObservations(accounts = mapOf("realdebrid" to
                account().copy(outcome = outcome))))
            assertEquals(outcome, result.accounts.single().outcome)
            assertNull(result.playback)
        }
    }

    @Test fun `actual supported selected playback records only that title and age`() {
        val observation = playback()
        val result = project(observations = FirstWatchObservations(playback = observation))
        assertEquals(observation, result.playback)
        assertEquals(1_000L, result.playbackAgeMs)
    }

    @Test fun `source engine profile credential changes and expiry invalidate playback`() {
        val evidence = FirstWatchObservations(playback = playback())
        assertNull(project(addons = listOf(source.copy(version = "2")), observations = evidence).playback)
        assertNull(project(engine = "mpv", observations = evidence).playback)
        assertNull(project(profileId = 2, observations = evidence).playback)
        assertNull(project(settings = settings.copy(realDebridApiKey = "replacement"), observations = evidence).playback)
        assertNull(project(observations = evidence.copy(playback = playback().copy(checkedAtMs = now - 900_000L))).playback)
        assertNull(project(observations = evidence.copy(playback = playback().copy(checkedAtMs = now + 1))).playback)
    }

    @Test fun `external player launch without local startup remains unknown`() {
        val launched = playback().copy(engine = "external",
            binding = firstWatchPlaybackBinding(1, listOf(source), settings, "external"))
        val result = project(engine = "external", observations = FirstWatchObservations(playback = launched))
        assertNull(result.playback)
    }

    @Test fun `fingerprints bind identity without retaining credentials or private addon URL`() {
        val credential = firstWatchAccountBinding(1, "realdebrid", settings.realDebridApiKey)
        assertTrue(credential.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(credential, firstWatchAccountBinding(2, "realdebrid", settings.realDebridApiKey))
        assertNotEquals(credential, firstWatchAccountBinding(1, "realdebrid", "replacement"))
        val configuration = firstWatchPlaybackBinding(1, listOf(source), settings, "exo")
        assertTrue(configuration.matches(Regex("[a-f0-9]{64}")))
        assertFalse(configuration.contains("private"))
    }

    private fun account(checkedAtMs: Long = now - 2_000L) = FirstWatchAccountObservation(
        binding = firstWatchAccountBinding(1, "realdebrid", settings.realDebridApiKey),
        checkedAtMs = checkedAtMs, outcome = FirstWatchAccountOutcome.ACCEPTED
    )

    private fun playback() = FirstWatchPlaybackObservation(
        binding = firstWatchPlaybackBinding(1, listOf(source), settings, "exo"),
        checkedAtMs = now - 1_000L, title = "Owned selection", videoId = "owned:1", engine = "exo",
        outcome = FirstWatchPlaybackOutcome.STARTED
    )

    private fun project(
        addons: List<Addon> = listOf(source),
        settings: DebridSettings = this.settings,
        profileId: Int = 1,
        engine: String = "exo",
        observations: FirstWatchObservations = FirstWatchObservations()
    ) = firstWatchReadiness(addons, settings, profileId, engine, observations, now)

    private fun addon(stream: Boolean, catalog: Boolean) = Addon(
        id = "configured-addon", name = "Source", version = "1", description = null, logo = null,
        baseUrl = "https://example.test/private-addon-config", types = listOf(ContentType.MOVIE),
        catalogs = if (catalog) listOf(CatalogDescriptor(ContentType.MOVIE, id = "movies", name = "Movies")) else emptyList(),
        resources = if (stream) listOf(AddonResource("stream", listOf("movie"), listOf("tt")))
            else listOf(AddonResource("meta", listOf("movie"), null))
    )
}
