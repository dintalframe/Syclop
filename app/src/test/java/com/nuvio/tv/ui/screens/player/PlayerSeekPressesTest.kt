package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSeekPressesTest {
    @Test
    fun matchingPressRemainsOwnedUntilItsSingleRelease() {
        val presses = PlayerSeekPresses()
        assertFalse(presses.acceptDown(3, KEYCODE_DPAD_RIGHT, 100, 3))
        assertTrue(presses.acceptDown(3, KEYCODE_DPAD_RIGHT, 100, 0))

        assertFalse(presses.release(4, KEYCODE_DPAD_RIGHT, 100))
        assertFalse(presses.release(3, KEYCODE_DPAD_LEFT, 100))
        assertFalse(presses.release(3, KEYCODE_DPAD_RIGHT, 101))
        assertTrue(presses.acceptDown(3, KEYCODE_DPAD_RIGHT, 100, 3))
        assertTrue(presses.release(3, KEYCODE_DPAD_RIGHT, 100))
        assertFalse(presses.acceptDown(3, KEYCODE_DPAD_RIGHT, 100, 3))
        assertFalse(presses.release(3, KEYCODE_DPAD_RIGHT, 100))
    }

    @Test
    fun aFreshDownReplacesTheOldPressForThatDeviceAndKey() {
        val presses = PlayerSeekPresses()
        assertTrue(presses.acceptDown(3, KEYCODE_DPAD_RIGHT, 100, 0))
        assertTrue(presses.acceptDown(3, KEYCODE_DPAD_LEFT, 110, 0))
        assertTrue(presses.acceptDown(3, KEYCODE_DPAD_RIGHT, 120, 0))

        assertFalse(presses.acceptDown(3, KEYCODE_DPAD_RIGHT, 100, 3))
        assertFalse(presses.acceptDown(4, KEYCODE_DPAD_RIGHT, 120, 3))
        assertFalse(presses.release(3, KEYCODE_DPAD_RIGHT, 100))
        assertTrue(presses.release(3, KEYCODE_DPAD_LEFT, 110))
        assertTrue(presses.acceptDown(3, KEYCODE_DPAD_RIGHT, 120, 3))
        assertTrue(presses.release(3, KEYCODE_DPAD_RIGHT, 120))
    }
}
