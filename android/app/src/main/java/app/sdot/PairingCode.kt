package app.sdot

import java.net.URI
import java.util.Base64

/** Connection details for the pi extension: its HTTPS base URL and bearer token. */
data class Pairing(val url: String, val token: String) {
    val host: String get() = URI(url).host ?: url

    /** Never expose the token through logs or crash reports. */
    override fun toString() = "Pairing(url=$url)"
}

class PairingException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { MALFORMED, NOT_HTTPS, BAD_TOKEN }
}

/**
 * Pairing code shown by `/call` in pi and pasted once into the app.
 * Format (mirrors extension/src/pairing.ts): `sdot1:` + base64url(JSON {u: url, t: token}).
 */
object PairingCode {
    private const val PREFIX = "sdot1:"
    private val TOKEN = Regex("^[A-Za-z0-9_-]{32,}$")

    fun decode(code: String): Pairing {
        val trimmed = code.trim()
        if (!trimmed.startsWith(PREFIX)) throw PairingException(PairingException.Reason.MALFORMED)
        val raw = try {
            val json = String(Base64.getUrlDecoder().decode(trimmed.substring(PREFIX.length)), Charsets.UTF_8)
            Json.parseObject(json)
        } catch (e: IllegalArgumentException) {
            throw PairingException(PairingException.Reason.MALFORMED)
        }
        val url = raw["u"] as? String ?: throw PairingException(PairingException.Reason.MALFORMED)
        val token = raw["t"] as? String ?: throw PairingException(PairingException.Reason.MALFORMED)
        return validate(Pairing(url, token))
    }

    fun encode(pairing: Pairing): String {
        val valid = validate(pairing)
        val json = "{\"u\":${Json.quote(valid.url)},\"t\":${Json.quote(valid.token)}}"
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    private fun validate(pairing: Pairing): Pairing {
        val uri = try {
            URI(pairing.url)
        } catch (e: Exception) {
            throw PairingException(PairingException.Reason.MALFORMED)
        }
        if (uri.scheme == null || uri.host == null) throw PairingException(PairingException.Reason.MALFORMED)
        if (!uri.scheme.equals("https", ignoreCase = true)) throw PairingException(PairingException.Reason.NOT_HTTPS)
        if (!TOKEN.matches(pairing.token)) throw PairingException(PairingException.Reason.BAD_TOKEN)
        return Pairing(pairing.url.trimEnd('/'), pairing.token)
    }
}
