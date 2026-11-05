package com.spotifyautoplaylist.auth

import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

internal const val DUMMY_CLIENT_ID = "0123456789abcdef0123456789abcdef"

internal fun PkceSession.parameters(): Map<String, String> = authorizationUri.rawQuery.split('&').associate {
    val parts = it.split('=', limit = 2)
    URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
}

internal fun PkceSession.callback(code: String = "dummy-code"): String =
    "/callback?code=$code&state=${parameters().getValue("state")}"

class PkceSessionTest {
    @Test fun `S256 matches RFC 7636 example`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            SpotifyAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun `authorization requests exact redirect and only required scopes`() {
        val session = PkceSession.create(DUMMY_CLIENT_ID)
        val params = session.parameters()
        assertEquals("https", session.authorizationUri.scheme)
        assertEquals("accounts.spotify.com", session.authorizationUri.host)
        assertEquals("/authorize", session.authorizationUri.path)
        assertEquals(SpotifyAuth.REDIRECT_URI, params["redirect_uri"])
        assertEquals(SpotifyAuth.scopes, params.getValue("scope").split(' ').toSet())
        assertEquals("code", params["response_type"])
        assertEquals("S256", params["code_challenge_method"])
        assertFalse(params.containsKey("client_secret"))
        val input = (session.consumeCallback("GET", session.callback()) as CallbackDecision.Accepted).input
        assertTrue(input.verifier.matches(Regex("[A-Za-z0-9_-]{43,128}")))
        assertEquals(params["code_challenge"], SpotifyAuth.challenge(input.verifier))
    }

    @Test fun `every attempt uses a fresh state and challenge`() {
        val a = PkceSession.create(DUMMY_CLIENT_ID).parameters()
        val b = PkceSession.create(DUMMY_CLIENT_ID).parameters()
        assertNotEquals(a["state"], b["state"])
        assertNotEquals(a["code_challenge"], b["code_challenge"])
    }

    @Test fun `invalid client id is rejected before browser opens`() {
        for (value in listOf("", "not-a-client", "0".repeat(31), "g".repeat(32))) {
            val error = assertThrows(ProbeException::class.java) { PkceSession.create(value) }
            assertEquals(ProbeFailure.INVALID_CLIENT_ID, error.reason)
        }
    }

    @Test fun `valid callback yields one time code only once`() {
        val session = PkceSession.create(DUMMY_CLIENT_ID)
        val target = session.callback("dummy%2Bcode")
        val result = session.consumeCallback("GET", target) as CallbackDecision.Accepted
        assertEquals("dummy+code", result.input.code)
        assertEquals(CallbackDecision.Rejected(409), session.consumeCallback("GET", target))
    }

    @Test fun `wrong state cannot consume the legitimate attempt`() {
        val session = PkceSession.create(DUMMY_CLIENT_ID)
        assertEquals(CallbackDecision.Rejected(400),
            session.consumeCallback("GET", "/callback?code=dummy&state=wrong"))
        assertTrue(session.consumeCallback("GET", session.callback()) is CallbackDecision.Accepted)
    }

    @Test fun `missing state duplicate query and malformed query are rejected`() {
        for (query in listOf("code=dummy", "code=dummy&state=%ZZ", "state=x&state=y&code=dummy", "state")) {
            val session = PkceSession.create(DUMMY_CLIENT_ID)
            assertEquals(CallbackDecision.Rejected(400), session.consumeCallback("GET", "/callback?$query"))
        }
    }

    @Test fun `wrong method path absolute URI or fragment cannot consume callback`() {
        val session = PkceSession.create(DUMMY_CLIENT_ID)
        assertEquals(CallbackDecision.Rejected(405), session.consumeCallback("POST", session.callback()))
        for (target in listOf("/other", "http://127.0.0.1:8765${session.callback()}",
            "//attacker/callback", "${session.callback()}#fragment", "/%63allback?code=x&state=y")) {
            assertEquals(CallbackDecision.Rejected(404), session.consumeCallback("GET", target))
        }
    }

    @Test fun `code and error together or empty values are rejected`() {
        for (suffix in listOf("&error=access_denied", "&code=second")) {
            val session = PkceSession.create(DUMMY_CLIENT_ID)
            assertEquals(CallbackDecision.Rejected(400), session.consumeCallback("GET", session.callback() + suffix))
        }
        val session = PkceSession.create(DUMMY_CLIENT_ID)
        assertEquals(CallbackDecision.Rejected(400), session.consumeCallback("GET", session.callback("")))
    }

    @Test fun `denial validates state and terminates attempt`() {
        val session = PkceSession.create(DUMMY_CLIENT_ID)
        val target = "/callback?error=access_denied&state=${session.parameters().getValue("state")}"
        assertEquals(CallbackDecision.Failed(ProbeFailure.AUTHORIZATION_DENIED), session.consumeCallback("GET", target))
        assertEquals(CallbackDecision.Rejected(409), session.consumeCallback("GET", session.callback()))
    }

    @Test fun `callback expires at deadline using monotonic time`() {
        var now = 0L
        val session = PkceSession.create(DUMMY_CLIENT_ID, timeoutMillis = 100, nanoTime = { now })
        now = 100_000_000
        assertEquals(CallbackDecision.Failed(ProbeFailure.CALLBACK_TIMEOUT), session.consumeCallback("GET", session.callback()))
    }

    @Test fun `sensitive objects have redacted string representations`() {
        val session = PkceSession.create(DUMMY_CLIENT_ID)
        val accepted = session.consumeCallback("GET", session.callback("sensitive-dummy-code")) as CallbackDecision.Accepted
        for (text in listOf(session.toString(), accepted.toString(), accepted.input.toString())) {
            assertFalse(text.contains("sensitive-dummy-code"))
            assertFalse(text.contains(accepted.input.verifier))
            assertTrue(text.contains("redacted"))
        }
    }
}
