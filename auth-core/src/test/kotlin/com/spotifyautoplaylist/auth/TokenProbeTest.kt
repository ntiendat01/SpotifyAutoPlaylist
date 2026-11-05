package com.spotifyautoplaylist.auth

import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TokenProbeTest {
    private val input = TokenExchangeInput(DUMMY_CLIENT_ID, "dummy+code", "dummy-verifier")

    @Test fun `exchange sends verifier and exact redirect without secret then returns safe metadata`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(validBody()))
            val grant = probe(server).verify(input)
            val result = grant.use {
                assertEquals("dummy-access-token", it.requireAccessToken())
                assertFalse(it.toString().contains("dummy-access-token"))
                it.metadata
            }
            assertTrue(grant.isClosed)
            assertEquals(200, result.httpStatus)
            assertEquals(SpotifyAuth.scopes, result.scopes)
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/api/token", request.path)
            assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
            assertNull(request.getHeader("Authorization"))
            val fields = request.body.readUtf8().split('&').associate {
                val parts = it.split('=', limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            }
            assertEquals(mapOf("grant_type" to "authorization_code", "client_id" to DUMMY_CLIENT_ID,
                "code" to "dummy+code", "code_verifier" to "dummy-verifier", "redirect_uri" to SpotifyAuth.REDIRECT_URI), fields)
            assertFalse(result.toString().contains("dummy-access-token"))
            assertFalse(result.toString().contains("dummy-refresh-token"))
        }
    }

    @Test fun `HTTP errors never expose response body or retry one time code`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("sensitive-response-body"))
            val failure = failureOf { probe(server).verify(input) }
            assertEquals(ProbeFailure.TOKEN_HTTP, failure.reason)
            assertEquals(400, failure.httpStatus)
            assertFalse(failure.toString().contains("sensitive-response-body"))
            assertNull(failure.cause)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `token POST redirects are not followed`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/unexpected")))
            assertEquals(307, failureOf { probe(server).verify(input) }.httpStatus)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `malformed token responses are sanitized`() = runBlocking {
        for (body in listOf("not-json", "{}", "[]", validBody().replace("Bearer", "Unknown"),
            validBody().replace("3600", "0"), validBody().replace("dummy-access-token", ""),
            validBody().replace("dummy-access-token", "invalid token"),
            validBody().replace("\"dummy-access-token\"", "null"), "x".repeat(65_537))) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))
                val failure = failureOf { probe(server).verify(input) }
                assertEquals(ProbeFailure.TOKEN_RESPONSE, failure.reason)
                assertNull(failure.cause)
                assertEquals("TOKEN_RESPONSE", failure.message)
            }
        }
    }

    @Test fun `partial scopes cannot report successful probe`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(validBody().replace(SpotifyAuth.scopes.joinToString(" "), "user-read-recently-played")))
            assertEquals(ProbeFailure.INSUFFICIENT_SCOPE, failureOf { probe(server).verify(input) }.reason)
        }
    }

    private fun probe(server: MockWebServer) = TokenProbe(server.url("/api/token"),
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(3, TimeUnit.SECONDS).build())

    private fun validBody() = """{"access_token":"dummy-access-token","refresh_token":"dummy-refresh-token","token_type":"Bearer","expires_in":3600,"scope":"${SpotifyAuth.scopes.joinToString(" ")}"}"""

    private suspend fun failureOf(block: suspend () -> Unit): ProbeException {
        try { block() } catch (failure: ProbeException) { return failure }
        throw AssertionError("Expected safe probe failure")
    }
}
