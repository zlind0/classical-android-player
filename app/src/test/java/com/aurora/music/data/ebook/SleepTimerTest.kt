package com.aurora.music.data.ebook

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {

    private fun millis(h: Int, m: Int, s: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, s)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun laterToday() {
        assertEquals(30 * 60_000L, delayUntilNextClock(10, 30, millis(10, 0)))
    }

    @Test
    fun pastTime_meansTomorrow() {
        assertEquals(20 * 60_000L, delayUntilNextClock(0, 10, millis(23, 50)))
    }

    @Test
    fun exactNow_means24h() {
        assertEquals(24 * 60 * 60_000L, delayUntilNextClock(10, 0, millis(10, 0)))
    }

    @Test
    fun secondsTruncated() {
        assertEquals(29 * 60_000L + 30_000L, delayUntilNextClock(10, 30, millis(10, 0, 30)))
    }
}
