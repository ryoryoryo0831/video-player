package com.ryose.videoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun 時間の表示() {
        assertEquals("0:00", formatTime(0))
        assertEquals("1:05", formatTime(65_000))
        assertEquals("1:00:00", formatTime(3_600_000))
        assertEquals("0:00", formatTime(-5))
    }

    @Test
    fun 速度の表示() {
        assertEquals("1x", formatRate(1f))
        assertEquals("1.25x", formatRate(1.25f))
        assertEquals("0.5x", formatRate(0.5f))
        assertEquals("2x", formatRate(2.0000002f))
    }
}
