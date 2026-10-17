package app.sdot

import org.junit.Assert.assertEquals
import org.junit.Test

class CallTimerTest {
    @Test
    fun startsAtZero() {
        assertEquals("00:00", CallTimer.format(0))
    }

    @Test
    fun showsMinutesAndSecondsUnderAnHour() {
        assertEquals("00:59", CallTimer.format(59))
        assertEquals("01:01", CallTimer.format(61))
        assertEquals("59:59", CallTimer.format(3599))
    }

    @Test
    fun addsHoursFromOneHour() {
        assertEquals("1:00:00", CallTimer.format(3600))
        assertEquals("2:03:04", CallTimer.format(2 * 3600 + 3 * 60 + 4))
    }

    @Test
    fun clampsNegativeToZero() {
        assertEquals("00:00", CallTimer.format(-5))
    }

    @Test
    fun elapsedSecondsCountsWholeSecondsSinceConnect() {
        assertEquals(0L, CallTimer.elapsedSeconds(startedAtMs = 1_000, nowMs = 1_999))
        assertEquals(61L, CallTimer.elapsedSeconds(startedAtMs = 1_000, nowMs = 62_000))
        assertEquals(0L, CallTimer.elapsedSeconds(startedAtMs = 5_000, nowMs = 1_000))
    }
}
