package com.spotifyautoplaylist.onboarding

import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.auth.SpotifyResourceId
import com.spotifyautoplaylist.data.local.AccountEntity
import com.spotifyautoplaylist.data.local.ManagedPlaylistEntity
import com.spotifyautoplaylist.data.local.ReplayDatabase
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No tokens, profile or play events. A separate no-backup directory from the M0 smoke journal. */
class FileCreationJournal(private val directory: File) {
    @Synchronized
    fun load(key: AccountKey): CreationRecord = safe {
        val file = file(key)
        // An interrupted first write must fail closed rather than generate a fresh creation marker.
        if (!file.exists()) {
            require(!File(directory, "${key.accountHash}.tmp").exists())
            return@safe CreationRecord().also { save(key, it) }
        }
        require(file.length() in 1..4096)
        val props = Properties().apply { file.inputStream().use(::load) }
        require(props.getProperty("version") == "1")
        val attempted = when (props.getProperty("attempted")) { "true" -> true; "false" -> false; else -> error("invalid record") }
        CreationRecord(props.getProperty("marker"), attempted, props.getProperty("playlistId").takeUnless { it.isNullOrEmpty() })
            .also(::validate)
    }

    @Synchronized
    fun save(key: AccountKey, record: CreationRecord): Unit = safe {
        validate(record)
        require(directory.isDirectory || directory.mkdirs())
        val props = Properties().apply {
            setProperty("version", "1"); setProperty("marker", record.marker)
            setProperty("attempted", record.attempted.toString()); setProperty("playlistId", record.playlistId.orEmpty())
        }
        val temporary = File(directory, "${key.accountHash}.tmp")
        FileOutputStream(temporary).use { props.store(it, null); it.fd.sync() }
        Files.move(temporary.toPath(), file(key).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }

    private fun file(key: AccountKey) = File(directory, "${key.accountHash}.properties")
    private fun validate(record: CreationRecord) {
        require(record.marker.matches(Regex("\\[replay-setup:[0-9a-f-]{36}]")))
        require(record.playlistId == null || (record.attempted && SpotifyResourceId.isId(record.playlistId)))
    }
    private inline fun <T> safe(block: () -> T): T = try { block() } catch (_: Exception) {
        throw SetupException(SetupFailure.STORAGE)
    }
}

class RoomSetupPersistence(private val database: ReplayDatabase, private val journal: FileCreationJournal) : SetupPersistence {
    override suspend fun playlist(account: AccountKey): SetupPlaylist? = database.playlists().managed(account.spotifyUserId)?.let {
        if (!it.ownershipVerified) throw SetupException(SetupFailure.RECOVERY)
        SetupPlaylist(it.playlistId, it.name)
    }

    override suspend fun savePlaylist(account: AccountKey, playlist: SetupPlaylist) {
        val userId = account.spotifyUserId
        val current = database.playlists().managed(userId)
        if (current != null) {
            if (current.playlistId != playlist.id) throw SetupException(SetupFailure.ALREADY_LINKED)
            return
        }
        if (database.accounts().get(userId) == null) database.accounts().upsert(AccountEntity(userId, null, System.currentTimeMillis()))
        database.playlists().insertManaged(ManagedPlaylistEntity(
            userId, playlist.id, playlist.url, playlist.name,
            // Creation time isn't established for a linked/recovered playlist.
            createdAtMs = null, ownershipVerified = true,
        ))
    }

    override suspend fun record(account: AccountKey): CreationRecord = withContext(Dispatchers.IO) { journal.load(account) }
    override suspend fun saveRecord(account: AccountKey, record: CreationRecord) = withContext(Dispatchers.IO) { journal.save(account, record) }
}
