package com.nuvio.tv.core.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import android.util.Xml
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.NuvioApplication
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.UserProfile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * Manual emulator-only storage fixture. Never add this class to a generic or account-free UI suite.
 * Run the three fixed phases in separate cold target-app processes. Existing singleton DataStores
 * are reused; bootstrap is exercised only by the application's real attachBaseContext entry point.
 */
@RunWith(AndroidJUnit4::class)
class LocalBackupStorageRoundTripAndroidTest {
    @Test
    fun runExplicitBackupStoragePhase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val phase = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT)
        assumeTrue("Explicit backup storage phase not selected.", phase != null)
        require(phase in setOf("seed", "restore", "undo")) { "Unknown fixed backup storage phase." }
        require(isQemu()) { "Backup storage fixture refuses a physical installation." }
        require(BuildConfig.LOCAL_ONLY_BUILD) { "Backup storage fixture requires the account-free local build." }
        val context = instrumentation.targetContext
        val report = JSONObject().put("schema", 1).put("phase", phase).put("qemuVerified", true)
            .put("pid", Process.myPid()).put("passed", false)
        var passed = false
        try {
            runBlocking {
                withTimeout(120_000L) {
                    withContext(Dispatchers.IO) {
                        val access = access(context)
                        withTimeout(10_000L) { access.profileManager.activeProfileReady.first { it } }
                        when (phase) {
                            "seed" -> seedPhase(context, access, report)
                            "restore" -> restorePhase(context, access, report)
                            "undo" -> undoPhase(context, access, report)
                        }
                        report.put("appSha256", sha256(File(context.applicationInfo.sourceDir)))
                        passed = true
                    }
                }
            }
        } catch (failure: Exception) {
            report.put("failureClass", safeClass(failure))
        } catch (failure: AssertionError) {
            report.put("failureClass", safeClass(failure))
        }
        report.put("passed", passed)
        var written = false
        try {
            File(checkNotNull(context.getExternalFilesDir(null)), "backup-roundtrip-$phase.json")
                .writeText(report.toString(2) + "\n")
            written = true
        } catch (_: Exception) { }
        assertTrue("Backup storage phase failed; inspect its sanitized report and retain its private recovery ledger.", passed && written)
    }

    private data class Access(val profileManager: ProfileManager, val profiles: ProfileDataStore,
        val factory: ProfileDataStoreFactory, val profileStore: DataStore<Preferences>, val backups: LocalBackupManager)

    private fun access(context: Context): Access {
        val app = context.applicationContext as NuvioApplication
        val manager = field<ProfileManager>(app.startupSyncService, "profileManager")
        val profiles = field<ProfileDataStore>(manager, "profileDataStore")
        val factory = field<ProfileDataStoreFactory>(manager, "factory")
        return Access(manager, profiles, factory, field(profiles, "dataStore"), LocalBackupManager(context, profiles, factory))
    }

    private suspend fun seedPhase(context: Context, a: Access, report: JSONObject) {
        val root = fixtureRoot(context)
        require(!File(root, "ledger.json").exists()) { "A fixture ledger already exists; recover it before another run." }
        require(!a.backups.pending() && !a.backups.canUndo()) { "Existing restore state must be preserved." }
        require(a.profiles.profilesList.first().none { it.id == 2 }) { "Secondary fixture profile slot is already used." }
        require(File(context.filesDir, "datastore").listFiles().orEmpty().none {
            it.name.contains("_p2.preferences_pb")
        }) { "Secondary fixture slot has existing files that must be preserved." }
        require(!privateSentinel(context).exists()) { "Private fixture sentinel already exists." }
        requireNoAccountBearingState(context)
        val lockFile = File(context.filesDir, "datastore/profile_lock_state.preferences_pb")
        val locks = if (lockFile.exists()) BackupRestoreBootstrap.readPreferences(lockFile).let {
            it[stringPreferencesKey("pin_enabled_map")].orEmpty()
        } else ""
        val lockRecords = locks.split(',').filter(String::isNotBlank)
        require(lockRecords.all { it.matches(Regex("[1-6]:false")) } &&
            lockRecords.map { it.substringBefore(':') }.distinct().size == lockRecords.size) {
            "Existing profile lock state must be preserved; fixture refused."
        }
        val ledger = JSONObject().put("schema", 1).put("nextPhase", "seeding").put("seedPid", Process.myPid())
        saveOriginalStores(a, root)
        File(BackupRestoreBootstrap.root(context), "notice.txt").takeIf(File::isFile)
            ?.copyTo(File(root, "original-notice.txt"), overwrite = false)
        writeLedger(root, ledger)
        preparePublicRegistrationProbe(context, root, ledger)
        writeLedger(root, ledger)
        seed(a, "A")
        privateSentinel(context).writeText(PRIVATE_A)
        val before = a.backups.localFiles().map { it.uri.toString() }.toSet()
        a.backups.export()
        val created = a.backups.localFiles().filter { it.uri.toString() !in before }
        require(created.size == 1 && created.single().uri.scheme == "content" && created.single().uri.authority == "media") {
            "Fixture requires one actual public Downloads publication."
        }
        val uri = created.single().uri
        ledger.put("ownedUri", uri.toString())
        writeLedger(root, ledger)
        context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.IS_PENDING), null, null, null).use { cursor ->
            require(cursor != null && cursor.moveToFirst() && cursor.getInt(0) == 0) { "Published row is still pending." }
        }
        val backup = a.backups.inspect(uri)
        require(backup.profiles.map { it.id }.toSet() == setOf(1, 2))
        require(backup.profiles.all { it.name == profileName("A", it.id) })
        val bytes = context.contentResolver.openInputStream(uri).use { checkNotNull(it).readBytes() }
        require(PortableBackupArchive.decode(bytes.inputStream()) == backup)
        require(backup.stores.none { it.feature !in PortableBackupPolicy.features })
        require(!backup.toString().contains(PRIVATE_MARKER) && !backup.toString().contains(PRIVATE_A)) {
            "Fixture-only private sentinel escaped the export allowlist."
        }
        require(backup.stores.count { it.feature == "library_preferences" } == 2)
        require(backup.stores.count { it.feature == "watched_items_preferences" } == 2)
        require(backup.stores.count { it.feature == "watch_progress_recent_preferences" } == 2)
        ledger.put("archiveSha256", sha256(bytes)).put("profileCount", backup.profiles.size)
        seed(a, "B")
        privateSentinel(context).writeText(PRIVATE_B)
        a.backups.queue(backup)
        require(a.backups.pending())
        ledger.put("nextPhase", "restore")
        writeLedger(root, ledger)
        report.put("profilesExported", 2).put("publicDownloadsPublished", true).put("archiveReadbackVerified", true)
            .put("privateSentinelExcluded", true).put("restoreQueued", true).put("archiveSha256", sha256(bytes))
    }

    private suspend fun restorePhase(context: Context, a: Access, report: JSONObject) {
        val root = fixtureRoot(context)
        val ledger = readLedger(root, "restore")
        require(ledger.getInt("seedPid") != Process.myPid()) { "Restore phase needs a cold target-app process." }
        require(!a.backups.pending() && a.backups.canUndo())
        verifySeed(a, "A")
        require(!privateSentinel(context).exists())
        require(publicRegistrationHash(context) == ledger.getString("registrationHash"))
        requireNoAccountBearingState(context)
        require(a.backups.notice()?.contains("Local restore completed") == true)
        a.backups.queue(null)
        require(a.backups.pending())
        ledger.put("nextPhase", "undo").put("restorePid", Process.myPid())
        writeLedger(root, ledger)
        report.put("coldBootstrapVerified", true).put("restoredProfiles", 2).put("privateSentinelRemoved", true)
            .put("localSourcesSelected", true).put("publicRegistrationPreserved", true).put("undoQueued", true)
    }

    private suspend fun undoPhase(context: Context, a: Access, report: JSONObject) {
        val root = fixtureRoot(context)
        val ledger = readLedger(root, "undo")
        require(ledger.getInt("restorePid") != Process.myPid()) { "Undo phase needs a cold target-app process." }
        require(!a.backups.pending())
        verifySeed(a, "B")
        require(privateSentinel(context).readText() == PRIVATE_B)
        require(publicRegistrationHash(context) == ledger.getString("registrationHash"))
        require(a.backups.notice()?.contains("Previous local data") == true)
        requireNoAccountBearingState(context)
        val uri = Uri.parse(ledger.getString("ownedUri"))
        require(uri.scheme == "content" && uri.authority == "media")
        require(context.contentResolver.delete(uri, null, null) == 1) { "Fixture-owned Downloads cleanup failed." }
        restoreOriginalStores(context, a, root)
        restorePublicRegistration(context, root, ledger)
        privateSentinel(context).delete()
        // There was no Undo snapshot before this fixture; remove only the recovery it created.
        require(File(BackupRestoreBootstrap.root(context), "recovery").deleteRecursively())
        val notice = File(BackupRestoreBootstrap.root(context), "notice.txt")
        val originalNotice = File(root, "original-notice.txt")
        if (originalNotice.exists()) originalNotice.copyTo(notice, overwrite = true) else notice.delete()
        report.put("coldUndoVerified", true).put("privateSentinelRecovered", true).put("restoredProfiles", 2)
            .put("ownedPublicationRemoved", true).put("originalLogicalPreferencesRestored", true)
            .put("limits", "No SAF picker, power-loss durability, real-account Keystore, or physical-Shield acceptance claim.")
        require(root.deleteRecursively())
    }

    private suspend fun seed(a: Access, marker: String) {
        a.profiles.replaceAllProfiles((1..2).map { UserProfile(it, profileName(marker, it), "#123456") })
        a.profiles.setActiveProfile(1)
        for (profile in 1..2) {
            for (feature in PortableBackupPolicy.features - "profile_settings") {
                a.factory.get(profile, feature).updateData { emptyPreferences() }
            }
            put(a, profile, "theme_settings", mutablePreferencesOf(
                stringPreferencesKey("selected_theme") to "WHITE", booleanPreferencesKey("amoled_mode") to (marker == "B")))
            put(a, profile, "layout_settings", mutablePreferencesOf(
                stringPreferencesKey("selected_layout") to "MODERN", booleanPreferencesKey("has_chosen_layout") to true))
            put(a, profile, "player_settings", mutablePreferencesOf(
                stringPreferencesKey("preferred_audio_language") to if (marker == "A") "en" else "ja",
                stringPreferencesKey("last_playback_diagnostics_json") to "https://example.invalid/$PRIVATE_MARKER"))
            put(a, profile, "trakt_settings", mutablePreferencesOf(
                stringPreferencesKey("watch_progress_source") to "NUVIO_SYNC",
                stringPreferencesKey("library_source_mode") to "LOCAL",
                intPreferencesKey("continue_watching_days_cap") to if (marker == "A") 60 else 90))
            val id = fixtureId(marker, profile)
            val library = JsonObject().apply {
                addProperty("id", id); addProperty("type", "series"); addProperty("name", profileName(marker, profile))
                addProperty("posterShape", "POSTER"); add("genres", JsonArray()); addProperty("addedAt", FIXTURE_TIME)
            }
            put(a, profile, "library_preferences", mutablePreferencesOf(stringSetPreferencesKey("library_items") to setOf(library.toString())))
            val watched = JsonObject().apply {
                addProperty("contentId", id); addProperty("contentType", "series"); addProperty("title", profileName(marker, profile))
                addProperty("season", 1); addProperty("episode", 1); addProperty("watchedAt", FIXTURE_TIME)
            }
            put(a, profile, "watched_items_preferences", mutablePreferencesOf(stringSetPreferencesKey("watched_items") to setOf(watched.toString())))
            val progress = JsonObject().apply {
                addProperty("contentId", id); addProperty("contentType", "series"); addProperty("name", profileName(marker, profile))
                addProperty("videoId", "$id:1:2"); addProperty("season", 1); addProperty("episode", 2)
                addProperty("position", if (marker == "A") 60_000L else 120_000L)
                addProperty("duration", 600_000L); addProperty("lastWatched", FIXTURE_TIME); addProperty("source", "local")
            }
            put(a, profile, "watch_progress_preferences", mutablePreferencesOf(intPreferencesKey("watch_progress_storage_version") to 1))
            put(a, profile, "watch_progress_recent_preferences", mutablePreferencesOf(stringPreferencesKey("watch_progress_map") to
                JsonObject().apply { add("$id:1:2", progress) }.toString()))
        }
    }

    private suspend fun verifySeed(a: Access, marker: String) {
        val profiles = a.profiles.profilesList.first()
        require(profiles.map { it.id }.toSet() == setOf(1, 2) && profiles.all { it.name == profileName(marker, it.id) })
        for (profile in 1..2) {
            val theme = a.factory.get(profile, "theme_settings").data.first()
            require(theme[booleanPreferencesKey("amoled_mode")] == (marker == "B"))
            val player = a.factory.get(profile, "player_settings").data.first()
            require(player[stringPreferencesKey("preferred_audio_language")] == if (marker == "A") "en" else "ja")
            if (marker == "A") require(player[stringPreferencesKey("last_playback_diagnostics_json")] == null)
            val tracking = a.factory.get(profile, "trakt_settings").data.first()
            require(tracking[stringPreferencesKey("watch_progress_source")] == "NUVIO_SYNC")
            require(tracking[stringPreferencesKey("library_source_mode")] == "LOCAL")
            val id = fixtureId(marker, profile)
            val library = a.factory.get(profile, "library_preferences").data.first()[stringSetPreferencesKey("library_items")].orEmpty()
            require(library.any { JsonParser.parseString(it).asJsonObject["id"].asString == id })
            val watched = a.factory.get(profile, "watched_items_preferences").data.first()[stringSetPreferencesKey("watched_items")].orEmpty()
            require(watched.any { JsonParser.parseString(it).asJsonObject["contentId"].asString == id })
            val raw = a.factory.get(profile, "watch_progress_recent_preferences").data.first()[stringPreferencesKey("watch_progress_map")]
            val progress = JsonParser.parseString(requireNotNull(raw)).asJsonObject["$id:1:2"].asJsonObject
            require(progress["position"].asLong == if (marker == "A") 60_000L else 120_000L)
        }
    }

    private suspend fun saveOriginalStores(a: Access, root: File) {
        savePreferences(File(root, "original/profile_settings.preferences_pb"), a.profileStore.data.first())
        for (profile in 1..2) for (feature in PortableBackupPolicy.features - "profile_settings") {
            savePreferences(File(root, "original/$feature-p$profile.preferences_pb"), a.factory.get(profile, feature).data.first())
        }
    }

    private suspend fun restoreOriginalStores(context: Context, a: Access, root: File) {
        // Restore through exact live instances; replacing open DataStore files is unsafe.
        for (feature in PortableBackupPolicy.features - "profile_settings") {
            val original = BackupRestoreBootstrap.readPreferences(File(root, "original/$feature-p1.preferences_pb"))
            a.factory.get(1, feature).updateData { original }
        }
        a.factory.clearProfile(2)
        val originalProfile = BackupRestoreBootstrap.readPreferences(File(root, "original/profile_settings.preferences_pb"))
        a.profileStore.updateData { originalProfile }
        require(a.profileStore.data.first() == originalProfile)
        // Slot 2 was unused on entry. All files with this exact scoped suffix belong to this fixture.
        File(context.filesDir, "datastore").listFiles()?.filter {
            it.name.endsWith("_p2.preferences_pb") || it.name.endsWith("_p2.preferences_pb.bak")
        }?.forEach { require(it.delete()) }
    }

    private suspend fun put(a: Access, profile: Int, feature: String, values: Preferences) {
        a.factory.get(profile, feature).updateData { values }
    }
    private suspend fun savePreferences(destination: File, preferences: Preferences) {
        val buffer = Buffer()
        PreferencesSerializer.writeTo(preferences, buffer)
        BackupRestoreBootstrap.atomicWrite(destination, buffer.readByteArray())
    }

    /** Conservative: refuse any persisted native credential/account record, even if disconnected. */
    private fun requireNoAccountBearingState(context: Context) {
        val app = context.applicationContext as NuvioApplication
        val auth = field<AuthManager>(app.startupSyncService, "authManager")
        require(!auth.isAuthenticated && auth.currentUserId == null) { "A cloud account is connected; fixture refused." }
        val secretKey = Regex("(?i)(?:.*(?:token|api_key|oauth_state|credentials|password|session).*)")
        File(context.filesDir, "datastore").listFiles()?.filter { it.name.endsWith(".preferences_pb") }?.forEach { file ->
            val values = BackupRestoreBootstrap.readPreferences(file).asMap()
            require(values.none { (key, value) -> secretKey.matches(key.name) && value.toString().isNotBlank() }) {
                "Account-bearing preferences found; fixture refused."
            }
        }
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles()?.filter { it.extension == "xml" }?.forEach { file ->
            file.inputStream().use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, "UTF-8")
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        val name = parser.getAttributeValue(null, "name").orEmpty()
                        require(!name.startsWith("account.p") && !name.matches(Regex("profile\\.[1-6]")) && !secretKey.matches(name)) {
                            "Persisted account record found; fixture refused."
                        }
                    }
                    parser.next()
                }
            }
        }
    }

    private fun isQemu(): Boolean {
        fun property(key: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("getprop $key"))
            .bufferedReader().use { it.readText().take(64).trim() }
        return (property("ro.kernel.qemu") == "1" || property("ro.boot.qemu") == "1") &&
            Build.HARDWARE in setOf("ranchu", "goldfish")
    }
    private fun fixtureRoot(context: Context) = File(context.noBackupFilesDir, "backup_roundtrip_fixture").apply { mkdirs() }
    private fun privateSentinel(context: Context) = File(context.applicationInfo.dataDir,
        "shared_prefs/backup_roundtrip_fixture_private.xml").apply { parentFile?.mkdirs() }
    private fun publicRegistrationHash(context: Context): String = File(context.applicationInfo.dataDir,
        "shared_prefs/syclop_trakt_app_registration.xml").takeIf(File::isFile)?.readBytes()?.let(::sha256) ?: "absent"
    private fun preparePublicRegistrationProbe(context: Context, root: File, ledger: JSONObject) {
        val file = File(context.applicationInfo.dataDir, "shared_prefs/syclop_trakt_app_registration.xml")
        ledger.put("registrationExisted", file.exists())
        val prefs = context.getSharedPreferences("syclop_trakt_app_registration", Context.MODE_PRIVATE)
        val original = JSONObject()
        prefs.all.forEach { (key, value) ->
            require(value is String) { "Unexpected public registration preference type." }
            original.put(key, value)
        }
        BackupRestoreBootstrap.atomicWrite(File(root, "original-public-registration.json"), original.toString().toByteArray())
        require(prefs.edit().putString("backup_roundtrip_public_probe", "synthetic-public-registration").commit())
        ledger.put("registrationHash", publicRegistrationHash(context))
    }
    private fun restorePublicRegistration(context: Context, root: File, ledger: JSONObject) {
        val original = JSONObject(File(root, "original-public-registration.json").readText())
        val prefs = context.getSharedPreferences("syclop_trakt_app_registration", Context.MODE_PRIVATE)
        val editor = prefs.edit().clear()
        original.keys().forEach { key -> editor.putString(key, original.getString(key)) }
        require(editor.commit())
        if (!ledger.getBoolean("registrationExisted")) require(context.deleteSharedPreferences("syclop_trakt_app_registration"))
        else require(prefs.all == original.keys().asSequence().associateWith(original::getString))
    }
    private fun profileName(marker: String, profile: Int) = "Backup fixture $marker $profile"
    private fun fixtureId(marker: String, profile: Int) = "backup_fixture_${marker}_$profile"
    private fun writeLedger(root: File, ledger: JSONObject) =
        BackupRestoreBootstrap.atomicWrite(File(root, "ledger.json"), ledger.toString().toByteArray())
    private fun readLedger(root: File, phase: String): JSONObject =
        JSONObject(File(root, "ledger.json").readText()).also { require(it.getString("nextPhase") == phase) }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            var count = input.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun safeClass(failure: Throwable) = failure.javaClass.simpleName
        .takeIf { it.matches(Regex("[A-Za-z0-9_$]{1,100}")) } ?: "Failure"
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as T

    private companion object {
        const val PHASE_ARGUMENT = "backupRoundTripPhase"
        const val FIXTURE_TIME = 1_800_000_000_000L
        const val PRIVATE_MARKER = "backup-fixture-private-marker"
        const val PRIVATE_A = "<map><string name=\"fixture_probe\">backup-fixture-private-A</string></map>"
        const val PRIVATE_B = "<map><string name=\"fixture_probe\">backup-fixture-private-B</string></map>"
    }
}
