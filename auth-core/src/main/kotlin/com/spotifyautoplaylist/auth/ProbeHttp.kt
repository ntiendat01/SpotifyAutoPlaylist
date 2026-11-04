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
                    if (it.code != 200) throw ProbeException(
                        httpFailure, it.code,
                        if (it.code == 429) retryAfterSeconds(it.header("Retry-After")) ?: 60 else null,
                    )
                    val source = it.body?.source() ?: throw ProbeException(responseFailure)
                    if (source.request(65_537)) throw ProbeException(responseFailure)
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
