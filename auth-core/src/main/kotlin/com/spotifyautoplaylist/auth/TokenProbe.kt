package com.spotifyautoplaylist.auth

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class TokenProbeResult(val httpStatus: Int, val scopes: Set<String>, val expiresInSeconds: Int)

fun interface TokenExchange {
    suspend fun verify(input: TokenExchangeInput): TokenProbeResult
}

/** Deliberately discards every token after validating its response shape. No persistence in M0. */
class TokenProbe internal constructor(
    private val endpoint: HttpUrl,
    private val client: OkHttpClient,
) : TokenExchange {
    constructor() : this(
        "https://accounts.spotify.com/api/token".toHttpUrl(),
        OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build(),
    )

    override suspend fun verify(input: TokenExchangeInput): TokenProbeResult = suspendCancellableCoroutine { continuation ->
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", input.clientId)
            .add("code", input.code)
            .add("redirect_uri", SpotifyAuth.REDIRECT_URI)
            .add("code_verifier", input.verifier).build()
        val request = Request.Builder().url(endpoint).post(body).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(ProbeException(ProbeFailure.NETWORK))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (it.code != 200) throw ProbeException(ProbeFailure.TOKEN_HTTP, it.code)
                        val source = it.body?.source() ?: throw ProbeException(ProbeFailure.TOKEN_RESPONSE)
                        if (source.request(65_537)) throw ProbeException(ProbeFailure.TOKEN_RESPONSE)
                        val json = Json.parseToJsonElement(source.readUtf8()).jsonObject
                        val tokenField = json["access_token"]?.jsonPrimitive
                        val typeField = json["token_type"]?.jsonPrimitive
                        val token = tokenField?.content
                        val type = typeField?.content
                        val expires = json["expires_in"]?.jsonPrimitive?.intOrNull
                        if (tokenField?.isString != true || token.isNullOrBlank() ||
                            typeField?.isString != true || !type.equals("Bearer", ignoreCase = true) ||
                            expires == null || expires <= 0
                        ) throw ProbeException(ProbeFailure.TOKEN_RESPONSE)
                        val scopes = json["scope"]?.jsonPrimitive?.content.orEmpty()
                            .split(' ').filter(String::isNotBlank).toSet()
                        if (!scopes.containsAll(SpotifyAuth.scopes)) {
                            throw ProbeException(ProbeFailure.INSUFFICIENT_SCOPE, 200)
                        }
                        TokenProbeResult(200, scopes, expires)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    val safe = when (failure) {
                        is ProbeException -> failure
                        is IOException -> ProbeException(ProbeFailure.NETWORK)
                        else -> ProbeException(ProbeFailure.TOKEN_RESPONSE)
                    }
                    if (continuation.isActive) continuation.resumeWithException(safe)
                }
            }
        })
    }
}
