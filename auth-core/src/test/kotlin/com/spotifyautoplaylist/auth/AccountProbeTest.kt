package com.spotifyautoplaylist.auth

import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AccountProbeTest {
    @Test fun `minimal me response verifies exact owner id with bearer token and no extra scopes`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"dummy-owner"}"""))
            val grant = grant()
            val result = probe(server).verify(grant, "dummy-owner")
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/v1/me", request.path)
            assertEquals("Bearer dummy-access-token", request.getHeader("Authorization"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
            assertEquals(0, request.bodySize)
            assertEquals(AccountProbeResult(200, "••••wner"), result)
            assertFalse(result.toString().contains("dummy-owner"))
            assertFalse(result.toString().contains("dummy-access-token"))
            assertTrue(grant.isClosed)
            assertEquals(3, grant.metadata.scopes.size)
        }
    }

    @Test fun `wrong account is rejected even when display name email or account id matches configured username`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"other-user","account_id":"dummy-owner","display_name":"dummy-owner","email":"dummy-owner","product":"premium"}"""))
            val grant = grant()
            val failure = failureOf { probe(server).verify(grant, "dummy-owner") }
            assertEquals(ProbeFailure.ACCOUNT_MISMATCH, failure.reason)
            assertEquals(200, failure.httpStatus)
            assertFalse(failure.toString().contains("other-user"))
            assertFalse(failure.toString().contains("dummy-owner"))
            assertTrue(grant.isClosed)
            assertEquals(1, server.requestCount) // no history/playlist call or retry
        }
    }

    @Test fun `owner id comparison is case sensitive`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"Dummy-Owner"}"""))
            assertEquals(ProbeFailure.ACCOUNT_MISMATCH,
                failureOf { probe(server).verify(grant(), "dummy-owner") }.reason)
        }
    }

    @Test fun `missing expected owner fails before any HTTP and discards token`() = runBlocking {
        MockWebServer().use { server ->
            val grant = grant()
            assertEquals(ProbeFailure.INVALID_OWNER_ID, failureOf { probe(server).verify(grant, "") }.reason)
            assertEquals(0, server.requestCount)
            assertTrue(grant.isClosed)
        }
    }

    @Test fun `closed grant cannot request profile`() = runBlocking {
        MockWebServer().use { server ->
            val grant = grant().also(TokenGrant::close)
            assertEquals(ProbeFailure.SESSION_EXPIRED, failureOf { probe(server).verify(grant, "dummy-owner") }.reason)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `missing malformed null numeric or blank id cannot verify and never exposes raw profile`() = runBlocking {
        for (body in listOf("not-json", "[]", "{}", """{"id":null}""", """{"id":123}""",
            """{"id":""}""", """{"id":"bad\nid"}""", """{"display_name":"dummy-owner"}""", "x".repeat(65_537))) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))
                val grant = grant()
                val failure = failureOf { probe(server).verify(grant, "dummy-owner") }
                assertEquals(ProbeFailure.PROFILE_RESPONSE, failure.reason)
                assertEquals("PROFILE_RESPONSE", failure.message)
                assertNull(failure.cause)
                assertTrue(grant.isClosed)
            }
        }
    }

    @Test fun `profile HTTP errors stop without refresh or retry and discard token`() = runBlocking {
        for (status in listOf(201, 400, 401, 403, 404, 429, 500)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("private-response")
                    .addHeader("Retry-After", "45"))
                val grant = grant()
                val failure = failureOf { probe(server).verify(grant, "dummy-owner") }
                assertEquals(ProbeFailure.PROFILE_HTTP, failure.reason)
                assertEquals(status, failure.httpStatus)
                assertEquals(if (status == 429) 45L else null, failure.retryAfterSeconds)
                assertFalse(failure.toString().contains("private-response"))
                assertNull(failure.cause)
                assertEquals(1, server.requestCount)
                assertTrue(grant.isClosed)
            }
        }
    }

    @Test fun `profile redirect does not forward bearer token`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/unexpected")))
            assertEquals(302, failureOf { probe(server).verify(grant(), "dummy-owner") }.httpStatus)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `very short user id is fully masked`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"abc"}"""))
            assertEquals("••••", probe(server).verify(grant(), "abc").maskedUserId)
        }
    }

    @Test fun `retry after parses delta or HTTP date and rejects invalid values`() {
        val now = Instant.parse("2026-11-04T00:00:00.500Z")
        assertEquals(45L, retryAfterSeconds("45", now))
        assertEquals(60L, retryAfterSeconds("Wed, 4 Nov 2026 00:01:00 GMT", now))
        for (header in listOf(null, "-1", "invalid", "999999999999999999999")) {
            assertNull(retryAfterSeconds(header, now))
        }
    }

    @Test fun `token grant is redacted and closes idempotently`() {
        val grant = grant()
        assertFalse(grant.toString().contains("dummy-access-token"))
        grant.close()
        grant.close()
        assertTrue(grant.isClosed)
        assertEquals(ProbeFailure.SESSION_EXPIRED,
            assertThrows(ProbeException::class.java) { grant.requireAccessToken() }.reason)
    }

    private fun grant() = TokenGrant(TokenProbeResult(200, SpotifyAuth.scopes, 3600), "dummy-access-token")
    private fun probe(server: MockWebServer) = AccountProbe(server.url("/v1/me"),
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(3, TimeUnit.SECONDS).build())

    private suspend fun failureOf(block: suspend () -> Unit): ProbeException {
        try { block() } catch (failure: ProbeException) { return failure }
        throw AssertionError("Expected safe account failure")
    }
}
