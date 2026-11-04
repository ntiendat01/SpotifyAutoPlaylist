package com.spotifyautoplaylist.auth

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class EncryptedCredentialStoreTest {
    private val directory = Files.createTempDirectory("credentials").toFile()
    private val file = File(directory, "auth/credentials.bin")
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmTokenCipher { key }

    @After fun cleanUp() { directory.deleteRecursively() }

    @Test fun `round trip keeps grant and file contains no plaintext`() {
        val store = EncryptedCredentialStore(file, cipher)
        assertNull(store.load())
        store.save(credentials())
        val loaded = EncryptedCredentialStore(file, cipher).load()!!
        assertEquals("dummy-access", loaded.accessToken)
        assertEquals("dummy-refresh", loaded.refreshToken)
        assertEquals("acct-1", loaded.accountId)
        assertEquals(1_000L, loaded.expiresAtEpochMillis)
        assertEquals(SpotifyAuth.scopes, loaded.scopes)
        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        for (secret in listOf("dummy-access", "dummy-refresh", "acct-1", "owner", DUMMY_CLIENT_ID)) {
            assertFalse(raw.contains(secret))
        }
        assertFalse(loaded.toString().contains("dummy"))
        assertFalse(File(file.parentFile, "credentials.bin.tmp").exists())
    }

    @Test fun `cipher uses fresh IV and authenticates ciphertext`() {
        val plain = "same".toByteArray()
        val first = cipher.encrypt(plain)
        assertFalse(first.contentEquals(cipher.encrypt(plain)))
        assertArrayEquals(plain, cipher.decrypt(first))
        val tampered = first.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }
        assertThrows(Exception::class.java) { cipher.decrypt(tampered) }
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(Exception::class.java) { AesGcmTokenCipher { otherKey }.decrypt(first) }
    }

    @Test fun `unreadable or foreign-key file fails closed with sanitized storage error`() {
        EncryptedCredentialStore(file, cipher).save(credentials())
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val failure = assertThrows(AuthException::class.java) { EncryptedCredentialStore(file, AesGcmTokenCipher { otherKey }).load() }
        assertEquals(AuthFailure.STORAGE, failure.reason)
        assertNull(failure.cause)
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(AuthFailure.STORAGE,
            assertThrows(AuthException::class.java) { EncryptedCredentialStore(file, cipher).load() }.reason)
    }

    @Test fun `interrupted save keeps last committed grant and clear removes everything`() {
        val store = EncryptedCredentialStore(file, cipher)
        store.save(credentials())
        val temporary = File(file.parentFile, "credentials.bin.tmp").apply { writeText("partial") }
        assertEquals("dummy-access", store.load()!!.accessToken)
        assertFalse(temporary.exists())
        store.clear()
        assertFalse(file.exists())
        assertNull(store.load())
    }

    private fun credentials() = StoredCredentials(DUMMY_CLIENT_ID, "owner", "acct-1", "dummy-access",
        "dummy-refresh", 1_000L, SpotifyAuth.scopes)
}
