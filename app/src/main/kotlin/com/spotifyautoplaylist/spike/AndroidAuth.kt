package com.spotifyautoplaylist.spike

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.spotifyautoplaylist.auth.AccountResetStep
import com.spotifyautoplaylist.auth.AesGcmTokenCipher
import com.spotifyautoplaylist.auth.AuthConfig
import com.spotifyautoplaylist.auth.AuthorizationFlow
import com.spotifyautoplaylist.auth.EncryptedCredentialStore
import com.spotifyautoplaylist.auth.FileAccountBindingStore
import com.spotifyautoplaylist.auth.SpotifyAuthManager
import com.spotifyautoplaylist.auth.SpotifyIdentityClient
import com.spotifyautoplaylist.auth.SpotifyTokenClient
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Production auth wiring. Owned once per process by ReplayApplication.
 * [resetSteps] will cancel workers (TASK-051) and delete account-scoped Room/DataStore data (TASK-021).
 */
class AndroidAuth private constructor(val config: AuthConfig, context: Context, resetSteps: List<AccountResetStep>) {
    val manager = SpotifyAuthManager(
        config,
        EncryptedCredentialStore(
            File(context.noBackupFilesDir, "auth/credentials.bin"),
            AesGcmTokenCipher(::credentialKey),
        ),
        FileAccountBindingStore(File(context.noBackupFilesDir, "auth/account-binding.json")),
        SpotifyTokenClient(),
        SpotifyIdentityClient(),
        resetSteps,
    )
    val authorization = AuthorizationFlow(config)

    companion object {
        private const val KEY_ALIAS = "replay.credentials.v1"

        /** Null when this build has no Spotify configuration. */
        fun create(context: Context, resetSteps: List<AccountResetStep> = emptyList()): AndroidAuth? =
            AuthConfig.fromBuild(BuildConfig.SPOTIFY_CLIENT_ID, BuildConfig.SPOTIFY_OWNER_USER_ID)
                ?.let { AndroidAuth(it, context.applicationContext, resetSteps) }

        @Synchronized
        private fun credentialKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
        }
    }
}
