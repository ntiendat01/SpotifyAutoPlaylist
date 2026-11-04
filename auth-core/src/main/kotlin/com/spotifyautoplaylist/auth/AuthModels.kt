package com.spotifyautoplaylist.auth

enum class AuthFailure {
    NOT_CONFIGURED, PORT_IN_USE, CALLBACK_TIMEOUT, CANCELLED, BROWSER_UNAVAILABLE,
    AUTHORIZATION_DENIED, AUTHORIZATION_FAILED, NETWORK, TOKEN_HTTP, TOKEN_RESPONSE, INSUFFICIENT_SCOPE,
    PROFILE_HTTP, PROFILE_RESPONSE, ACCOUNT_MISMATCH, REAUTH_REQUIRED, STORAGE, QUOTA_EXCEEDED, UNEXPECTED,
    CONFIRMATION_REQUIRED, RESET_PENDING,
}

// Safe to display/log: never attach a raw exception, URL, response body or credentials.
class AuthException(
    val reason: AuthFailure,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Long? = null,
) : Exception(reason.name)

internal fun ProbeException.toAuth(): AuthException = AuthException(
    when (reason) {
        ProbeFailure.INVALID_CLIENT_ID, ProbeFailure.INVALID_OWNER_ID -> AuthFailure.NOT_CONFIGURED
        ProbeFailure.PORT_IN_USE -> AuthFailure.PORT_IN_USE
        ProbeFailure.CALLBACK_TIMEOUT -> AuthFailure.CALLBACK_TIMEOUT
        ProbeFailure.CANCELLED -> AuthFailure.CANCELLED
        ProbeFailure.BROWSER_UNAVAILABLE -> AuthFailure.BROWSER_UNAVAILABLE
        ProbeFailure.AUTHORIZATION_DENIED -> AuthFailure.AUTHORIZATION_DENIED
        ProbeFailure.AUTHORIZATION_FAILED -> AuthFailure.AUTHORIZATION_FAILED
        ProbeFailure.NETWORK -> AuthFailure.NETWORK
        ProbeFailure.TOKEN_HTTP -> AuthFailure.TOKEN_HTTP
        ProbeFailure.TOKEN_RESPONSE -> AuthFailure.TOKEN_RESPONSE
        ProbeFailure.INSUFFICIENT_SCOPE -> AuthFailure.INSUFFICIENT_SCOPE
        ProbeFailure.PROFILE_HTTP -> AuthFailure.PROFILE_HTTP
        ProbeFailure.PROFILE_RESPONSE -> AuthFailure.PROFILE_RESPONSE
        ProbeFailure.ACCOUNT_MISMATCH -> AuthFailure.ACCOUNT_MISMATCH
        ProbeFailure.SESSION_EXPIRED -> AuthFailure.REAUTH_REQUIRED
        ProbeFailure.QUOTA_EXCEEDED -> AuthFailure.QUOTA_EXCEEDED
        else -> AuthFailure.UNEXPECTED
    },
    httpStatus, retryAfterSeconds,
)

/** Public Client ID plus the Developer App owner's Spotify user ID (AUTH-006), from build config. */
class AuthConfig private constructor(val clientId: String, val expectedOwnerId: String) {
    override fun toString() = "AuthConfig(<redacted>)"

    companion object {
        /** Returns null for a build without Spotify configuration (e.g. CI); rejects partial values. */
        fun fromBuild(clientId: String, expectedOwnerId: String): AuthConfig? {
            if (clientId.isEmpty() && expectedOwnerId.isEmpty()) return null
            if (!SpotifyAuth.isValidClientId(clientId) || !OwnerIdentity.isValid(expectedOwnerId)) {
                throw AuthException(AuthFailure.NOT_CONFIGURED)
            }
            return AuthConfig(clientId, expectedOwnerId)
        }
    }
}

class AccessToken internal constructor(internal val value: String) {
    override fun toString() = "AccessToken(<redacted>)"
}

/** Persisted grant bound to one Client ID and one verified Spotify identity. */
class StoredCredentials internal constructor(
    val clientId: String,
    val spotifyUserId: String,
    val accountId: String,
    internal val accessToken: String,
    internal val refreshToken: String,
    val expiresAtEpochMillis: Long,
    val scopes: Set<String>,
) {
    internal fun refreshed(response: TokenResponse, nowMillis: Long) = StoredCredentials(
        clientId, spotifyUserId, accountId, response.accessToken,
        // Spotify may rotate the refresh token; keep the previous one only if none is returned.
        response.refreshToken ?: refreshToken,
        nowMillis + response.expiresInSeconds * 1_000L,
        response.scopes.ifEmpty { scopes },
    )

    override fun toString() = "StoredCredentials(<redacted>)"
}

class TokenResponse internal constructor(
    internal val accessToken: String,
    internal val refreshToken: String?,
    val expiresInSeconds: Int,
    val scopes: Set<String>,
) {
    override fun toString() = "TokenResponse(<redacted>)"
}

class VerifiedIdentity internal constructor(val spotifyUserId: String, val accountId: String) {
    val maskedUserId: String get() = OwnerIdentity.mask(spotifyUserId)
    override fun toString() = "VerifiedIdentity(<redacted>)"
}
