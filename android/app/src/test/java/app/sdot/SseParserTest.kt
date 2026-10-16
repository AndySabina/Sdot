package app.sdot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    @Test
    fun parsesTheExtensionStreamPrelude() {
        val parser = SseParser()
        val events = parser.feed("retry: 3000\n\nevent: hello\ndata: {\"busy\":false,\"clients\":1}\n\n")
        assertEquals(listOf(SseEvent("hello", "{\"busy\":false,\"clients\":1}")), events)
        assertEquals(3000L, parser.retryMs)
    }

    @Test
    fun returnsMultipleEventsFromOneChunk() {
        val events = SseParser().feed(
            "event: speech\ndata: {\"text\":\"One.\"}\n\nevent: activity\ndata: {\"tool\":\"bash\"}\n\nevent: idle\ndata: {\"aborted\":false}\n\n",
        )
        assertEquals(listOf("speech", "activity", "idle"), events.map { it.type })
        assertEquals("{\"tool\":\"bash\"}", events[1].data)
    }

    @Test
    fun handlesBlocksSplitAcrossReads() {
        val parser = SseParser()
        val stream = "event: speech\ndata: {\"text\":\"Split here.\"}\n\nevent: busy\ndata: {}\n\n"
        val events = stream.chunked(3).flatMap { parser.feed(it) }
        assertEquals(listOf(SseEvent("speech", "{\"text\":\"Split here.\"}"), SseEvent("busy", "{}")), events)
    }

    @Test
    fun emitsNothingUntilTheBlankLineArrives() {
        val parser = SseParser()
        assertTrue(parser.feed("event: speech\ndata: {\"text\":\"x\"}\n").isEmpty())
        assertEquals(1, parser.feed("\n").size)
    }

    @Test
    fun ignoresCommentsAndKeepalives() {
        val parser = SseParser()
        assertTrue(parser.feed(": ping\n\n: ping\n\n").isEmpty())
        assertEquals(listOf(SseEvent("busy", "{}")), parser.feed("event: busy\n: inline comment\ndata: {}\n\n"))
    }

    @Test
    fun supportsCrLfAndCrSplitAcrossChunks() {
        val parser = SseParser()
        val events = parser.feed("event: busy\r") + parser.feed("\ndata: {}\r") + parser.feed("\n\r\n")
        assertEquals(listOf(SseEvent("busy", "{}")), events)
    }

    @Test
    fun joinsMultiLineDataAndDefaultsTypeToMessage() {
        assertEquals(listOf(SseEvent("message", "a\nb")), SseParser().feed("data: a\ndata:b\n\n"))
    }

    @Test
    fun ignoresInvalidRetryAndEmptyBlocks() {
        val parser = SseParser()
        assertTrue(parser.feed("retry: soon\n\nevent: idle\n\n").isEmpty())
        assertNull(parser.retryMs)
    }

    @Test
    fun resetDropsPartialState() {
        val parser = SseParser()
        parser.feed("event: speech\ndata: {\"text\":\"lost\"}\n")
        parser.reset()
        assertEquals(listOf(SseEvent("busy", "{}")), parser.feed("event: busy\ndata: {}\n\n"))
    }
}
