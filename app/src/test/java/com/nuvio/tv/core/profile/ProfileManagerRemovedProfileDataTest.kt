package com.nuvio.tv.core.profile

import android.content.Context
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.core.sync.androidtv.TvCardLaunchAuthenticator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProfileManagerRemovedProfileDataTest {

    private lateinit var tempDir: File
    private lateinit var factory: ProfileDataStoreFactory
    private lateinit var credentialStore: ProfileScopedCredentialStore
    private lateinit var lockStore: ProfileLockStateDataStore
    private lateinit var manager: ProfileManager
    private val activeProfileId = MutableStateFlow(1)
    private val lockStates = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    private val storedProfiles = MutableStateFlow(listOf(profile(1), profile(2), profile(3)))

    @Before
    fun setUp() {
        mockkObject(TvCardLaunchAuthenticator.Companion)
        every { TvCardLaunchAuthenticator.revokeIssuedCards() } returns Unit
        tempDir = Files.createTempDirectory("profile_manager_test").toFile()
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempDir
        }
        val profileDataStore = mockk<ProfileDataStore>(relaxed = true) {
            every { profilesList } returns storedProfiles
            every { activeProfileId } returns this@ProfileManagerRemovedProfileDataTest.activeProfileId
            every { hasEverSelectedProfile } returns flowOf(true)
            every { rememberLastProfileEnabled } returns flowOf(false)
            every { confirmExitEnabled } returns flowOf(false)
            every { startupSplashEnabled } returns flowOf(true)
        }
        coEvery { profileDataStore.setActiveProfile(any()) } answers { activeProfileId.value = firstArg() }
        coEvery { profileDataStore.replaceAllProfiles(any()) } answers { storedProfiles.value = firstArg() }
        coEvery { profileDataStore.deleteProfile(any()) } answers {
            storedProfiles.value = storedProfiles.value.filterNot { it.id == firstArg<Int>() }
        }
        factory = mockk(relaxed = true)
        credentialStore = mockk(relaxed = true)
        lockStore = mockk(relaxed = true)
        every { lockStore.pinEnabled } returns lockStates
        manager = ProfileManager(profileDataStore, factory, setOf(credentialStore), lockStore, context)
    }

    @After
    fun tearDown() {
        unmockkObject(TvCardLaunchAuthenticator.Companion)
        tempDir.deleteRecursively()
    }

    @Test
    fun `profiles removed on another device lose their local data`() = runTest {
        val removed = profileFiles(2)
        val kept = profileFiles(3) + file("datastore/library_preferences.preferences_pb")

        manager.replaceAllProfiles(listOf(profile(1), profile(3)))

        removed.forEach { assertFalse("${it.path} should be deleted", it.exists()) }
        kept.forEach { assertTrue("${it.path} should be kept", it.exists()) }
        verify(exactly = 1) { credentialStore.removeProfile(2) }
        verify(exactly = 0) { credentialStore.removeProfile(3) }
        coVerify(exactly = 1) { factory.clearProfile(2) }
        coVerify(exactly = 1) { lockStore.removeProfile(2) }
        coVerify(exactly = 0) { lockStore.removeProfile(3) }
        verify(exactly = 1) { TvCardLaunchAuthenticator.revokeIssuedCards() }
    }

    @Test
    fun `profiles added on another device are not treated as deleted`() = runTest {
        manager.replaceAllProfiles(listOf(profile(1), profile(2), profile(3), profile(4)))

        verify(exactly = 1) { factory.markProfileCreated(4) }
        verify(exactly = 0) { credentialStore.removeProfile(any()) }
        coVerify(exactly = 0) { factory.clearProfile(any()) }
        verify(exactly = 0) { TvCardLaunchAuthenticator.revokeIssuedCards() }
    }

    @Test
    fun `deleting a profile removes its shadow copies and Continue Watching cache`() = runTest {
        manager.profiles.first { profiles -> profiles.any { it.id == 2 } }
        val removed = profileFiles(2)
        val kept = profileFiles(3)

        manager.deleteProfile(2)

        removed.forEach { assertFalse("${it.path} should be deleted", it.exists()) }
        kept.forEach { assertTrue("${it.path} should be kept", it.exists()) }
        verify(exactly = 1) { credentialStore.removeProfile(2) }
        coVerify(exactly = 1) { lockStore.removeProfile(2) }
        verify(exactly = 1) { TvCardLaunchAuthenticator.revokeIssuedCards() }
    }

    @Test
    fun `failed card revocation prevents reusing a deleted profile identity`() = runTest {
        every { TvCardLaunchAuthenticator.revokeIssuedCards() } throws java.io.IOException("Fixture unavailable")
        val result = runCatching { manager.replaceAllProfiles(listOf(profile(1), profile(3))) }
        assertTrue(result.isFailure)
        assertTrue(storedProfiles.value.any { it.id == 2 })
        verify(exactly = 0) { credentialStore.removeProfile(any()) }
    }

    @Test
    fun `launcher publication requires the current selection and a profile without a PIN`() = runTest {
        manager.profiles.first { it.size == 3 }
        val primaryRevision = manager.activeProfileSelectionRevision
        assertTrue(manager.canPublishLauncherHistory(1, primaryRevision))

        lockStates.value = mapOf(1 to true)
        assertEquals(ProfileManager.LauncherPrivacy(null, setOf(1)), manager.launcherPrivacy.first())
        assertFalse(manager.canPublishLauncherHistory(1, primaryRevision))

        manager.setActiveProfile(2)
        manager.activeProfileId.first { it == 2 }
        assertEquals(ProfileManager.LauncherPrivacy(2, setOf(1)), manager.launcherPrivacy.first())
        assertTrue(manager.canPublishLauncherHistory(2, manager.activeProfileSelectionRevision))
        lockStates.value = emptyMap()
        manager.setActiveProfile(1)
        manager.activeProfileId.first { it == 1 }
        assertFalse(manager.canPublishLauncherHistory(1, primaryRevision))
    }

    private fun profileFiles(id: Int) = listOf(
        "datastore/library_preferences_p$id.preferences_pb",
        "datastore/library_preferences_p$id.preferences_pb.bak",
        "datastore/trakt_auth_store_p$id.preferences_pb.bak",
        "cw_enrichment/inprogress_$id.json",
        "cw_enrichment/nextup_$id.json",
        "plugin_code_p$id/scraper.js"
    ).map(::file)

    private fun file(path: String) = File(tempDir, path).apply {
        parentFile?.mkdirs()
        writeText("x")
    }

    private fun profile(id: Int) = UserProfile(id = id, name = "Profile $id", avatarColorHex = "#1E88E5")
}
