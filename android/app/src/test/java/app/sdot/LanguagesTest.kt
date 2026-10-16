package app.sdot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguagesTest {
    @Test
    fun prefersExactTagIgnoringCase() {
        assertEquals("es-us", Languages.match("es-US", listOf("es-ES", "es-us")))
    }

    @Test
    fun fallsBackToSameLanguage() {
        assertEquals("es-ES", Languages.match("es-419", listOf("en-US", "es-ES")))
    }

    @Test
    fun returnsNullWhenNoCandidateShareTheLanguage() {
        assertNull(Languages.match("fr-FR", listOf("en-US", "es-ES")))
    }
}
