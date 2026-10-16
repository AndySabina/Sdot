package app.sdot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonTest {
    @Test
    fun parsesFlatObjectsFromTheExtension() {
        val obj = Json.parseObject("""{"busy":true,"session":"s 1","clients":2,"aborted":false,"x":null,"nested":{"a":[1,"b"]}}""")
        assertEquals(true, obj["busy"])
        assertEquals("s 1", obj["session"])
        assertEquals(2.0, obj["clients"])
        assertEquals(false, obj["aborted"])
        assertNull(obj["x"])
    }

    @Test
    fun decodesEscapes() {
        assertEquals("Say \"hi\"\n\u00e9 \uD83D\uDE00/", Json.parseObject("""{"text":"Say \"hi\"\n\u00e9 \ud83d\ude00\/"}""")["text"])
    }

    @Test
    fun quoteRoundTrips() {
        val text = "Line \"one\"\n\ttab \\ back \u0001 ñ"
        assertEquals(text, Json.parseObject("{\"text\":${Json.quote(text)}}")["text"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGarbage() {
        Json.parseObject("{\"a\":")
    }
}
