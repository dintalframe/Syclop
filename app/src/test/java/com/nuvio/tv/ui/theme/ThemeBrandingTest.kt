package com.nuvio.tv.ui.theme

import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeBrandingTest {
    @Test
    fun everyThemeUsesTheSyclopWordmark() {
        AppTheme.entries.forEach { theme ->
            assertEquals(R.drawable.syclop_banner_wordmark, theme.brandWordmarkResource)
        }
    }
}
