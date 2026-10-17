package app.sdot

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLabelTest {
    @Test
    fun fallsBackToPiWhenMissingOrBlank() {
        assertEquals("pi", SessionLabel.normalize(null))
        assertEquals("pi", SessionLabel.normalize(""))
        assertEquals("pi", SessionLabel.normalize("  \n\t "))
    }

    @Test
    fun trimsAndCollapsesWhitespace() {
        assertEquals("my project", SessionLabel.normalize("  my \n  project\t"))
    }

    @Test
    fun keepsNamesUpToTheLimit() {
        val exact = "a".repeat(SessionLabel.MAX_LENGTH)
        assertEquals(exact, SessionLabel.normalize(exact))
    }

    @Test
    fun capsLongNamesWithAnEllipsis() {
        val label = SessionLabel.normalize("x".repeat(100))
        assertEquals(SessionLabel.MAX_LENGTH, label.length)
        assertEquals("x".repeat(SessionLabel.MAX_LENGTH - 1) + "…", label)
    }

    @Test
    fun doesNotLeaveTrailingSpaceBeforeTheEllipsis() {
        val raw = "a".repeat(SessionLabel.MAX_LENGTH - 2) + " bcdef"
        assertEquals("a".repeat(SessionLabel.MAX_LENGTH - 2) + "…", SessionLabel.normalize(raw))
    }
}
