package com.nuvio.tv.ui.navigation

import android.os.Bundle
import com.nuvio.tv.ui.screens.player.PlayerProgressWriteOwner
import io.mockk.every
import io.mockk.mockk
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls the production error-navigation owner; only Android Bundle and navigation effects are faked. */
class PlayerErrorSourceNavigationTest {
    private val initial = PlayerProgressWriteOwner(7, "tt-current", "series", "native:cut-A:ep-2", 1, 2)
    private val current = initial.copy(videoId = "native:cut-A:ep-3", episode = 3)

    @Test fun `an in-place current episode never pops the original episode picker`() {
        val f = exit(current, initial)

        assertEquals(0, f.pops)
        assertCurrentRoute(f, current)
    }

    @Test fun `without a retained picker the route uses all six current identity fields`() {
        val f = exit(current, null)

        assertCurrentRoute(f, current)
        assertEquals("", query(f.routes.single())["episodeName"])
        assertFalse(f.routes.single().contains("Old%20episode%20two"))
    }

    @Test fun `same coordinates cannot reuse a different native video or edition`() {
        val otherCut = current.copy(videoId = "native:cut-B:ep-3")
        val f = exit(otherCut, current)

        assertEquals(0, f.pops)
        assertCurrentRoute(f, otherCut)
    }

    @Test fun `only the exact retained profile content type video and coordinates are reused`() {
        val f = exit(current, current)

        assertEquals(1, f.pops)
        assertTrue(f.routes.isEmpty())
        assertEquals(0, f.backs)
    }

    @Test fun `a retained entry for another profile content or type is not reused`() {
        for (retained in listOf(current.copy(profileId = 8), current.copy(contentId = "tt-other"),
            current.copy(contentType = "tv"), current.copy(season = 2))) {
            val f = exit(current, retained)
            assertEquals("Retained $retained", 0, f.pops)
            assertCurrentRoute(f, current)
        }
    }

    @Test fun `an invalidated profile owner goes Back without popping any source picker`() {
        val f = exit(null, initial)

        assertEquals(0, f.pops)
        assertTrue(f.routes.isEmpty())
        assertEquals(1, f.backs)
    }

    @Test fun `incomplete current identity never refills fields from the original episode`() {
        for (owner in listOf(current.copy(videoId = ""), current.copy(contentId = ""),
            current.copy(contentType = ""), current.copy(season = null), current.copy(episode = null))) {
            val f = exit(owner, null)
            assertTrue("Owner $owner", f.routes.isEmpty())
            assertEquals(0, f.pops)
            assertEquals(1, f.backs)
        }
    }

    @Test fun `a different parent content context cannot supply display fields for current routing`() {
        val f = exit(current.copy(contentId = "tt-other"), null)

        assertTrue(f.routes.isEmpty())
        assertEquals(1, f.backs)
    }

    @Test fun `a movie keeps its null coordinates and explicit profile in a manual picker`() {
        val movie = PlayerProgressWriteOwner(0, "tt-movie", "movie", "native:movie", null, null)
        val f = exit(movie, null, arguments(movie))

        assertCurrentRoute(f, movie)
    }

    @Test fun `source routes retain return flags without transferring source or grant inputs`() {
        val f = exit(initial, null, arguments(initial) + mapOf(
            "returnToDetailOnBack" to "true", "returnToHomeOnBack" to "true",
            "streamUrl" to "https://secret.invalid/private-file", "headers" to "private-header",
            "infoHash" to "private-hash", "fileIdx" to "9", "sources" to "private-grant"))
        assertCurrentRoute(f, initial)
        val route = f.routes.single()
        assertEquals("true", query(route)["returnToDetailOnBack"])
        assertEquals("true", query(route)["returnToHomeOnBack"])
        for (secret in listOf("secret.invalid", "private-file", "private-header", "private-hash", "private-grant")) {
            assertFalse(route.contains(secret))
        }
    }

    @Test fun `source-entry identity is read exactly and cannot inherit a missing profile`() {
        assertEquals(current, playerSourceSelectionOwner(bundle(arguments(current))))
        assertNull(playerSourceSelectionOwner(bundle(arguments(current) - "profileId")))
        assertNull(playerSourceSelectionOwner(null))
    }

    private class Effects {
        var pops = 0
        var backs = 0
        val routes = mutableListOf<String>()
    }

    private fun exit(owner: PlayerProgressWriteOwner?, retained: PlayerProgressWriteOwner?,
        args: Map<String, String?> = arguments(initial)): Effects {
        val effects = Effects()
        returnFromPlaybackError(owner, retained, bundle(args),
            popBackToStream = { effects.pops++; retained != null },
            navigateToStream = { effects.routes += it }, goBack = { effects.backs++ })
        return effects
    }

    private fun assertCurrentRoute(f: Effects, owner: PlayerProgressWriteOwner) {
        assertEquals(0, f.backs)
        assertEquals(1, f.routes.size)
        val route = f.routes.single()
        val path = route.substringBefore('?').split('/').map(::decode)
        assertEquals(listOf("stream", owner.videoId, owner.contentType, "Owned series"), path)
        val values = query(route)
        assertEquals(owner.contentId, values["contentId"])
        assertEquals(owner.profileId.toString(), values["profileId"])
        assertEquals(owner.season?.toString().orEmpty(), values["season"])
        assertEquals(owner.episode?.toString().orEmpty(), values["episode"])
        assertEquals("true", values["manualSelection"])
    }

    private fun arguments(owner: PlayerProgressWriteOwner): Map<String, String?> = mapOf(
        "contentId" to owner.contentId, "contentType" to owner.contentType,
        "videoId" to owner.videoId, "season" to owner.season?.toString(),
        "episode" to owner.episode?.toString(), "profileId" to owner.profileId.toString(),
        "title" to "Owned series", "episodeTitle" to "Old episode two")

    private fun bundle(values: Map<String, String?>): Bundle = mockk<Bundle>().also { args ->
        every { args.getString(any()) } answers { values[firstArg<String>()] }
    }

    private fun query(route: String) = route.substringAfter('?').split('&').associate {
        decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
    }

    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
}
