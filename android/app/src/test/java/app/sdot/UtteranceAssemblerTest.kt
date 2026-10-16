package app.sdot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceAssemblerTest {
    private var now = 0L
    private val assembler = UtteranceAssembler(gapMs = 1200) { now }

    @Test
    fun mergesSegmentsSeparatedByShortPauses() {
        assembler.segment("me puedes decir")
        now = 800
        assembler.segment("donde estamos trabajando ahora")
        now = 1900
        assertNull(assembler.takeIfDue())
        now = 2000
        assertEquals("me puedes decir donde estamos trabajando ahora", assembler.takeIfDue())
        assertFalse(assembler.hasPending())
    }

    @Test
    fun ongoingSpeechDelaysDelivery() {
        assembler.segment("hola")
        now = 1000
        assembler.partial("que tal")
        now = 2100
        assertNull(assembler.takeIfDue())
        assertEquals(100, assembler.msUntilDue())
        now = 2200
        assertEquals("hola", assembler.takeIfDue())
    }

    @Test
    fun partialAloneIsNotDeliveredWhileRecognizerIsWorking() {
        assembler.partial("estamos")
        now = 5000
        assertNull(assembler.takeIfDue())
        assertFalse(assembler.hasPending())
    }

    @Test
    fun keepsLatestPartialWhenSessionEndsWithoutResult() {
        assembler.partial("me puedes")
        assembler.partial("me puedes decir")
        assembler.endWithoutResult()
        assertTrue(assembler.hasPending())
        now = 1200
        assertEquals("me puedes decir", assembler.takeIfDue())
    }

    @Test
    fun blankFinalResultFallsBackToPartial() {
        assembler.partial("donde estamos")
        assembler.segment("  ")
        now = 1200
        assertEquals("donde estamos", assembler.takeIfDue())
    }

    @Test
    fun finalResultReplacesPartial() {
        assembler.partial("estamos dentro")
        assembler.segment("donde estamos trabajando")
        assembler.endWithoutResult()
        now = 1200
        assertEquals("donde estamos trabajando", assembler.takeIfDue())
    }

    @Test
    fun flushReturnsEverythingImmediatelyIncludingPartial() {
        assembler.segment("primero")
        assembler.partial("segundo")
        assertEquals("primero segundo", assembler.flush())
        assertNull(assembler.flush())
    }

    @Test
    fun clearDropsPendingSpeech() {
        assembler.segment("hola")
        assembler.partial("mundo")
        assembler.clear()
        assertNull(assembler.flush())
    }
}
