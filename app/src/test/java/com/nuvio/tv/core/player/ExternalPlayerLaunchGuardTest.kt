package com.nuvio.tv.core.player

import android.content.Context
import com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class ExternalPlayerLaunchGuardTest {
    @Test fun `untrusted URLs reject before installed-app discovery or payload construction`() {
        ConnectedServicePlaybackGrants.registry.clear()
        val context = mockk<Context>()
        for (url in listOf("https://cdn.example.test/video?token=synthetic", "magnet:?xt=urn:btih:synthetic", "torrent://synthetic")) {
            val failure = assertThrows(ExternalPlayerLaunchRejected::class.java) {
                ExternalPlayerIntentFactory.create(context, ExternalPlayerInput(url, playerPackage = "com.brouken.player"))
            }
            assertEquals(ExternalPlayerLaunchRejection.BLOCKED_STREAM, failure.reason)
            assertFalse(failure.message.orEmpty().contains("synthetic"))
        }
        verify { context wasNot Called }
    }
    @Test fun `blocked launch is a creation failure instead of a fake synchronous success`() {
        ConnectedServicePlaybackGrants.registry.clear()
        val context = mockk<Context>()
        val contract = ExternalPlayerResultContract()
        val input = ExternalPlayerInput("https://cdn.example.test/video?token=synthetic")
        assertNull(contract.getSynchronousResult(context, input))
        assertThrows(ExternalPlayerLaunchRejected::class.java) { contract.createIntent(context, input) }
        verify { context wasNot Called }
    }
}
