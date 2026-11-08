package com.nuvio.tv.core.player

import java.io.IOException
import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SubtitleResponseLimitTest {
    @Test
    fun normalSubtitleRemainsByteIdentical() {
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray()
        assertArrayEquals(bytes, bytes.toResponseBody().readSubtitleBytes())
    }

    @Test
    fun declaredOversizeIsRejectedBeforeReading() {
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = Long.MAX_VALUE
            override fun source(): BufferedSource = error("Oversize body must not be read")
        }
        assertThrows(IOException::class.java) { body.readSubtitleBytes() }
    }

    @Test
    fun unknownLengthCannotReadBeyondTheLimit() {
        val source = Buffer().write(ByteArray(MAX_EXTERNAL_SUBTITLE_BYTES + 100))
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = source
        }
        assertThrows(IOException::class.java) { body.readSubtitleBytes() }
        assertEquals(99L, source.size)
    }

    @Test
    fun exactLimitIsAccepted() {
        val bytes = ByteArray(MAX_EXTERNAL_SUBTITLE_BYTES)
        assertEquals(bytes.size, bytes.toResponseBody().readSubtitleBytes().size)
    }
}
