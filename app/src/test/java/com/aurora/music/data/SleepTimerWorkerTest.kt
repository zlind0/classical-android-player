package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerWorkerTest {

    private fun worker() = SleepTimerWorker(
        nowElapsed = { 1_000_000L },
        nowWall = { 1_700_000_000_000L },
    )

    @Test
    fun setMinutes_armsTimer() {
        val w = worker()
        assertNull(w.timer.value)
        w.setMinutes(30)
        val t = w.timer.value
        assertNotNull(t)
        assertEquals(30, t!!.totalMinutes)
        assertNull(t.clockLabel)
    }

    @Test
    fun setAt_replacesCountdown() {
        val w = worker()
        w.setMinutes(30)
        w.setAt(7, 30)
        val t = w.timer.value
        assertNotNull(t)
        assertEquals(null, t!!.totalMinutes)
        assertEquals("07:30", t.clockLabel)
    }

    @Test
    fun setMinutes_replacesClock() {
        val w = worker()
        w.setAt(7, 30)
        w.setMinutes(10)
        assertEquals(10, w.timer.value!!.totalMinutes)
    }

    @Test
    fun cancel_clearsTimer() {
        val w = worker()
        w.setMinutes(30)
        w.cancel()
        assertNull(w.timer.value)
    }

    @Test
    fun setMinutes_zeroOrNegative_cancels() {
        val w = worker()
        w.setMinutes(30)
        w.setMinutes(0)
        assertNull(w.timer.value)
    }

    @Test
    fun consumeFired_clearsAndNotifies() {
        val w = worker()
        var fired = 0
        w.onFired = { fired++ }
        w.setMinutes(30)
        w.consumeFired()
        assertNull(w.timer.value)
        assertEquals(1, fired)
    }
}
