package com.spotifyautoplaylist.auth

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object SpotifyAuth {
    const val PORT = 8765
    const val REDIRECT_URI = "http://127.0.0.1:8765/callback"
    const val TIMEOUT_MILLIS = 180_000L
    val scopes: Set<String> = linkedSetOf(
        "user-read-recently-played", "playlist-modify-private", "playlist-read-private",
    )

    fun isValidClientId(value: String): Boolean = value.matches(Regex("[0-9a-fA-F]{32}"))

    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(UTF_8)))
}

class TokenExchangeInput internal constructor(
    internal val clientId: String,
    internal val code: String,
    internal val verifier: String,
) {
    override fun toString() = "TokenExchangeInput(<redacted>)"
}

sealed interface CallbackDecision {
    data class Rejected(val httpStatus: Int) : CallbackDecision
    data class Failed(val reason: ProbeFailure) : CallbackDecision
    class Accepted(val input: TokenExchangeInput) : CallbackDecision {
        override fun toString() = "Accepted(<redacted>)"
    }
}

class PkceSession private constructor(
    private val clientId: String,
    private val verifier: String,
    private val state: String,
    private val deadlineNanos: Long,
    private val nanoTime: () -> Long,
) {
    private var consumed = false

    val authorizationUri: URI
        get() {
            val parameters = linkedMapOf(
                "client_id" to clientId,
                "response_type" to "code",
                "redirect_uri" to SpotifyAuth.REDIRECT_URI,
                "scope" to SpotifyAuth.scopes.joinToString(" "),
                "state" to state,
                "code_challenge_method" to "S256",
                "code_challenge" to SpotifyAuth.challenge(verifier),
            )
            return URI("https://accounts.spotify.com/authorize?" + parameters.entries.joinToString("&") {
                "${encode(it.key)}=${encode(it.value)}"
            })
        }

    fun remainingMillis(): Long = ((deadlineNanos - nanoTime()) / 1_000_000).coerceAtLeast(0)

    @Synchronized
    fun consumeCallback(method: String, target: String): CallbackDecision {
        if (consumed) return CallbackDecision.Rejected(409)
        if (remainingMillis() == 0L) {
            consumed = true
            return CallbackDecision.Failed(ProbeFailure.CALLBACK_TIMEOUT)
        }
        if (method != "GET") return CallbackDecision.Rejected(405)
        val uri = try { URI(target) } catch (_: Exception) {
            return CallbackDecision.Rejected(400)
        }
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawFragment != null ||
            uri.rawPath != "/callback"
        ) return CallbackDecision.Rejected(404)
        val parameters = try { parseQuery(uri.rawQuery ?: "") } catch (_: Exception) {
            return CallbackDecision.Rejected(400)
        }
        val returnedState = parameters["state"] ?: return CallbackDecision.Rejected(400)
        if (!MessageDigest.isEqual(state.toByteArray(UTF_8), returnedState.toByteArray(UTF_8))) {
            return CallbackDecision.Rejected(400)
        }
        val code = parameters["code"]
        val error = parameters["error"]
        if ((code == null) == (error == null) || code?.isBlank() == true || error?.isBlank() == true) {
            return CallbackDecision.Rejected(400)
        }
        consumed = true
        return if (error != null) CallbackDecision.Failed(
            if (error == "access_denied") ProbeFailure.AUTHORIZATION_DENIED else ProbeFailure.AUTHORIZATION_FAILED,
        ) else CallbackDecision.Accepted(TokenExchangeInput(clientId, code!!, verifier))
    }

    override fun toString() = "PkceSession(<redacted>)"

    companion object {
        fun create(
            clientId: String,
            timeoutMillis: Long = SpotifyAuth.TIMEOUT_MILLIS,
            nanoTime: () -> Long = System::nanoTime,
        ): PkceSession {
            if (!SpotifyAuth.isValidClientId(clientId)) throw ProbeException(ProbeFailure.INVALID_CLIENT_ID)
            require(timeoutMillis in 1..SpotifyAuth.TIMEOUT_MILLIS)
            val random = SecureRandom()
            fun randomString(size: Int): String = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(size).also(random::nextBytes))
            return PkceSession(clientId, randomString(64), randomString(32),
                nanoTime() + timeoutMillis * 1_000_000, nanoTime)
        }

        private fun encode(value: String) = URLEncoder.encode(value, UTF_8.name())

        private fun parseQuery(query: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            for (pair in query.split('&')) {
                val parts = pair.split('=', limit = 2)
                require(parts.size == 2)
                val key = URLDecoder.decode(parts[0], UTF_8.name())
                val value = URLDecoder.decode(parts[1], UTF_8.name())
                require(result.put(key, value) == null)
            }
            return result
        }
    }
}
