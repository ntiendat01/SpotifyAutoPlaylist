package com.spotifyautoplaylist.spotify

import java.lang.reflect.Type
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Raw Retrofit surface; only [SpotifyClient] calls it, so every call gets auth, status mapping and logs. */
internal interface SpotifyService {
    @GET("me/player/recently-played")
    suspend fun recentlyPlayed(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int,
        @Query("before") before: Long?,
        @Query("after") after: Long?,
    ): Response<RecentlyPlayedPage>

    @POST("me/playlists")
    suspend fun createPlaylist(
        @Header("Authorization") authorization: String,
        @Body body: CreatePlaylistRequest,
    ): Response<PlaylistDto>

    @GET("playlists/{id}/items")
    suspend fun playlistItems(
        @Header("Authorization") authorization: String,
        @Path("id") playlistId: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("fields") fields: String,
    ): Response<PlaylistItemsPage>

    @POST("playlists/{id}/items")
    suspend fun addItems(
        @Header("Authorization") authorization: String,
        @Path("id") playlistId: String,
        @Body body: AddItemsRequest,
    ): Response<SnapshotResponse>

    @GET("me/playlists")
    suspend fun myPlaylists(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): Response<PlaylistsPage>

    @GET("playlists/{id}")
    suspend fun playlist(
        @Header("Authorization") authorization: String,
        @Path("id") playlistId: String,
        @Query("fields") fields: String,
    ): Response<PlaylistDto>
}

internal class ResponseTooLargeException : Exception()

/** kotlinx JSON converter that refuses bodies above [maxBytes] instead of buffering them. */
@OptIn(ExperimentalSerializationApi::class)
internal class BoundedJsonConverterFactory(private val json: Json, private val maxBytes: Long) : Converter.Factory() {
    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *> {
        val serializer = json.serializersModule.serializer(type)
        return Converter<ResponseBody, Any> { body ->
            body.use {
                val source = it.source()
                if (source.request(maxBytes + 1)) throw ResponseTooLargeException()
                json.decodeFromString(serializer, source.readUtf8())
            }
        }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody> {
        val serializer = json.serializersModule.serializer(type)
        return Converter<Any, RequestBody> { json.encodeToString(serializer, it).toRequestBody(JSON_TYPE) }
    }

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
