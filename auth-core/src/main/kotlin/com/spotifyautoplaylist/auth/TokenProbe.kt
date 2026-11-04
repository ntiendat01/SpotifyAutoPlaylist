package com.spotifyautoplaylist.auth

import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class TokenProbeResult(val httpStatus: Int, val scopes: Set<String>, val expiresInSeconds: Int)

fun interface TokenExchange {
    suspend fun verify(input: TokenExchangeInput): TokenGrant
}

/** Keeps access token only until TASK-002A completes. Refresh token is never retained. */
class TokenProbe internal constructor(
    private val endpoint: HttpUrl,
    private val client: OkHttpClient,
) : TokenExchange {
    constructor() : this("https://accounts.spotify.com/api/token".toHttpUrl(), probeHttpClient())

    override suspend fun verify(input: TokenExchangeInput): TokenGrant {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", input.clientId)
            .add("code", input.code)
            .add("redirect_uri", SpotifyAuth.REDIRECT_URI)
            .add("code_verifier", input.verifier).build()
        val request = Request.Builder().url(endpoint).post(body).build()
        return client.probeJson(request, ProbeFailure.TOKEN_HTTP, ProbeFailure.TOKEN_RESPONSE,
            discard = TokenGrant::close,
        ) { json ->
            val tokenField = json["access_token"]?.jsonPrimitive
            val typeField = json["token_type"]?.jsonPrimitive
            val token = tokenField?.content
            val type = typeField?.content
            val expires = json["expires_in"]?.jsonPrimitive?.intOrNull
            if (tokenField?.isString != true || token.isNullOrBlank() ||
                token.any { it.code !in 33..126 } ||
                typeField?.isString != true || !type.equals("Bearer", ignoreCase = true) ||
                expires == null || expires <= 0
            ) throw ProbeException(ProbeFailure.TOKEN_RESPONSE)
            val scopes = json["scope"]?.jsonPrimitive?.content.orEmpty()
                .split(' ').filter(String::isNotBlank).toSet()
            if (!scopes.containsAll(SpotifyAuth.scopes)) throw ProbeException(ProbeFailure.INSUFFICIENT_SCOPE, 200)
            TokenGrant(TokenProbeResult(200, scopes, expires), token)
        }
    }
}
