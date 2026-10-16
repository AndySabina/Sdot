package app.sdot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test
    fun comparesNumericallyNotLexically() {
        assertTrue(Versions.compare("0.10.0", "0.9.9") > 0)
        assertTrue(Versions.compare("1.2.3", "1.2.10") < 0)
        assertTrue(Versions.compare("2.0.0", "10.0.0") < 0)
    }

    @Test
    fun ignoresLeadingVAndMissingParts() {
        assertEquals(0, Versions.compare("v1.2.0", "1.2.0"))
        assertEquals(0, Versions.compare("V1.2", "1.2.0"))
        assertEquals(0, Versions.compare("1", "v1.0.0"))
        assertTrue(Versions.compare("v1.2.1", "1.2") > 0)
    }

    @Test
    fun ignoresPreReleaseAndBuildSuffixes() {
        assertEquals(0, Versions.compare("1.2.0-beta.1", "1.2.0"))
        assertEquals(0, Versions.compare("1.2.0+build.7", "v1.2.0"))
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(Versions.parse(""))
        assertNull(Versions.parse("v"))
        assertNull(Versions.parse("latest"))
        assertNull(Versions.parse("1..2"))
        assertNull(Versions.parse("1.2.3.4"))
        assertNull(Versions.parse("1.-2.0"))
        assertNull(Versions.parse("1.100.0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun compareThrowsOnGarbage() {
        Versions.compare("nightly", "1.0.0")
    }

    @Test
    fun versionCodeMatchesTheGradleFormula() {
        assertEquals(10000 * 1 + 100 * 2 + 3, Versions.code("v1.2.3"))
        assertEquals(1000, Versions.code("0.10.0"))
        assertTrue(Versions.code("0.10.0") > Versions.code("0.9.99"))
    }
}
