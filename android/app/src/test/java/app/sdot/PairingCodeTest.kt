package app.sdot

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PairingCodeTest {
    private val token = "Ab3_-xYz0123456789abcdefghijKLMNOPqrQ"

    private fun code(json: String): String =
        "sdot1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private fun assertRejected(input: String, reason: PairingException.Reason) {
        try {
            PairingCode.decode(input)
            fail("expected $reason for $input")
        } catch (e: PairingException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun decodesCodeProducedByTheExtension() {
        // Generated with extension/src/pairing.ts encodePairing(); the trailing slash was stripped there.
        val pairing = PairingCode.decode(
            "sdot1:eyJ1IjoiaHR0cHM6Ly9waG9uZS1icmlkZ2UuZXhhbXBsZS50cy5uZXQiLCJ0IjoiQWIzXy14WXowMTIzNDU2Nzg5YWJjZGVmZ2hpaktMTU5PUHFyUSJ9",
        )
        assertEquals("https://phone-bridge.example.ts.net", pairing.url)
        assertEquals(token, pairing.token)
        assertEquals("phone-bridge.example.ts.net", pairing.host)
    }

    @Test
    fun decodesExtensionCodeWithPortAndUnpaddedBase64() {
        val pairing = PairingCode.decode(
            "  sdot1:eyJ1IjoiaHR0cHM6Ly9leGFtcGxlLmNvbTo4NDQzIiwidCI6Ilp6Wnp0b2tlbldpdGhfZGFzaC1hbmRfdW5kZXJzY29yZV8wMDAwMDAifQ\n",
        )
        assertEquals("https://example.com:8443", pairing.url)
        assertEquals("ZzZztokenWith_dash-and_underscore_000000", pairing.token)
        assertEquals("example.com", pairing.host)
    }

    @Test
    fun roundTripsThroughEncode() {
        val original = Pairing("https://example.org/bridge", token)
        assertEquals(original, PairingCode.decode(PairingCode.encode(original)))
    }

    @Test
    fun toStringNeverRevealsTheToken() {
        assertEquals(false, Pairing("https://example.org", token).toString().contains(token))
    }

    @Test
    fun stripsTrailingSlashes() {
        assertEquals("https://example.org", PairingCode.decode(code("""{"u":"https://example.org//","t":"$token"}""")).url)
    }

    @Test
    fun rejectsPlainHttp() {
        assertRejected(code("""{"u":"http://example.org","t":"$token"}"""), PairingException.Reason.NOT_HTTPS)
    }

    @Test
    fun rejectsShortOrInvalidToken() {
        assertRejected(code("""{"u":"https://example.org","t":"short"}"""), PairingException.Reason.BAD_TOKEN)
        assertRejected(code("""{"u":"https://example.org","t":"${token}!"}"""), PairingException.Reason.BAD_TOKEN)
    }

    @Test
    fun rejectsMalformedCodes() {
        assertRejected("", PairingException.Reason.MALFORMED)
        assertRejected("sdot2:abc", PairingException.Reason.MALFORMED)
        assertRejected("sdot1:***", PairingException.Reason.MALFORMED)
        assertRejected("sdot1:" + Base64.getUrlEncoder().encodeToString("not json".toByteArray()), PairingException.Reason.MALFORMED)
        assertRejected(code("""{"u":"https://example.org"}"""), PairingException.Reason.MALFORMED)
        assertRejected(code("""{"u":"not a url","t":"$token"}"""), PairingException.Reason.MALFORMED)
    }
}
