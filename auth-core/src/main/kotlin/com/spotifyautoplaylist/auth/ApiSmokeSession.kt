package com.spotifyautoplaylist.auth

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ApiSmokeFactory internal constructor(
    private val journal: SmokeJournal,
    private val base: HttpUrl,
    private val client: OkHttpClient,
    private val now: () -> Instant = Instant::now,
) : SmokeSessionFactory {
    constructor(journal: SmokeJournal) : this(journal, "https://api.spotify.com/v1/".toHttpUrl(), probeHttpClient())

    override suspend fun open(grant: TokenGrant, ownerId: String, clientId: String): SmokeSession {
        try {
            if (!SpotifyAuth.isValidClientId(clientId)) throw ProbeException(ProbeFailure.INVALID_CLIENT_ID)
            val identity = AccountProbe(base.newBuilder().addPathSegment("me").build(), client).inspect(grant, ownerId)
            val accountId = identity.accountId ?: throw ProbeException(ProbeFailure.PROFILE_RESPONSE, 200)
            val scope = MessageDigest.getInstance("SHA-256").digest("$clientId\u0000$accountId".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val writerKey = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val record = SmokeLocks.forScope(writerKey).withLock { withContext(Dispatchers.IO) { journal.load(scope) } }
            return LiveSmokeSession(grant, ownerId, identity.summary, scope, writerKey, journal, record, base, client, now)
        } catch (failure: Exception) {
            grant.close()
            throw failure
        }
    }
}

private class LiveSmokeSession(
    private val grant: TokenGrant,
    private val ownerId: String,
    override val account: AccountProbeResult,
    private val scope: String,
    writerKey: String,
    private val journal: SmokeJournal,
    @Volatile private var record: SmokeRecord,
    private val base: HttpUrl,
    private val client: OkHttpClient,
    private val now: () -> Instant,
) : SmokeSession {
    private val mutex = SmokeLocks.forScope(writerKey)
    private var retryUntilNanos: Long? = null
    private var quotaBlocked = false
    @Volatile private var privateVerified = false
    @Volatile private var verificationAtMillis: Long? = null

    override fun snapshot() = SmokeView(
        record.playlistId?.let { "https://open.spotify.com/playlist/$it" },
        record.trackId?.let(OwnerIdentity::mask),
        if (record.writeState == SmokeWriteState.PRESENT && verificationAtMillis == null)
            SmokeWriteState.VERIFY_REMOTE else record.writeState,
        record.creationAttempted, privateVerified, verificationAtMillis,
    )

    override suspend fun recent(): RecentSmokeResult = operation {
        val json = get("me/player/recently-played", mapOf("limit" to "50"))
        val items = json.array("items")
        if (items.size > 50) invalid()
        var valid = 0
        for (element in items) {
            val item = element as? JsonObject ?: invalid()
            val track = item["track"] as? JsonObject ?: continue
            if (track.stringOrNull("type") != "track" || track["is_local"]?.jsonPrimitive?.booleanOrNull == true ||
                track.stringOrNull("id")?.let(SpotifyResourceId::isId) != true
            ) continue
            val playedAt = try { Instant.parse(item.string("played_at")) } catch (_: Exception) { continue }
            if (playedAt > now().plusSeconds(300)) continue
            valid++
        }
        RecentSmokeResult(items.size, valid, items.size - valid)
    }

    override suspend fun preparePlaylist(confirmed: Boolean): SmokeReport = operation {
        confirmation(confirmed)
        if (record.playlistId == null) {
            val matches = mutableListOf<String>()
            var earlierSmokeExists = false
            pages("me/playlists") { row ->
                val item = row as? JsonObject ?: incomplete()
                val owner = (item["owner"] as? JsonObject)?.stringOrNull("id") ?: incomplete()
                if (owner == ownerId) {
                    val description = item.stringOrNull("description").orEmpty()
                    if (description == record.marker) matches += item.string("id")
                    if (description.startsWith("[replay-m0:") || item.stringOrNull("name") == PLAYLIST_NAME) {
                        earlierSmokeExists = true
                    }
                }
            }
            when {
                matches.size == 1 -> save(record.copy(playlistId = validPlaylistId(matches.single())))
                matches.size > 1 -> throw ProbeException(ProbeFailure.LINK_REQUIRED)
                record.creationAttempted -> throw ProbeException(ProbeFailure.CREATION_UNCERTAIN)
                earlierSmokeExists -> throw ProbeException(ProbeFailure.LINK_REQUIRED)
                else -> {
                    save(record.copy(creationAttempted = true)) // durable before a possibly ambiguous POST
                    val created = post("me/playlists", buildJsonObject {
                        put("name", PLAYLIST_NAME); put("public", false); put("collaborative", false)
                        put("description", record.marker)
                    })
                    save(record.copy(playlistId = validPlaylistId(created.string("id"))))
                    metadata()
                    return@operation SmokeReport(SmokeOutcome.PLAYLIST_READY, postStatus = 201)
                }
            }
        }
        metadata()
        SmokeReport(SmokeOutcome.PLAYLIST_READY)
    }

    override suspend fun linkPlaylist(input: String, confirmed: Boolean): SmokeReport = operation {
        confirmation(confirmed)
        val id = SpotifyResourceId.parse(input, "playlist") ?: throw ProbeException(ProbeFailure.INVALID_PLAYLIST)
        if (record.playlistId != null && record.playlistId != id) throw ProbeException(ProbeFailure.LINK_REQUIRED)
        metadata(id)
        save(record.copy(playlistId = id))
        SmokeReport(SmokeOutcome.PLAYLIST_READY)
    }

    override suspend fun addTrack(input: String, confirmed: Boolean): SmokeReport = operation {
        confirmation(confirmed)
        val id = SpotifyResourceId.parse(input, "track") ?: throw ProbeException(ProbeFailure.INVALID_TRACK)
        if (record.trackId != null && record.trackId != id) throw ProbeException(ProbeFailure.TRACK_LOCKED)
        val before = membership(id)
        if (before.present) {
            save(record.copy(trackId = id, writeState = SmokeWriteState.PRESENT))
            return@operation SmokeReport(SmokeOutcome.ALREADY_PRESENT, before.pages)
        }
        if (record.writeState in setOf(SmokeWriteState.PRESENT, SmokeWriteState.REMOVED_EXTERNALLY)) {
            save(record.copy(writeState = SmokeWriteState.REMOVED_EXTERNALLY))
            return@operation SmokeReport(SmokeOutcome.REMOVED_EXTERNALLY, before.pages)
        }
        save(record.copy(trackId = id, writeState = SmokeWriteState.VERIFY_REMOTE))
        var postStatus: Int? = null
        try {
            val result = post("playlists/${playlistId()}/items", buildJsonObject {
                put("uris", buildJsonArray { add("spotify:track:$id") })
            })
            if (result.string("snapshot_id").isBlank()) invalid()
            postStatus = 201
        } catch (failure: ProbeException) {
            // A denied/throttled request stops here. Timeout/5xx/malformed success can be read back.
            if (failure.httpStatus in 400..499 || failure.reason == ProbeFailure.QUOTA_EXCEEDED) throw failure
        }
        val after = membership(id)
        if (!after.present) throw ProbeException(ProbeFailure.VERIFY_REMOTE)
        save(record.copy(writeState = SmokeWriteState.PRESENT))
        SmokeReport(SmokeOutcome.PRESENT, after.pages, postStatus)
    }

    override suspend fun reconcile(): SmokeReport = operation {
        val trackId = record.trackId
        if (trackId == null) {
            metadata()
            return@operation SmokeReport(SmokeOutcome.PLAYLIST_READY)
        }
        val result = membership(trackId)
        if (result.present) {
            save(record.copy(writeState = SmokeWriteState.PRESENT))
            SmokeReport(SmokeOutcome.PRESENT, result.pages)
        } else if (record.writeState in setOf(SmokeWriteState.PRESENT, SmokeWriteState.REMOVED_EXTERNALLY)) {
            save(record.copy(writeState = SmokeWriteState.REMOVED_EXTERNALLY))
            SmokeReport(SmokeOutcome.REMOVED_EXTERNALLY, result.pages)
        } else {
            SmokeReport(SmokeOutcome.ABSENT_RETRY_READY, result.pages)
        }
    }

    private class Membership(val present: Boolean, val pages: Int)

    private suspend fun membership(trackId: String): Membership {
        verificationAtMillis = null
        val snapshot = metadata()
        var present = false
        val count = pages("playlists/${playlistId()}/items",
            "items(item(id,type,is_local,linked_from),is_local),next,total,offset,limit",
        ) { element ->
            val row = element as? JsonObject ?: incomplete()
            val item = row["item"] as? JsonObject ?: incomplete()
            when (item.stringOrNull("type")) {
                "episode" -> Unit
                "track" -> {
                    val local = item["is_local"]?.jsonPrimitive?.booleanOrNull
                        ?: row["is_local"]?.jsonPrimitive?.booleanOrNull ?: incomplete()
                    if (!local) {
                        if (item["linked_from"]?.let { it !is JsonNull } == true) incomplete()
                        val id = item.stringOrNull("id")?.takeIf(SpotifyResourceId::isId) ?: incomplete()
                        if (id == trackId) present = true
                    }
                }
                else -> incomplete()
            }
        }
        if (metadata() != snapshot) incomplete()
        verificationAtMillis = now().toEpochMilli()
        return Membership(present, count)
    }

    private suspend fun metadata(id: String = playlistId()): String {
        privateVerified = false
        val json = get("playlists/$id", mapOf("fields" to "id,owner(id),public,collaborative,snapshot_id"))
        if (json.string("id") != id) invalid()
        if ((json["owner"] as? JsonObject)?.stringOrNull("id") != ownerId) {
            throw ProbeException(ProbeFailure.PLAYLIST_OWNER)
        }
        if (json["public"]?.jsonPrimitive?.booleanOrNull != false ||
            json["collaborative"]?.jsonPrimitive?.booleanOrNull != false
        ) throw ProbeException(ProbeFailure.PLAYLIST_PRIVACY)
        val snapshot = json.string("snapshot_id").takeIf { it.isNotBlank() } ?: invalid()
        privateVerified = true
        return snapshot
    }

    private suspend fun pages(path: String, fields: String? = null, consume: (JsonElement) -> Unit): Int {
        var offset = 0
        var expectedTotal: Int? = null
        repeat(20) { page ->
            val params = mutableMapOf("limit" to "50", "offset" to offset.toString())
            if (fields != null) params["fields"] = fields
            val json = get(path, params)
            val items = json.array("items")
            val total = json["total"]?.jsonPrimitive?.intOrNull ?: incomplete()
            if (json["offset"]?.jsonPrimitive?.intOrNull != offset || items.size > 50 || total < offset + items.size ||
                expectedTotal?.let { it != total } == true
            ) incomplete()
            expectedTotal = total
            items.forEach(consume)
            val nextField = json["next"] ?: incomplete()
            if (nextField is JsonNull) {
                if (offset + items.size != total) incomplete()
                return page + 1
            }
            val next = nextField.jsonPrimitive.takeIf { it.isString }?.content?.toHttpUrlOrNull() ?: incomplete()
            val expectedPath = url(path).encodedPath
            val nextOffset = offset + items.size
            if (items.isEmpty() || nextOffset >= total || next.scheme != base.scheme || next.host != base.host ||
                next.port != base.port || next.username.isNotEmpty() || next.password.isNotEmpty() ||
                next.fragment != null || next.encodedPath != expectedPath ||
                next.queryParameterValues("offset") != listOf(nextOffset.toString())
            ) incomplete()
            offset = nextOffset // reconstruct request: never forward a server-provided URL or query token
        }
        incomplete()
    }

    private suspend fun save(value: SmokeRecord) {
        withContext(NonCancellable + Dispatchers.IO) { journal.save(scope, value); record = value }
        if (value.writeState == SmokeWriteState.VERIFY_REMOTE) verificationAtMillis = null
        currentCoroutineContext().ensureActive()
    }

    private fun playlistId() = record.playlistId ?: throw ProbeException(ProbeFailure.PLAYLIST_NOT_READY)
    private fun validPlaylistId(value: String) = value.takeIf(SpotifyResourceId::isId) ?: invalid()
    private fun confirmation(value: Boolean) { if (!value) throw ProbeException(ProbeFailure.CONFIRMATION_REQUIRED) }

    private fun url(path: String, params: Map<String, String> = emptyMap()): HttpUrl = base.newBuilder().apply {
        path.split('/').forEach(::addPathSegment)
        params.forEach { (key, value) -> addQueryParameter(key, value) }
    }.build()

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonObject = request(url(path, params), null)
    private suspend fun post(path: String, body: JsonObject): JsonObject = request(url(path), body)

    private suspend fun request(url: HttpUrl, body: JsonObject?): JsonObject {
        val remaining = retryUntilNanos?.let { it - System.nanoTime() } ?: 0
        if (quotaBlocked) throw ProbeException(ProbeFailure.QUOTA_EXCEEDED, 429)
        if (remaining > 0) throw ProbeException(ProbeFailure.API_HTTP, 429, (remaining + 999_999_999) / 1_000_000_000)
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${grant.requireAccessToken()}")
            .header("Cache-Control", "no-store").apply {
                if (body != null) post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            }.build()
        try {
            return client.probeJson(request, ProbeFailure.API_HTTP, ProbeFailure.API_RESPONSE,
                expectedStatus = if (body == null) 200 else 201, maxBytes = 1_048_576,
            ) { it }
        } catch (failure: ProbeException) {
            if (failure.httpStatus == 429) {
                retryUntilNanos = System.nanoTime() + (failure.retryAfterSeconds ?: 60) * 1_000_000_000
                quotaBlocked = failure.reason == ProbeFailure.QUOTA_EXCEEDED
            }
            throw failure
        }
    }

    private suspend fun <T> operation(block: suspend () -> T): T = mutex.withLock {
        grant.requireAccessToken()
        try {
            val latest = withContext(Dispatchers.IO) { journal.load(scope) }
            if (latest != record) { privateVerified = false; verificationAtMillis = null }
            record = latest
            block()
        } catch (failure: CancellationException) { throw failure }
        catch (failure: ProbeException) { throw failure }
        catch (_: Exception) { throw ProbeException(ProbeFailure.API_RESPONSE) }
    }

    override fun close() = grant.close()

    companion object { const val PLAYLIST_NAME = "My Replays — AutoPlaylist" }
}

private object SmokeLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun forScope(scope: String): Mutex = locks.computeIfAbsent(scope) { Mutex() }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.string(key: String): String = stringOrNull(key) ?: invalid()
private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: invalid()
private fun invalid(): Nothing = throw ProbeException(ProbeFailure.API_RESPONSE)
private fun incomplete(): Nothing = throw ProbeException(ProbeFailure.READ_INCOMPLETE)
