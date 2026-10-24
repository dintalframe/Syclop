package com.nuvio.tv.core.express

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against Android's ICU regex engine; the host JVM accepts some invalid ICU syntax. */
@RunWith(AndroidJUnit4::class)
class ExpressTemplateAndroidTest {
    @Test
    fun replacesExactMovieMetadataOnAndroid() {
        assertEquals(
            "Big Buck Bunny 2008 tt1254207",
            expressTemplate("{title} {year} {imdbId}", mapOf(
                "title" to "Big Buck Bunny", "year" to "2008", "imdbId" to "tt1254207"
            ))
        )
    }

    @Test
    fun missingAnimeAbsoluteNumberDoesNotCreateAQuery() {
        assertNull(expressTemplate("{title} {absoluteNumber}", mapOf("title" to "Verified anime")))
    }
}
