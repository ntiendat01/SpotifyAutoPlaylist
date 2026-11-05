package com.spotifyautoplaylist.auth

import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal fun probeHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS).build()

/** Bounded, cancellable HTTP with only safe error categories crossing the transport boundary. */
internal suspend fun <T> OkHttpClient.probeJson(
    request: Request,
    httpFailure: ProbeFailure,
    responseFailure: ProbeFailure,
    discard: (T) -> Unit = {},
    expectedStatus: Int = 200,
    maxBytes: Long = 65_536,
    parse: (JsonObject) -> T,
): T = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(ProbeException(ProbeFailure.NETWORK))
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val json = response.use {
                    if (it.code != expectedStatus) throw ProbeException(
                        if (it.code == 429 && quotaExceeded(it)) ProbeFailure.QUOTA_EXCEEDED else httpFailure, it.code,
                        if (it.code == 429) retryAfterSeconds(it.header("Retry-After")) ?: 60 else null,
                    )
                    val source = it.body?.source() ?: throw ProbeException(responseFailure)
                    if (source.request(maxBytes + 1)) throw ProbeException(responseFailure)
                    Json.parseToJsonElement(source.readUtf8()).jsonObject
                }
                // Do not allocate a credential-bearing result until the response is closed.
                val result = parse(json)
                // A canceled dispatcher handoff must not strand an in-memory token grant.
                continuation.resume(result) { _, value, _ -> discard(value) }
            } catch (failure: Exception) {
                val safe = when (failure) {
                    is ProbeException -> failure
                    is IOException -> ProbeException(ProbeFailure.NETWORK)
                    else -> ProbeException(responseFailure)
                }
                if (continuation.isActive) continuation.resumeWithException(safe)
            }
        }
    })
}

private fun quotaExceeded(response: Response): Boolean = try {
    val json = Json.parseToJsonElement(response.peekBody(4_096).string()).jsonObject
    val error = json["error"] as? JsonObject
    (error?.get("reason") ?: json["reason"])?.toString() == "\"QUOTA_EXCEEDED\""
} catch (_: Exception) { false }

internal fun retryAfterSeconds(header: String?, now: Instant = Instant.now()): Long? {
    val value = header?.trim() ?: return null
    val seconds = value.toLongOrNull() ?: try {
        val instant = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        // Round upward so an HTTP date is never retried early due to sub-second rounding.
        val duration = Duration.between(now, instant)
        duration.seconds + if (duration.nano > 0) 1 else 0
    } catch (_: Exception) { return null }
    return seconds.takeIf { it in 0..Int.MAX_VALUE.toLong() }
}
