package app.sdot

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test
    fun doublesFromOneSecondUpToFifteen() {
        val backoff = Backoff()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 15000L, 15000L), List(6) { backoff.next() })
    }

    @Test
    fun resetStartsOverAtTheInitialDelay() {
        val backoff = Backoff()
        repeat(4) { backoff.next() }
        backoff.reset()
        assertEquals(1000L, backoff.next())
    }

    @Test
    fun honoursCustomBounds() {
        val backoff = Backoff(initialMs = 500, maxMs = 1200)
        assertEquals(listOf(500L, 1000L, 1200L), List(3) { backoff.next() })
    }
}
