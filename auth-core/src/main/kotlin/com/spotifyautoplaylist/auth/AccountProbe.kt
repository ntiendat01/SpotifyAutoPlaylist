package com.spotifyautoplaylist.auth

import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

object OwnerIdentity {
    // Spotify IDs are opaque. Do not lowercase, fuzzy-match display names or infer from email.
    fun isValid(value: String): Boolean = value.length in 1..256 &&
        value.none { it.isWhitespace() || it.isISOControl() }

    internal fun mask(value: String): String = "••••" + if (value.length > 4) value.takeLast(4) else ""
}

data class AccountProbeResult(val httpStatus: Int, val maskedUserId: String)

fun interface AccountVerification {
    suspend fun verify(grant: TokenGrant, expectedOwnerId: String): AccountProbeResult
}

class AccountProbe internal constructor(
    private val endpoint: HttpUrl,
    private val client: OkHttpClient,
) : AccountVerification {
    constructor() : this("https://api.spotify.com/v1/me".toHttpUrl(), probeHttpClient())

    override suspend fun verify(grant: TokenGrant, expectedOwnerId: String): AccountProbeResult {
        try {
            if (!OwnerIdentity.isValid(expectedOwnerId)) throw ProbeException(ProbeFailure.INVALID_OWNER_ID)
            val request = Request.Builder().url(endpoint)
                .header("Authorization", "Bearer ${grant.requireAccessToken()}")
                .header("Cache-Control", "no-store").get().build()
            return client.probeJson(request, ProbeFailure.PROFILE_HTTP, ProbeFailure.PROFILE_RESPONSE) { json ->
                val idField = json["id"]?.jsonPrimitive
                val id = idField?.content
                if (idField?.isString != true || id == null || !OwnerIdentity.isValid(id)) {
                    throw ProbeException(ProbeFailure.PROFILE_RESPONSE, 200)
                }
                if (id != expectedOwnerId) throw ProbeException(ProbeFailure.ACCOUNT_MISMATCH, 200)
                // Discard all other profile fields, including email, display_name and product.
                AccountProbeResult(200, OwnerIdentity.mask(id))
            }
        } finally {
            grant.close()
        }
    }
}
