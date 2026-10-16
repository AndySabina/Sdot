package app.sdot

import java.util.Locale
import java.util.MissingResourceException

/** Plain description of a text-to-speech voice, so ranking is testable without Android. */
data class VoiceOption(
    val name: String,
    val locale: Locale,
    val quality: Int,
    val latency: Int,
    val networkRequired: Boolean,
    val installed: Boolean,
)

object Voices {
    // Same values as android.speech.tts.Voice.QUALITY_* and LATENCY_*.
    const val QUALITY_VERY_LOW = 100
    const val QUALITY_LOW = 200
    const val QUALITY_NORMAL = 300
    const val QUALITY_HIGH = 400
    const val QUALITY_VERY_HIGH = 500
    const val LATENCY_VERY_LOW = 100
    const val LATENCY_LOW = 200
    const val LATENCY_NORMAL = 300
    const val LATENCY_HIGH = 400
    const val LATENCY_VERY_HIGH = 500

    /**
     * Best offline, installed voice for [target]: exact locale first, then same language;
     * within that, highest quality, then lowest latency.
     */
    fun best(voices: List<VoiceOption>, target: Locale): VoiceOption? {
        val language = iso3Language(target)
        val country = iso3Country(target)
        return voices
            .filter { !it.networkRequired && it.installed && iso3Language(it.locale) == language }
            .sortedWith(
                compareByDescending<VoiceOption> { iso3Country(it.locale) == country }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
                    .thenBy { it.name },
            )
            .firstOrNull()
    }

    fun isLowQuality(voice: VoiceOption?): Boolean = voice == null || voice.quality <= QUALITY_NORMAL

    // Engines report both two- and three-letter codes ("es-ES" and "spa-ESP").
    private fun iso3Language(locale: Locale): String =
        try { locale.isO3Language } catch (e: MissingResourceException) { locale.language }.lowercase()

    private fun iso3Country(locale: Locale): String =
        try { locale.isO3Country } catch (e: MissingResourceException) { locale.country }.uppercase()
}
