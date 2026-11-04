package com.spotifyautoplaylist.auth

import java.io.Closeable

/** Short-lived M0 credential. Never put this object in UI state, saved state, storage or logs. */
class TokenGrant(val metadata: TokenProbeResult, accessToken: String) : Closeable {
    private var token: String? = accessToken

    @Synchronized
    internal fun requireAccessToken(): String = token ?: throw ProbeException(ProbeFailure.SESSION_EXPIRED)

    val isClosed: Boolean @Synchronized get() = token == null

    @Synchronized
    override fun close() { token = null }

    override fun toString() = "TokenGrant(<redacted>)"
}
