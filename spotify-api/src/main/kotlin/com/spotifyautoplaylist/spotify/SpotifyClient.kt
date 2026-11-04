package com.spotifyautoplaylist.spotify

import com.spotifyautoplaylist.auth.AccessToken
import com.spotifyautoplaylist.auth.BearerTokenProvider
import com.spotifyautoplaylist.auth.SpotifyResourceId
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit

/**
 * Spotify Web API calls for sync and playlist reconciliation (04-SPOTIFY-API §4, §8).
 *
 * - A 401 refreshes once through [BearerTokenProvider] and retries; a second 401 fails.
 * - Nothing else is retried here: backoff, `VERIFY_REMOTE` and reconciliation belong to the caller.
 * - After a 429 every call fails fast until `Retry-After` has passed.
 * - Callers pass IDs, never server-provided `next` URLs.
 */
class SpotifyClient internal constructor(
    private val service: SpotifyService,
    private val tokens: BearerTokenProvider,
    private val logger: SpotifyHttpLogger,
    private val clock: () -> Instant,
    private val nanoTime: () -> Long,
) {
    private class Block(val untilNanos: Long, val kind: SpotifyErrorKind)

    @Volatile private var block: Block? = null

    /** API-01. `before`/`after` are Unix epoch milliseconds and mutually exclusive. */
    suspend fun recentlyPlayed(before: Long? = null, after: Long? = null, limit: Int = MAX_PAGE): RecentlyPlayedPage {
        require(before == null || after == null) { "before and after are mutually exclusive" }
        require((before ?: 0) >= 0 && (after ?: 0) >= 0 && limit in 1..MAX_PAGE)
        return call(SpotifyEndpoint.RECENTLY_PLAYED, { it.items.size <= limit }) {
            service.recentlyPlayed(it, limit, before, after)
        }
    }

    /** API-02. Always private and not collaborative. A failure with `outcomeUnknown` must not be re-sent. */
    suspend fun createPlaylist(name: String, description: String): PlaylistDto {
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH)
        return call(SpotifyEndpoint.CREATE_PLAYLIST, { SpotifyResourceId.isId(it.id) }) {
            service.createPlaylist(it, CreatePlaylistRequest(name, description))
        }
    }

    /** API-03, one page by offset. */
    suspend fun playlistItems(playlistId: String, offset: Int = 0, limit: Int = MAX_PAGE): PlaylistItemsPage {
        requirePage(playlistId, offset, limit)
        return call(SpotifyEndpoint.PLAYLIST_ITEMS, { it.offset == offset && it.items.size <= limit }) {
            service.playlistItems(it, playlistId, limit, offset, ITEM_FIELDS)
        }
    }

    /** API-04. Callers must mark the outbox `VERIFY_REMOTE` before calling (TX-04). */
    suspend fun addTracks(playlistId: String, trackIds: List<String>): SnapshotResponse {
        require(SpotifyResourceId.isId(playlistId))
        require(trackIds.size in 1..MAX_ADD && trackIds.all(SpotifyResourceId::isId) && trackIds.distinct() == trackIds)
        return call(SpotifyEndpoint.ADD_ITEMS, { it.snapshotId.isNotBlank() }) {
            service.addItems(it, playlistId, AddItemsRequest(trackIds.map { id -> "spotify:track:$id" }))
        }
    }

    /** API-05, one page by offset. */
    suspend fun myPlaylists(offset: Int = 0, limit: Int = MAX_PAGE): PlaylistsPage {
        require(offset >= 0 && limit in 1..MAX_PAGE)
        return call(SpotifyEndpoint.MY_PLAYLISTS, { it.offset == offset && it.items.size <= limit }) {
            service.myPlaylists(it, limit, offset)
        }
    }

    /** API-07: owner, privacy and snapshot for verification before writes. */
    suspend fun playlist(playlistId: String): PlaylistDto {
        require(SpotifyResourceId.isId(playlistId))
        return call(SpotifyEndpoint.PLAYLIST, { it.id == playlistId }) {
            service.playlist(it, playlistId, PLAYLIST_FIELDS)
        }
    }

    private suspend fun <T : Any> call(
        endpoint: SpotifyEndpoint,
        valid: (T) -> Boolean,
        send: suspend (authorization: String) -> Response<T>,
    ): T {
        block?.let {
            val remaining = it.untilNanos - nanoTime()
            if (remaining > 0) {
                throw SpotifyApiException(endpoint, it.kind, 429, TimeUnit.NANOSECONDS.toSeconds(remaining + 999_999_999))
            }
        }
        var token: AccessToken = tokens.accessToken()
        attempt(endpoint, 1, valid) { send(token.bearerHeader()) }?.let { return it }
        token = tokens.onUnauthorized(token)
        attempt(endpoint, 2, valid) { send(token.bearerHeader()) }?.let { return it }
        // The refreshed token was rejected too; the production provider signs out and throws here.
        tokens.onUnauthorized(token)
        throw SpotifyApiException(endpoint, SpotifyErrorKind.UNAUTHORIZED, 401)
    }

    /** Returns null only for HTTP 401. */
    private suspend fun <T : Any> attempt(
        endpoint: SpotifyEndpoint,
        number: Int,
        valid: (T) -> Boolean,
        send: suspend () -> Response<T>,
    ): T? {
        val started = nanoTime()
        fun log(status: Int?, error: SpotifyErrorKind?) {
            val event = SpotifyHttpEvent(endpoint, number, status, error, TimeUnit.NANOSECONDS.toMillis(nanoTime() - started))
            try { logger.log(event) } catch (_: Exception) { /* diagnostics must not change the result */ }
        }
        fun fail(status: Int?, kind: SpotifyErrorKind, retryAfter: Long? = null): Nothing {
            log(status, kind)
            throw SpotifyApiException(endpoint, kind, status, retryAfter)
        }

        val response = try {
            send()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: IOException) {
            fail(null, SpotifyErrorKind.NETWORK)
        } catch (_: SerializationException) {
            fail(null, SpotifyErrorKind.MALFORMED_RESPONSE)
        } catch (_: ResponseTooLargeException) {
            fail(null, SpotifyErrorKind.MALFORMED_RESPONSE)
        }
        val status = response.code()
        if (response.isSuccessful) {
            val body = response.body()
            if (body == null || !valid(body)) fail(status, SpotifyErrorKind.MALFORMED_RESPONSE)
            log(status, null)
            return body
        }
        val errorText = try { response.errorBody()?.use(::readPrefix) } catch (_: IOException) { null }
        if (status == 401) {
            log(status, SpotifyErrorKind.UNAUTHORIZED)
            return null
        }
        val kind = statusKind(status, errorText)
        if (status != 429) fail(status, kind)
        val retryAfter = retryAfterSeconds(response.headers()["Retry-After"], clock()) ?: DEFAULT_RETRY_AFTER_SECONDS
        block = Block(nanoTime() + TimeUnit.SECONDS.toNanos(retryAfter), kind)
        fail(status, kind, retryAfter)
    }

    companion object {
        const val MAX_PAGE = 50
        const val MAX_ADD = 100
        const val MAX_NAME_LENGTH = 100
        private const val MAX_RESPONSE_BYTES = 1_048_576L
        private const val MAX_ERROR_BYTES = 4_096L
        private const val DEFAULT_RETRY_AFTER_SECONDS = 60L
        private const val ITEM_FIELDS =
            "items(added_at,is_local,item(id,type,uri,is_local,linked_from)),next,total,offset,limit"
        private const val PLAYLIST_FIELDS =
            "id,name,description,owner(id),public,collaborative,snapshot_id,external_urls"
        private val API_BASE = "https://api.spotify.com/v1/".toHttpUrl()

        internal val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun create(tokens: BearerTokenProvider, logger: SpotifyHttpLogger = SpotifyHttpLogger.NONE): SpotifyClient =
            create(tokens, logger, API_BASE, defaultHttpClient())

        internal fun create(
            tokens: BearerTokenProvider,
            logger: SpotifyHttpLogger,
            baseUrl: HttpUrl,
            http: OkHttpClient,
            clock: () -> Instant = Instant::now,
            nanoTime: () -> Long = System::nanoTime,
        ): SpotifyClient {
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(http)
                .addConverterFactory(BoundedJsonConverterFactory(JSON, MAX_RESPONSE_BYTES))
                .build()
            return SpotifyClient(retrofit.create(SpotifyService::class.java), tokens, logger, clock, nanoTime)
        }

        /** No redirects (a bearer token must not follow one) and no transparent retry of a POST. */
        internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()

        private fun readPrefix(body: ResponseBody): String {
            val source = body.source()
            source.request(MAX_ERROR_BYTES)
            return source.buffer.readUtf8(minOf(MAX_ERROR_BYTES, source.buffer.size))
        }

        private fun requirePage(playlistId: String, offset: Int, limit: Int) =
            require(SpotifyResourceId.isId(playlistId) && offset >= 0 && limit in 1..MAX_PAGE)
    }
}
