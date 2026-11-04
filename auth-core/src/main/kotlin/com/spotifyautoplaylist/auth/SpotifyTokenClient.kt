package com.spotifyautoplaylist.auth

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

interface TokenEndpoint {
    /** Exchanges a one-time authorization code; never call twice with the same input. */
    suspend fun exchange(input: TokenExchangeInput): TokenResponse

    /** Throws [AuthFailure.REAUTH_REQUIRED] when Spotify rejects the refresh token itself. */
    suspend fun refresh(clientId: String, refreshToken: String): TokenResponse
}

class SpotifyTokenClient internal constructor(
    private val endpoint: HttpUrl,
    private val client: OkHttpClient,
) : TokenEndpoint {
    constructor() : this("https://accounts.spotify.com/api/token".toHttpUrl(), probeHttpClient())

    override suspend fun exchange(input: TokenExchangeInput): TokenResponse {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", input.clientId)
            .add("code", input.code)
            .add("redirect_uri", SpotifyAuth.REDIRECT_URI)
            .add("code_verifier", input.verifier).build()
        return post(body) { parse(it, initial = true) }
    }

    override suspend fun refresh(clientId: String, refreshToken: String): TokenResponse {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId).build()
        return try {
            post(body) { parse(it, initial = false) }
        } catch (failure: AuthException) {
            // 400 invalid_grant / 401 invalid_client: the stored grant can never succeed again.
            // 429, 5xx and network failures are transient and keep the stored grant.
            if (failure.reason == AuthFailure.TOKEN_HTTP && failure.httpStatus in setOf(400, 401)) {
                throw AuthException(AuthFailure.REAUTH_REQUIRED, failure.httpStatus)
            }
            throw failure
        }
    }

    private suspend fun post(body: FormBody, parse: (JsonObject) -> TokenResponse): TokenResponse {
        val request = Request.Builder().url(endpoint).header("Cache-Control", "no-store").post(body).build()
        return try {
            client.probeJson(request, ProbeFailure.TOKEN_HTTP, ProbeFailure.TOKEN_RESPONSE, parse = parse)
        } catch (failure: ProbeException) {
            throw failure.toAuth()
        }
    }

    private fun parse(json: JsonObject, initial: Boolean): TokenResponse {
        val type = json["token_type"]?.jsonPrimitive
        val expires = json["expires_in"]?.jsonPrimitive?.intOrNull
        val access = token(json, "access_token")
        val refresh = token(json, "refresh_token")
        if (type?.isString != true || !type.content.equals("Bearer", ignoreCase = true) ||
            access == null || expires == null || expires <= 0 || (initial && refresh == null) ||
            (json["refresh_token"] != null && refresh == null)
        ) throw ProbeException(ProbeFailure.TOKEN_RESPONSE)
        val scopeField = json["scope"]?.jsonPrimitive
        val scopes = scopeField?.content.orEmpty().split(' ').filter(String::isNotBlank).toSet()
        // A refresh response may omit scope (unchanged); an explicit narrower scope is a downgrade.
        if ((initial || scopeField != null) && !scopes.containsAll(SpotifyAuth.scopes)) {
            throw ProbeException(ProbeFailure.INSUFFICIENT_SCOPE, 200)
        }
        return TokenResponse(access, refresh, expires, scopes)
    }

    private fun token(json: JsonObject, name: String): String? {
        val field = json[name]?.jsonPrimitive ?: return null
        val value = field.content
        return value.takeIf { field.isString && it.isNotEmpty() && it.length <= 4_096 && it.all { c -> c.code in 33..126 } }
    }
}

fun interface IdentityVerifier {
    /** Requires `/me.id` to equal [expectedOwnerId] exactly and an immutable `account_id`. */
    suspend fun verify(accessToken: AccessToken, expectedOwnerId: String): VerifiedIdentity
}

class SpotifyIdentityClient internal constructor(
    private val endpoint: HttpUrl,
    private val client: OkHttpClient,
) : IdentityVerifier {
    constructor() : this("https://api.spotify.com/v1/me".toHttpUrl(), probeHttpClient())

    override suspend fun verify(accessToken: AccessToken, expectedOwnerId: String): VerifiedIdentity {
        val grant = TokenGrant(TokenProbeResult(200, emptySet(), Int.MAX_VALUE), accessToken.value)
        try {
            val checked = AccountProbe(endpoint, client).inspect(grant, expectedOwnerId)
            val accountId = checked.accountId ?: throw AuthException(AuthFailure.PROFILE_RESPONSE, 200)
            return VerifiedIdentity(expectedOwnerId, accountId)
        } catch (failure: ProbeException) {
            throw failure.toAuth()
        } finally {
            grant.close()
        }
    }
}
