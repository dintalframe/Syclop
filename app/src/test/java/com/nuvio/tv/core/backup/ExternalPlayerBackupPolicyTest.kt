package com.nuvio.tv.core.backup

import com.google.gson.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ExternalPlayerBackupPolicyTest {
    @Test fun `public player package is allowed only as a canonical string`() {
        val key = "external_player_package"
        assertEquals("com.brouken.player", PortableBackupPolicy.sanitize("player_settings", key,
            PortableValue("string", JsonPrimitive("com.brouken.player")))?.value?.asString)
        assertNull(PortableBackupPolicy.sanitize("player_settings", key, PortableValue("boolean", JsonPrimitive(true))))
        for (bad in listOf("https://player/?token=private", "org.videolan.vlc/.PlayerActivity", "com.player?secret=x", "player")) {
            assertNull(PortableBackupPolicy.sanitize("player_settings", key, PortableValue("string", JsonPrimitive(bad))))
        }
    }
    @Test fun `per-profile external app preferences survive portable archive round trip`() {
        val stores = listOf(1 to "com.brouken.player", 2 to "org.videolan.vlc").map { (profile, player) ->
            PortableStore(profile, "player_settings", mapOf("external_player_package" to PortableValue("string", JsonPrimitive(player))))
        }
        val backup = PortableBackup(createdAt = "2026-10-24T00:00:00Z", sourceVersion = "0.1.0",
            profiles = listOf(PortableProfile(1, "One", "#123456"), PortableProfile(2, "Two", "#654321")), stores = stores)
        val restored = PortableBackupArchive.decode(ByteArrayInputStream(PortableBackupArchive.encode(backup)))
        assertEquals(stores, restored.stores)
    }
}
