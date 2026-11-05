package com.spotifyautoplaylist.auth

import java.io.Closeable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object SpotifyResourceId {
    fun isId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9]{22}"))

    fun parse(value: String, type: String): String? {
        val input = value.trim()
        if (isId(input)) return input
        val uriId = input.removePrefix("spotify:$type:")
        if (uriId != input && isId(uriId)) return uriId
        val url = input.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.host != "open.spotify.com" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.pathSegments.size != 2 || url.pathSegments[0] != type
        ) return null
        return url.pathSegments[1].takeIf(::isId)
    }
}

data class RecentSmokeResult(val received: Int, val valid: Int, val filtered: Int, val httpStatus: Int = 200)
enum class SmokeOutcome { PLAYLIST_READY, PRESENT, ALREADY_PRESENT, ABSENT_RETRY_READY, REMOVED_EXTERNALLY }
data class SmokeReport(val outcome: SmokeOutcome, val pages: Int = 0, val postStatus: Int? = null)

data class SmokeView(
    val playlistUrl: String? = null,
    val maskedTrackId: String? = null,
    val writeState: SmokeWriteState = SmokeWriteState.NONE,
    val creationAttempted: Boolean = false,
    val privateVerified: Boolean = false,
    val verificationAtMillis: Long? = null,
) {
    override fun toString() = "SmokeView(state=$writeState, <redacted>)"
}

interface SmokeSession : Closeable {
    val account: AccountProbeResult
    fun snapshot(): SmokeView
    suspend fun recent(): RecentSmokeResult
    suspend fun preparePlaylist(confirmed: Boolean): SmokeReport
    suspend fun linkPlaylist(input: String, confirmed: Boolean): SmokeReport
    suspend fun addTrack(input: String, confirmed: Boolean): SmokeReport
    suspend fun reconcile(): SmokeReport
}

fun interface SmokeSessionFactory {
    suspend fun open(grant: TokenGrant, ownerId: String, clientId: String): SmokeSession
}
