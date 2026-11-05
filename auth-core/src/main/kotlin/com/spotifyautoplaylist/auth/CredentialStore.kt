package com.spotifyautoplaylist.auth

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.*

interface TokenCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * AES-256-GCM envelope: version byte, IV length, IV, ciphertext+tag. On Android the key comes from
 * AndroidKeyStore, which generates the IV itself; the key never leaves secure hardware/software.
 */
class AesGcmTokenCipher(private val key: () -> SecretKey) : TokenCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val sealed = cipher.doFinal(plaintext)
        val iv = cipher.iv
        require(iv.size == IV_BYTES)
        return ByteBuffer.allocate(2 + iv.size + sealed.size)
            .put(VERSION).put(iv.size.toByte()).put(iv).put(sealed).array()
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > 2 + IV_BYTES + TAG_BITS / 8)
        require(ciphertext[0] == VERSION && ciphertext[1].toInt() == IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, ciphertext, 2, IV_BYTES))
        cipher.updateAAD(AAD)
        return cipher.doFinal(ciphertext, 2 + IV_BYTES, ciphertext.size - 2 - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val AAD = "replay.credentials.v1".toByteArray(Charsets.US_ASCII)
    }
}

interface CredentialStore {
    /** Returns null when no grant is stored; throws [AuthFailure.STORAGE] when it is unreadable. */
    fun load(): StoredCredentials?
    fun save(credentials: StoredCredentials)
    fun clear()
}

/** Place only under Android noBackupFilesDir. Writes are atomic; contents are never logged. */
class EncryptedCredentialStore(private val file: File, private val cipher: TokenCipher) : CredentialStore {
    private val temporary = File(file.parentFile, file.name + ".tmp")

    @Synchronized
    override fun load(): StoredCredentials? = safe {
        // A leftover temporary file is an interrupted save; the target still holds the last committed grant.
        if (temporary.exists()) require(temporary.delete())
        if (!file.exists()) return@safe null
        require(file.length() in 1..MAX_BYTES)
        val json = Json.parseToJsonElement(cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8)).jsonObject
        require(json["version"]?.jsonPrimitive?.intOrNull == 1)
        fun text(name: String) = json.getValue(name).jsonPrimitive.also { require(it.isString) }.content
        StoredCredentials(
            clientId = text("clientId"),
            spotifyUserId = text("spotifyUserId"),
            accountId = text("accountId"),
            accessToken = text("accessToken"),
            refreshToken = text("refreshToken"),
            expiresAtEpochMillis = json.getValue("expiresAtEpochMillis").jsonPrimitive.long,
            scopes = json.getValue("scopes").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        ).also(::validate)
    }

    @Synchronized
    override fun save(credentials: StoredCredentials): Unit = safe {
        validate(credentials)
        val directory = requireNotNull(file.parentFile)
        require(directory.isDirectory || directory.mkdirs())
        val json = buildJsonObject {
            put("version", 1)
            put("clientId", credentials.clientId)
            put("spotifyUserId", credentials.spotifyUserId)
            put("accountId", credentials.accountId)
            put("accessToken", credentials.accessToken)
            put("refreshToken", credentials.refreshToken)
            put("expiresAtEpochMillis", credentials.expiresAtEpochMillis)
            putJsonArray("scopes") { credentials.scopes.sorted().forEach(::add) }
        }
        val sealed = cipher.encrypt(json.toString().toByteArray(Charsets.UTF_8))
        FileOutputStream(temporary).use { stream ->
            stream.write(sealed)
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }

    @Synchronized
    override fun clear(): Unit = safe {
        require((!temporary.exists() || temporary.delete()) && (!file.exists() || file.delete()))
    }

    private fun validate(credentials: StoredCredentials) {
        require(SpotifyAuth.isValidClientId(credentials.clientId))
        require(OwnerIdentity.isValid(credentials.spotifyUserId) && OwnerIdentity.isValid(credentials.accountId))
        require(credentials.accessToken.isNotEmpty() && credentials.refreshToken.isNotEmpty())
        require(credentials.expiresAtEpochMillis > 0)
    }

    private inline fun <T> safe(block: () -> T): T = try { block() } catch (_: Exception) {
        // Never carry file contents, key errors or paths into shared diagnostics.
        throw AuthException(AuthFailure.STORAGE)
    }

    private companion object {
        const val MAX_BYTES = 65_536L
    }
}
