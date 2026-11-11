package com.nuvio.tv.ui.screens.player

/** Tracks admitted horizontal key presses until their matching native releases. */
internal class PlayerSeekPresses {
    private val downTimes = mutableMapOf<Pair<Int, Int>, Long>()

    fun acceptDown(deviceId: Int, keyCode: Int, downTime: Long, repeatCount: Int): Boolean {
        val key = deviceId to keyCode
        if (repeatCount > 0) return downTimes[key] == downTime
        downTimes[key] = downTime
        return true
    }

    fun release(deviceId: Int, keyCode: Int, downTime: Long): Boolean {
        val key = deviceId to keyCode
        if (downTimes[key] != downTime) return false
        downTimes.remove(key)
        return true
    }
}
