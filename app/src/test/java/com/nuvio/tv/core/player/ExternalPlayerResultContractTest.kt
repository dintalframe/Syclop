package com.nuvio.tv.core.player

import android.app.Activity
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class ExternalPlayerResultContractTest {
    private val contract = ExternalPlayerResultContract()

    private fun resultIntent(actionValue: String?, values: Map<String, Any> = emptyMap()): Intent {
        val result = mockk<Intent>()
        every { result.action } returns actionValue
        every { result.getLongExtra(any(), -1L) } answers { values[firstArg<String>()] as? Long ?: -1L }
        every { result.getIntExtra(any(), -1) } answers { values[firstArg<String>()] as? Int ?: -1 }
        every { result.getStringExtra("end_by") } returns (values["end_by"] as? String)
        return result
    }

    @Test fun `contract reads VLC Long output keys without reading returned media URI or extras bundle`() {
        val data = resultIntent("org.videolan.vlc.player.result", mapOf(
            "extra_position" to 1_234_567L, "extra_duration" to 7_654_321L))
        val result = contract.parseResult(Activity.RESULT_CANCELED, data)!!
        assertEquals(1_234_567L, result.positionMs)
        assertEquals(7_654_321L, result.durationMs)
        assertTrue(result.endedByUser)
        verify(exactly = 0) { data.data }
        verify(exactly = 0) { data.extras }
    }

    @Test fun `contract falls back to Just Player Int millisecond fields`() {
        val data = resultIntent("com.mxtech.intent.result.VIEW", mapOf(
            "position" to 90_123, "duration" to 5_000_000, "end_by" to "user"))
        val result = contract.parseResult(Activity.RESULT_OK, data)!!
        assertEquals(90_123L, result.positionMs)
        assertEquals(5_000_000L, result.durationMs)
        assertTrue(result.endedByUser)
    }

    @Test fun `contract preserves Just Player completion without position or duration`() {
        val result = contract.parseResult(Activity.RESULT_OK,
            resultIntent("com.mxtech.intent.result.VIEW", mapOf("end_by" to "playback_completion")))!!
        assertEquals(0L, result.positionMs)
        assertNull(result.durationMs)
        assertFalse(result.endedByUser)
    }

    @Test fun `null or generic empty canceled chooser result cannot fabricate playback progress`() {
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, null))
        assertNull(contract.parseResult(Activity.RESULT_OK, resultIntent("android.intent.action.CHOOSER")))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, resultIntent("is.xyz.mpv.MPVActivity.result")))
    }
}
