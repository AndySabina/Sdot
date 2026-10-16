package app.sdot

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicesTest {
    private val spain = Locale.forLanguageTag("es-ES")

    private fun voice(
        name: String,
        tag: String = "es-ES",
        quality: Int = Voices.QUALITY_HIGH,
        latency: Int = Voices.LATENCY_NORMAL,
        network: Boolean = false,
        installed: Boolean = true,
        locale: Locale = Locale.forLanguageTag(tag),
    ) = VoiceOption(name, locale, quality, latency, network, installed)

    @Test
    fun prefersHighestQualityOfflineVoice() {
        val best = Voices.best(
            listOf(
                voice("normal", quality = Voices.QUALITY_NORMAL),
                voice("network", quality = Voices.QUALITY_VERY_HIGH, network = true),
                voice("high", quality = Voices.QUALITY_HIGH),
            ),
            spain,
        )
        assertEquals("high", best?.name)
    }

    @Test
    fun skipsVoicesThatAreNotInstalled() {
        val best = Voices.best(
            listOf(
                voice("missing", quality = Voices.QUALITY_VERY_HIGH, installed = false),
                voice("present", quality = Voices.QUALITY_LOW),
            ),
            spain,
        )
        assertEquals("present", best?.name)
    }

    @Test
    fun prefersExactLocaleThenSameLanguage() {
        val voices = listOf(
            voice("us", tag = "es-US", quality = Voices.QUALITY_VERY_HIGH),
            voice("es", tag = "es-ES", quality = Voices.QUALITY_NORMAL),
            voice("en", tag = "en-US", quality = Voices.QUALITY_VERY_HIGH),
        )
        assertEquals("es", Voices.best(voices, spain)?.name)
        assertEquals("us", Voices.best(voices, Locale.forLanguageTag("es-AR"))?.name)
    }

    @Test
    fun matchesThreeLetterLanguageCodes() {
        val best = Voices.best(listOf(voice("iso3", locale = Locale("spa", "ESP"))), Locale.forLanguageTag("es-ES"))
        assertEquals("iso3", best?.name)
    }

    @Test
    fun lowerLatencyBreaksTies() {
        val best = Voices.best(
            listOf(voice("slow", latency = Voices.LATENCY_HIGH), voice("fast", latency = Voices.LATENCY_LOW)),
            spain,
        )
        assertEquals("fast", best?.name)
    }

    @Test
    fun returnsNullWithoutUsableVoice() {
        assertNull(Voices.best(listOf(voice("en", tag = "en-US"), voice("net", network = true)), spain))
    }

    @Test
    fun flagsNormalQualityAndBelowAsLow() {
        assertTrue(Voices.isLowQuality(voice("n", quality = Voices.QUALITY_NORMAL)))
        assertTrue(Voices.isLowQuality(null))
        assertFalse(Voices.isLowQuality(voice("h", quality = Voices.QUALITY_HIGH)))
    }
}
