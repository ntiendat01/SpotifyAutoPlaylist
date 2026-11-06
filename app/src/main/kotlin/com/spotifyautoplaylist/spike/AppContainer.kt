package com.spotifyautoplaylist.spike

import android.content.Context
import android.util.Log
import com.spotifyautoplaylist.data.LocalDataReset
import com.spotifyautoplaylist.data.auth.SpotifyAuthConfigRepository
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.settings.SettingsRepository
import com.spotifyautoplaylist.spotify.SpotifyClient
import com.spotifyautoplaylist.spotify.SpotifyHttpLogger
import java.io.File
import com.spotifyautoplaylist.auth.AuthConfig
import com.spotifyautoplaylist.auth.AuthException
import com.spotifyautoplaylist.auth.AuthFailure
import com.spotifyautoplaylist.auth.AuthState
import com.spotifyautoplaylist.onboarding.*
import java.net.URI
import androidx.work.WorkManager
import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.auth.AccountResetStep
import com.spotifyautoplaylist.data.sync.SyncStatusRepository
import com.spotifyautoplaylist.playlist.ManagedPlaylistVerifier
import com.spotifyautoplaylist.playlist.PlaylistRelinker
import com.spotifyautoplaylist.playlist.RelinkResult
import com.spotifyautoplaylist.playlist.OutboxWriter
import com.spotifyautoplaylist.playlist.PlaylistReconciler
import com.spotifyautoplaylist.rules.QualificationRecorder
import com.spotifyautoplaylist.spotify.sync.HistorySync
import com.spotifyautoplaylist.spotify.sync.asRecentlyPlayedSource
import com.spotifyautoplaylist.sync.RoomHistoryStore
import com.spotifyautoplaylist.sync.SyncOrchestrator
import com.spotifyautoplaylist.sync.SyncPass
import com.spotifyautoplaylist.sync.SyncRepository
import com.spotifyautoplaylist.sync.SyncScheduler
import com.spotifyautoplaylist.sync.boundAccount
import java.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * Process-wide production graph: one Room database, one settings DataStore and the auth manager whose
 * account reset deletes both. Used by the production onboarding UI (TASK-050B).
 * Spotify Client ID / owner user ID live in [authConfig] (runtime DataStore, optionally seeded from BuildConfig).
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: ReplayDatabase by lazy { ReplayDatabase.create(appContext) }

    val settings: SettingsRepository by lazy {
        SettingsRepository.create(File(appContext.noBackupFilesDir, "datastore/settings.preferences_pb"))
    }

    val syncStatus: SyncStatusRepository by lazy {
        SyncStatusRepository.create(File(appContext.noBackupFilesDir, "datastore/sync-status.preferences_pb"))
    }

    /** Installation-scoped Developer App IDs; not cleared by [LocalDataReset]. */
    val authConfig: SpotifyAuthConfigRepository by lazy {
        SpotifyAuthConfigRepository.create(File(appContext.noBackupFilesDir, "datastore/spotify-auth-config.preferences_pb"))
    }

    private val scheduler by lazy { SyncScheduler(WorkManager.getInstance(appContext)) }

    /**
     * Always constructed so runtime ID entry can enable login without rebuilding.
     * [AndroidAuth.isConfigured] is false until DataStore (or BuildConfig seed) provides both IDs.
     */
    val auth: AndroidAuth by lazy {
        val seeded = runBlocking {
            authConfig.ensureSeeded(BuildConfig.SPOTIFY_CLIENT_ID, BuildConfig.SPOTIFY_OWNER_USER_ID)
        }
        AndroidAuth.create(
            appContext,
            listOf(AccountResetStep { scheduler.cancel() }, LocalDataReset(database, settings, syncStatus)),
            seeded,
        )
    }

    /** Signed-in account or null; a sign-out with a reason (mismatch, reset pending, storage) is an auth error. */
    private suspend fun currentAccount(): AccountKey? {
        if (!auth.isConfigured) return null
        return when (val state = auth.manager.restore()) {
            is AuthState.SignedIn -> auth.manager.requireAccount()
            is AuthState.SignedOut -> state.reason?.let { throw AuthException(it) }
            AuthState.Restoring -> null
        }
    }

    /** Read model for Home/History/Collection (TASK-050C); account-scoped, local only. */
    val library: com.spotifyautoplaylist.library.LibrarySource by lazy {
        com.spotifyautoplaylist.library.RoomLibrarySource(::currentAccount, database, settings, syncStatus)
    }

    /** Rules Studio save (TASK-050F): DataStore + full re-evaluation; never removes qualifications. */
    suspend fun saveRules(daily: Int, rolling: Int): Int {
        val account = currentAccount() ?: throw IllegalStateException("no account")
        return QualificationRecorder(database, settings).changeThresholds(account, daily, rolling).newlyQualified.size
    }

    /**
     * Saves runtime Client ID + owner user ID. Changing either ID requires [confirmed]=true and runs
     * [com.spotifyautoplaylist.auth.SpotifyAuthManager.resetAccount] so old tokens/bindings cannot mix.
     * First-time setup (no previous IDs) does not need confirmation.
     */
    suspend fun configureAuthIds(clientId: String, ownerUserId: String, confirmed: Boolean): AuthConfig {
        val previous = auth.configOrNull()
        val trimmedClient = clientId.trim()
        val trimmedOwner = ownerUserId.trim()
        val changing = previous != null &&
            (previous.clientId != trimmedClient || previous.expectedOwnerId != trimmedOwner)
        if (changing) {
            if (!confirmed) throw AuthException(AuthFailure.CONFIRMATION_REQUIRED)
            auth.manager.resetAccount(confirmed = true)
        }
        val config = authConfig.save(trimmedClient, trimmedOwner)
        auth.replaceConfig(config)
        return config
    }

    val syncOrchestrator: SyncOrchestrator by lazy {
        val api = spotify
        val recorder = QualificationRecorder(database, settings)
        val history = HistorySync(api.asRecentlyPlayedSource(), RoomHistoryStore(database), Instant::now)
        val requireAccount: suspend () -> AccountKey = auth.manager::requireAccount
        SyncOrchestrator(::currentAccount, object : SyncPass {
            override suspend fun history(userId: String) = history.sync(userId)
            override suspend fun evaluate(account: AccountKey) = recorder.evaluate(account).newlyQualified.size
            override suspend fun autoSyncEnabled(account: AccountKey) = settings.current(account).autoSyncEnabled
            override fun writer(account: AccountKey): OutboxWriter {
                val requireAccount = boundAccount(account, requireAccount)
                val verifier = ManagedPlaylistVerifier(requireAccount, { database.playlists().managed(it) }, api::playlist)
                val reconciler = PlaylistReconciler(requireAccount, verifier, { id, offset -> api.playlistItems(id, offset) }, database)
                return OutboxWriter(requireAccount, reconciler, database, api::addTracks)
            }
        }, syncStatus, log = { if (BuildConfig.DEBUG) Log.i("ReplaySync", it) })
    }

    val sync: SyncRepository by lazy {
        SyncRepository(
            account = { try { currentAccount() } catch (_: AuthException) { null } },
            hasVerifiedPlaylist = { database.playlists().managed(it.spotifyUserId)?.ownershipVerified == true },
            scheduler = scheduler,
            status = syncStatus,
            relink = relink@{ key, input, confirmed ->
                val orchestrator = syncOrchestrator
                val api = spotify
                val requireAccount: suspend () -> AccountKey = auth.manager::requireAccount
                val verifier = ManagedPlaylistVerifier(requireAccount, { database.playlists().managed(it) }, api::playlist)
                orchestrator.exclusive(key) {
                    if (requireAccount() != key) RelinkResult.Rejected(RelinkResult.Reason.ACCOUNT_CHANGED)
                    else PlaylistRelinker(requireAccount, database, verifier, api::playlist).relink(input, confirmed)
                }
            },
        )
    }

    /** Logs only sanitized events, and only in debug. */
    val spotify: SpotifyClient by lazy {
        val logger = if (BuildConfig.DEBUG) SpotifyHttpLogger { Log.i("SpotifyApi", it.toString()) } else SpotifyHttpLogger.NONE
        SpotifyClient.create(auth.manager, logger)
    }

    val onboardingAuth: OnboardingAuth by lazy {
        object : OnboardingAuth {
            override val state = auth.manager.state
            override suspend fun restore(): AuthState =
                if (!auth.isConfigured) AuthState.SignedOut(AuthFailure.NOT_CONFIGURED) else auth.manager.restore()
            override suspend fun login(openBrowser: suspend (URI) -> Unit, verifying: () -> Unit) {
                val input = auth.authorization().authorize(openBrowser)
                verifying()
                auth.manager.completeLogin(input)
            }
            override suspend fun signOut() { scheduler.cancel(); auth.manager.signOut() }
        }
    }

    val playlistSetup: PlaylistSetup by lazy {
        PlaylistSetupRepository(
            auth.manager::requireAccount,
            object : SetupApi {
                override suspend fun create(name: String, description: String) = spotify.createPlaylist(name, description)
                override suspend fun get(id: String) = spotify.playlist(id)
                override suspend fun ownPlaylists(offset: Int) = spotify.myPlaylists(offset)
            },
            RoomSetupPersistence(database, FileCreationJournal(File(appContext.noBackupFilesDir, "playlist-setup"))),
        )
    }
}
