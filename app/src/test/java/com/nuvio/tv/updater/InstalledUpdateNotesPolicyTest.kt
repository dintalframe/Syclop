package com.nuvio.tv.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledUpdateNotesPolicyTest {
    @Test fun `legacy upgrade shows exact installed release until acknowledged`() {
        assertTrue(show(acknowledged = null))
        assertFalse(show(acknowledged = 4))
    }

    @Test fun `only a newer numeric version shows again`() {
        assertTrue(show(acknowledged = 3))
        assertFalse(show(acknowledged = 5))
        assertTrue(InstalledUpdateNotesPolicy.shouldShow("0.1.10", "0.1.10", 11, 10, 100, 200))
    }

    @Test fun `clean install and invalid package timestamps never show upgrade notes`() {
        assertFalse(show(first = 100, updated = 100))
        assertFalse(show(first = 0, updated = 100))
        assertFalse(show(first = 200, updated = 100))
    }

    @Test fun `other release notes cannot describe the installed release`() {
        assertFalse(show(bundle = "0.1.4"))
        assertFalse(show(bundle = ""))
    }

    @Test fun `opening without acknowledgement keeps notes eligible on next launch`() {
        assertTrue(show(acknowledged = 3))
        assertTrue(show(acknowledged = 3))
        assertFalse(show(acknowledged = 4))
    }

    @Test fun `ambient dismissal cannot acknowledge notes`() {
        assertFalse(InstalledUpdateNotesPolicy.shouldAcknowledge(ambientShowing = true))
        assertTrue(InstalledUpdateNotesPolicy.shouldAcknowledge(ambientShowing = false))
    }

    @Test fun `home notice yields to every pending deep link and addon operation`() {
        assertTrue(present())
        assertFalse(present(homeReady = false))
        assertFalse(present(deepLink = true))
        assertFalse(present(launch = true))
        assertFalse(present(confirmation = true))
        assertFalse(present(installing = true))
    }

    private fun present(
        homeReady: Boolean = true,
        deepLink: Boolean = false,
        launch: Boolean = false,
        confirmation: Boolean = false,
        installing: Boolean = false
    ) = InstalledUpdateNotesPolicy.canPresent(homeReady, deepLink, launch, confirmation, installing)

    private fun show(
        acknowledged: Int? = null,
        first: Long = 100,
        updated: Long = 200,
        bundle: String = "0.1.3"
    ) = InstalledUpdateNotesPolicy.shouldShow("0.1.3", bundle, 4, acknowledged, first, updated)
}
