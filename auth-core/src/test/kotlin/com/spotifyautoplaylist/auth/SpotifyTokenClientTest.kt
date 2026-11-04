package com.spotifyautoplaylist.auth

import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class SpotifyTokenClientTest {
    private val input = TokenExchangeInput(DUMMY_CLIENT_ID, "dummy+code", "dummy-verifier")
    private val scopes = SpotifyAuth.scopes.joinToString(" ")

    @Test fun `exchange keeps refresh token and sends PKCE fields without secret`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body()))
            val response = client(server).exchange(input)
            assertEquals("dummy-access", response.accessToken)
            assertEquals("dummy-refresh", response.refreshToken)
            assertEquals(3600, response.expiresInSeconds)
            assertEquals(SpotifyAuth.scopes, response.scopes)
            assertFalse(response.toString().contains("dummy"))
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertNull(request.getHeader("Authorization"))
            assertEquals(mapOf("grant_type" to "authorization_code", "client_id" to DUMMY_CLIENT_ID,
                "code" to "dummy+code", "code_verifier" to "dummy-verifier",
                "redirect_uri" to SpotifyAuth.REDIRECT_URI), fields(request))
        }
    }

    @Test fun `exchange without refresh token or full scope is rejected`() = runBlocking {
        val cases = mapOf(
            body().replace(""","refresh_token":"dummy-refresh"""", "") to AuthFailure.TOKEN_RESPONSE,
            body(refresh = "") to AuthFailure.TOKEN_RESPONSE,
            body(scope = "user-read-recently-played") to AuthFailure.INSUFFICIENT_SCOPE,
            body().replace("Bearer", "Mac") to AuthFailure.TOKEN_RESPONSE,
            "not-json" to AuthFailure.TOKEN_RESPONSE,
        )
        for ((payload, expected) in cases) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(payload))
            assertEquals(expected, failureOf { client(server).exchange(input) }.reason)
        }
    }

    @Test fun `refresh sends refresh grant and accepts rotated or omitted refresh token`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body(access = "new-access", refresh = "rotated-refresh")))
            server.enqueue(MockResponse().setBody(
                """{"access_token":"newer-access","token_type":"Bearer","expires_in":3600}"""))
            val rotated = client(server).refresh(DUMMY_CLIENT_ID, "old-refresh")
            assertEquals("new-access", rotated.accessToken)
            assertEquals("rotated-refresh", rotated.refreshToken)
            assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "old-refresh",
                "client_id" to DUMMY_CLIENT_ID), fields(server.takeRequest(3, TimeUnit.SECONDS)!!))
            val unchanged = client(server).refresh(DUMMY_CLIENT_ID, "rotated-refresh")
            assertNull(unchanged.refreshToken)
            assertTrue(unchanged.scopes.isEmpty())
        }
    }

    @Test fun `refresh rejection requires login while transient failures keep grant`() = runBlocking {
        val cases = listOf(
            MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""") to AuthFailure.REAUTH_REQUIRED,
            MockResponse().setResponseCode(401) to AuthFailure.REAUTH_REQUIRED,
            MockResponse().setResponseCode(503).setBody("sensitive") to AuthFailure.TOKEN_HTTP,
            MockResponse().setResponseCode(429).addHeader("Retry-After", "7") to AuthFailure.TOKEN_HTTP,
            MockResponse().setBody(body(scope = "playlist-read-private")) to AuthFailure.INSUFFICIENT_SCOPE,
        )
        for ((response, expected) in cases) MockWebServer().use { server ->
            server.enqueue(response)
            val failure = failureOf { client(server).refresh(DUMMY_CLIENT_ID, "old-refresh") }
            assertEquals(expected, failure.reason)
            assertFalse(failure.toString().contains("sensitive"))
            assertNull(failure.cause)
            if (response.status.contains("429")) assertEquals(7L, failure.retryAfterSeconds)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `identity requires exact owner id and immutable account id`() = runBlocking {
        val token = AccessToken("dummy-access")
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"owner","account_id":"acct-1","email":"x"}"""))
            server.enqueue(MockResponse().setBody("""{"id":"owner"}"""))
            server.enqueue(MockResponse().setBody("""{"id":"Owner","account_id":"acct-1"}"""))
            val identity = SpotifyIdentityClient(server.url("/v1/me"), http()).verify(token, "owner")
            assertEquals("acct-1", identity.accountId)
            assertEquals("Bearer dummy-access", server.takeRequest().getHeader("Authorization"))
            assertFalse(identity.toString().contains("owner"))
            assertEquals(AuthFailure.PROFILE_RESPONSE,
                failureOf { SpotifyIdentityClient(server.url("/v1/me"), http()).verify(token, "owner") }.reason)
            assertEquals(AuthFailure.ACCOUNT_MISMATCH,
                failureOf { SpotifyIdentityClient(server.url("/v1/me"), http()).verify(token, "owner") }.reason)
        }
    }

    private fun client(server: MockWebServer) = SpotifyTokenClient(server.url("/api/token"), http())

    private fun http() = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(3, TimeUnit.SECONDS).build()

    private fun body(access: String = "dummy-access", refresh: String = "dummy-refresh", scope: String = scopes) =
        """{"access_token":"$access","refresh_token":"$refresh","token_type":"Bearer","expires_in":3600,"scope":"$scope"}"""

    private fun fields(request: RecordedRequest) = request.body.readUtf8().split('&').associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
    }

    private suspend fun failureOf(block: suspend () -> Unit): AuthException {
        try { block() } catch (failure: AuthException) { return failure }
        throw AssertionError("Expected safe auth failure")
    }
}
