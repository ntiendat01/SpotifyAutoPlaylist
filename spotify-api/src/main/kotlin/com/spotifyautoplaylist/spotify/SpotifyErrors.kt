package com.spotifyautoplaylist.spotify

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Endpoints from 04-SPOTIFY-API §4. [template] never contains IDs, so it is safe to log. */
enum class SpotifyEndpoint(val apiId: String, val method: String, val template: String, val isWrite: Boolean) {
    RECENTLY_PLAYED("API-01", "GET", "/me/player/recently-played", false),
    CREATE_PLAYLIST("API-02", "POST", "/me/playlists", true),
    PLAYLIST_ITEMS("API-03", "GET", "/playlists/{id}/items", false),
    ADD_ITEMS("API-04", "POST", "/playlists/{id}/items", true),
    MY_PLAYLISTS("API-05", "GET", "/me/playlists", false),
    PLAYLIST("API-07", "GET", "/playlists/{id}", false),
}

/** 04-SPOTIFY-API §8 status classes. */
enum class SpotifyErrorKind {
    BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, RATE_LIMITED, QUOTA_EXCEEDED, SERVER, NETWORK,
    MALFORMED_RESPONSE, UNEXPECTED_STATUS,
}

/** Safe to display and log: carries no URL, ID, header, body or underlying exception. */
class SpotifyApiException(
    val endpoint: SpotifyEndpoint,
    val kind: SpotifyErrorKind,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Long? = null,
) : Exception("${endpoint.apiId} $kind${httpStatus?.let { " HTTP $it" }.orEmpty()}") {

    /**
     * The write may have been applied remotely (timeout, 5xx, unreadable success). The caller must mark
     * `VERIFY_REMOTE` and read the playlist back before any retry; never POST again blindly.
     */
    val outcomeUnknown: Boolean
        get() = endpoint.isWrite && kind in AMBIGUOUS

    /** Worth trying again later. For a write this still means reconcile first (see [outcomeUnknown]). */
    val retryable: Boolean
        get() = kind == SpotifyErrorKind.RATE_LIMITED || (kind in AMBIGUOUS && !endpoint.isWrite)

    private companion object {
        val AMBIGUOUS = setOf(
            SpotifyErrorKind.SERVER, SpotifyErrorKind.NETWORK,
            SpotifyErrorKind.MALFORMED_RESPONSE, SpotifyErrorKind.UNEXPECTED_STATUS,
        )
    }
}

/** One finished HTTP attempt. [toString] is the sanitized log line. */
class SpotifyHttpEvent(
    val endpoint: SpotifyEndpoint,
    val attempt: Int,
    val httpStatus: Int?,
    val error: SpotifyErrorKind?,
    val durationMillis: Long,
) {
    override fun toString() = "${endpoint.apiId} ${endpoint.method} ${endpoint.template} attempt=$attempt " +
        "status=${httpStatus ?: "-"} result=${error ?: "OK"} ms=$durationMillis"
}

fun interface SpotifyHttpLogger {
    fun log(event: SpotifyHttpEvent)

    companion object {
        val NONE = SpotifyHttpLogger { }
    }
}

internal fun statusKind(code: Int, errorBody: String?): SpotifyErrorKind = when (code) {
    400 -> SpotifyErrorKind.BAD_REQUEST
    401 -> SpotifyErrorKind.UNAUTHORIZED
    403 -> SpotifyErrorKind.FORBIDDEN
    404 -> SpotifyErrorKind.NOT_FOUND
    429 -> if (quotaExceeded(errorBody)) SpotifyErrorKind.QUOTA_EXCEEDED else SpotifyErrorKind.RATE_LIMITED
    in 500..599 -> SpotifyErrorKind.SERVER
    else -> SpotifyErrorKind.UNEXPECTED_STATUS
}

private fun quotaExceeded(body: String?): Boolean = try {
    val json = Json.parseToJsonElement(body ?: return false).jsonObject
    val reason = ((json["error"] as? JsonObject)?.get("reason") ?: json["reason"]) as? JsonPrimitive
    reason?.isString == true && reason.content == "QUOTA_EXCEEDED"
} catch (_: Exception) { false }

/** `Retry-After` as delta-seconds or an HTTP date, rounded up; null when absent or invalid. */
internal fun retryAfterSeconds(header: String?, now: Instant): Long? {
    val value = header?.trim() ?: return null
    val seconds = value.toLongOrNull() ?: try {
        val duration = Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
        duration.seconds + if (duration.nano > 0) 1 else 0
    } catch (_: Exception) { return null }
    return seconds.coerceAtLeast(0).takeIf { it <= Int.MAX_VALUE }
}
