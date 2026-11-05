package com.spotifyautoplaylist.auth

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.json.*

enum class SmokeWriteState { NONE, VERIFY_REMOTE, PRESENT, REMOVED_EXTERNALLY }

data class SmokeRecord(
    val marker: String = "[replay-m0:${UUID.randomUUID()}]",
    val playlistId: String? = null,
    val creationAttempted: Boolean = false,
    val trackId: String? = null,
    val writeState: SmokeWriteState = SmokeWriteState.NONE,
) {
    override fun toString() = "SmokeRecord(state=$writeState, <redacted>)"
}

interface SmokeJournal {
    fun load(scope: String): SmokeRecord
    fun save(scope: String, record: SmokeRecord)
}

/** Place only under Android noBackupFilesDir. Fail closed on corruption or non-atomic filesystems. */
class FileSmokeJournal(private val directory: File) : SmokeJournal {
    @Synchronized
    override fun load(scope: String): SmokeRecord = safe {
        val file = file(scope)
        if (!file.exists()) {
            require(!File(directory, "$scope.tmp").exists())
            return@safe SmokeRecord().also { save(scope, it) }
        }
        require(file.length() in 1..16_384)
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        require(json["version"]?.jsonPrimitive?.intOrNull == 1)
        val record = SmokeRecord(
            marker = json.getValue("marker").jsonPrimitive.content,
            playlistId = json["playlistId"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            creationAttempted = json.getValue("creationAttempted").jsonPrimitive.boolean,
            trackId = json["trackId"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            writeState = SmokeWriteState.valueOf(json.getValue("writeState").jsonPrimitive.content),
        )
        validate(record)
        record
    }

    @Synchronized
    override fun save(scope: String, record: SmokeRecord): Unit = safe {
        validate(record)
        require(directory.isDirectory || directory.mkdirs())
        val target = file(scope)
        val temporary = File(directory, "$scope.tmp")
        val json = buildJsonObject {
            put("version", 1); put("marker", record.marker)
            put("playlistId", record.playlistId?.let(::JsonPrimitive) ?: JsonNull)
            put("creationAttempted", record.creationAttempted)
            put("trackId", record.trackId?.let(::JsonPrimitive) ?: JsonNull)
            put("writeState", record.writeState.name)
        }
        FileOutputStream(temporary).use { stream ->
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }

    private fun file(scope: String): File {
        require(scope.matches(Regex("[0-9a-f]{64}")))
        return File(directory, "$scope.json")
    }

    private fun validate(record: SmokeRecord) {
        require(record.marker.matches(Regex("\\[replay-m0:[0-9a-f-]{36}]")))
        require(record.playlistId == null || SpotifyResourceId.isId(record.playlistId))
        require(record.trackId == null || SpotifyResourceId.isId(record.trackId))
        require((record.trackId == null) == (record.writeState == SmokeWriteState.NONE))
        require(record.trackId == null || record.playlistId != null)
    }

    private inline fun <T> safe(block: () -> T): T = try { block() } catch (_: Exception) {
        // Never carry file contents, resource identifiers or paths into shared diagnostics.
        throw ProbeException(ProbeFailure.JOURNAL)
    }
}
