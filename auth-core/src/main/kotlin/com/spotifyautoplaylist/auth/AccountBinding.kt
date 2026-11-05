package com.spotifyautoplaylist.auth

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.*

/**
 * Which Spotify account owns this installation's local data (AUTH-006 / BR-013). Survives sign-out so a
 * different account can never reconnect onto previous history, playlist or outbox rows; only an explicit
 * [SpotifyAuthManager.resetAccount] removes it. [resetPending] marks a reset that has not finished yet.
 */
class AccountBinding internal constructor(internal val accountHash: String?, val resetPending: Boolean) {
    init {
        require(accountHash == null || accountHash.matches(HASH)) { "invalid binding" }
        require(resetPending || accountHash != null) { "invalid binding" }
    }

    override fun toString() = "AccountBinding(resetPending=$resetPending, <redacted>)"

    internal companion object {
        val HASH = Regex("[0-9a-f]{64}")
    }
}

/**
 * Key every account-scoped repository must use. Production code obtains it only from
 * [SpotifyAuthManager.requireAccount] for the bound, signed-in account; direct construction is for tests.
 */
class AccountKey(val spotifyUserId: String, val accountHash: String) {
    init {
        require(OwnerIdentity.isValid(spotifyUserId) && accountHash.matches(AccountBinding.HASH)) { "invalid account key" }
    }

    override fun equals(other: Any?) = other is AccountKey &&
        other.spotifyUserId == spotifyUserId && other.accountHash == accountHash

    override fun hashCode() = 31 * spotifyUserId.hashCode() + accountHash.hashCode()
    override fun toString() = "AccountKey(<redacted>)"
}

internal fun accountHash(accountId: String): String = MessageDigest.getInstance("SHA-256")
    .digest("replay.account.v1\u0000$accountId".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

interface AccountBindingStore {
    /** Returns null when unbound; throws [AuthFailure.STORAGE] when unreadable. */
    fun load(): AccountBinding?
    fun save(binding: AccountBinding)
    fun clear()
}

/**
 * One step of an explicit account reset, e.g. cancel WorkManager jobs or delete account-scoped Room rows.
 * Steps run in order and must be idempotent: an interrupted reset is retried from the start.
 */
fun interface AccountResetStep {
    suspend fun reset()
}

/** Place only under Android noBackupFilesDir. Stores a salted hash, never the raw account ID. */
class FileAccountBindingStore(private val file: File) : AccountBindingStore {
    private val temporary = File(file.parentFile, file.name + ".tmp")

    @Synchronized
    override fun load(): AccountBinding? = safe {
        if (temporary.exists()) require(temporary.delete())
        if (!file.exists()) return@safe null
        require(file.length() in 1..4_096)
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        require(json["version"]?.jsonPrimitive?.intOrNull == 1)
        val hash = json.getValue("accountHash").let { if (it is JsonNull) null else it.jsonPrimitive.content }
        AccountBinding(hash, json.getValue("resetPending").jsonPrimitive.boolean)
    }

    @Synchronized
    override fun save(binding: AccountBinding): Unit = safe {
        val directory = requireNotNull(file.parentFile)
        require(directory.isDirectory || directory.mkdirs())
        val json = buildJsonObject {
            put("version", 1)
            put("accountHash", binding.accountHash?.let(::JsonPrimitive) ?: JsonNull)
            put("resetPending", binding.resetPending)
        }
        FileOutputStream(temporary).use { stream ->
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }

    @Synchronized
    override fun clear(): Unit = safe {
        require((!temporary.exists() || temporary.delete()) && (!file.exists() || file.delete()))
    }

    private inline fun <T> safe(block: () -> T): T = try { block() } catch (_: Exception) {
        throw AuthException(AuthFailure.STORAGE)
    }
}
