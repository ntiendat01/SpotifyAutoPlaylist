package com.spotifyautoplaylist.spike

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.spotifyautoplaylist.auth.AccountResetStep
import com.spotifyautoplaylist.auth.AesGcmTokenCipher
import com.spotifyautoplaylist.auth.AuthConfig
import com.spotifyautoplaylist.auth.AuthException
import com.spotifyautoplaylist.auth.AuthFailure
import com.spotifyautoplaylist.auth.AuthorizationFlow
import com.spotifyautoplaylist.auth.EncryptedCredentialStore
import com.spotifyautoplaylist.auth.FileAccountBindingStore
import com.spotifyautoplaylist.auth.SpotifyAuthManager
import com.spotifyautoplaylist.auth.SpotifyIdentityClient
import com.spotifyautoplaylist.auth.SpotifyTokenClient
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Production auth wiring. Owned once per process by ReplayApplication.
 * [AuthConfig] may be supplied later (runtime DataStore / Settings) without rebuilding the APK;
 * [replaceConfig] updates the live provider used by login and token refresh.
 * [resetSteps] cancel workers and delete account-scoped Room/DataStore data.
 */
class AndroidAuth private constructor(
    context: Context,
    resetSteps: List<AccountResetStep>,
    initial: AuthConfig?,
) {
    private val configRef = AtomicReference(initial)

    /** Null until BuildConfig seed or an in-app save provides both IDs. */
    fun configOrNull(): AuthConfig? = configRef.get()

    val isConfigured: Boolean get() = configRef.get() != null

    /** Replaces the live AuthConfig after a confirmed ID change (or first-time setup). */
    fun replaceConfig(config: AuthConfig?) {
        configRef.set(config)
    }

    val manager = SpotifyAuthManager(
        configProvider = {
            configRef.get() ?: throw AuthException(AuthFailure.NOT_CONFIGURED)
        },
        EncryptedCredentialStore(
            File(context.noBackupFilesDir, "auth/credentials.bin"),
            AesGcmTokenCipher(::credentialKey),
        ),
        FileAccountBindingStore(File(context.noBackupFilesDir, "auth/account-binding.json")),
        SpotifyTokenClient(),
        SpotifyIdentityClient(),
        resetSteps,
    )

    /** Builds a fresh PKCE flow against the current Client ID. */
    fun authorization(): AuthorizationFlow {
        val config = configRef.get() ?: throw AuthException(AuthFailure.NOT_CONFIGURED)
        return AuthorizationFlow(config)
    }

    companion object {
        private const val KEY_ALIAS = "replay.credentials.v1"

        /** Always constructs stores; [initial] may be null when no IDs are configured yet. */
        fun create(
            context: Context,
            resetSteps: List<AccountResetStep> = emptyList(),
            initial: AuthConfig? = null,
        ): AndroidAuth = AndroidAuth(context.applicationContext, resetSteps, initial)

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
