package com.nuvio.tv.core.sync.androidtv

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.MainActivity
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvCardLaunchAndroidTest {
    @Test
    fun androidKeystoreAuthenticatesSerializedLauncherCardsAndRejectsChangedPlayback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val progress = WatchProgress("tt12345", "series", "Fixture title", null, null, null,
            "tt12345:2:3", 2, 3, "Fixture episode", 5_000, 60_000, 1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val authenticator = activity.tvCardLaunches
                val serialized = authenticator.createIntent(context, progress).toUri(Intent.URI_INTENT_SCHEME)
                val restored = Intent.parseUri(serialized, Intent.URI_INTENT_SCHEME)
                assertTrue(authenticator.isCurrentCard(restored))
                restored.putExtra("videoId", "tt99999:1:1")
                assertFalse(authenticator.isCurrentCard(restored))
            }
        }
    }
}
