package com.spotifyautoplaylist.spike

import android.content.Context
import android.util.Log
import com.spotifyautoplaylist.data.LocalDataReset
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.settings.SettingsRepository
import com.spotifyautoplaylist.spotify.SpotifyClient
import com.spotifyautoplaylist.spotify.SpotifyHttpLogger
import java.io.File
import com.spotifyautoplaylist.auth.AuthState
import com.spotifyautoplaylist.onboarding.*
import java.net.URI
import androidx.work.WorkManager
import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.auth.AccountResetStep
import com.spotifyautoplaylist.auth.AuthException
import com.spotifyautoplaylist.data.sync.SyncStatusRepository
import com.spotifyautoplaylist.playlist.ManagedPlaylistVerifier
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
import java.time.Instant

/**
 * Process-wide production graph: one Room database, one settings DataStore and the auth manager whose
 * account reset deletes both. Used by the production onboarding UI (TASK-050B).
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

    private val scheduler by lazy { SyncScheduler(WorkManager.getInstance(appContext)) }

    /** Null when the build has no Spotify configuration. Account reset stops sync work before deleting data. */
    val auth: AndroidAuth? by lazy {
        AndroidAuth.create(appContext, listOf(AccountResetStep { scheduler.cancel() }, LocalDataReset(database, settings, syncStatus)))
    }

    /** Signed-in account or null; a sign-out with a reason (mismatch, reset pending, storage) is an auth error. */
    private suspend fun currentAccount(): AccountKey? {
        val manager = auth?.manager ?: return null
        return when (val state = manager.restore()) {
            is AuthState.SignedIn -> manager.requireAccount()
            is AuthState.SignedOut -> state.reason?.let { throw AuthException(it) }
            AuthState.Restoring -> null
        }
    }

    /** Null without Spotify configuration. */
    val syncOrchestrator: SyncOrchestrator? by lazy {
        val current = auth
        val api = spotify
        if (current == null || api == null) null else {
            val recorder = QualificationRecorder(database, settings)
            val history = HistorySync(api.asRecentlyPlayedSource(), RoomHistoryStore(database), Instant::now)
            val requireAccount: suspend () -> AccountKey = current.manager::requireAccount
            SyncOrchestrator(::currentAccount, object : SyncPass {
                override suspend fun history(userId: String) = history.sync(userId)
                override suspend fun evaluate(account: AccountKey) = recorder.evaluate(account).newlyQualified.size
                override suspend fun autoSyncEnabled(account: AccountKey) = settings.current(account).autoSyncEnabled
                override fun writer(account: AccountKey): OutboxWriter {
                    val verifier = ManagedPlaylistVerifier(requireAccount, { database.playlists().managed(it) }, api::playlist)
                    val reconciler = PlaylistReconciler(requireAccount, verifier, { id, offset -> api.playlistItems(id, offset) }, database)
                    return OutboxWriter(requireAccount, reconciler, database, api::addTracks)
                }
            }, syncStatus, log = { if (BuildConfig.DEBUG) Log.i("ReplaySync", it) })
        }
    }

    /** Null without Spotify configuration. */
    val sync: SyncRepository? by lazy {
        if (auth == null) null else SyncRepository(
            account = { try { currentAccount() } catch (_: AuthException) { null } },
            hasVerifiedPlaylist = { database.playlists().managed(it.spotifyUserId)?.ownershipVerified == true },
            scheduler = scheduler,
            status = syncStatus,
        )
    }

    /** Null when the build has no Spotify configuration. Logs only sanitized events, and only in debug. */
    val spotify: SpotifyClient? by lazy {
        val logger = if (BuildConfig.DEBUG) SpotifyHttpLogger { Log.i("SpotifyApi", it.toString()) } else SpotifyHttpLogger.NONE
        auth?.let { SpotifyClient.create(it.manager, logger) }
    }

    val onboardingAuth: OnboardingAuth? by lazy {
        auth?.let { current -> object : OnboardingAuth {
            override val state = current.manager.state
            override suspend fun restore(): AuthState = current.manager.restore()
            override suspend fun login(openBrowser: suspend (URI) -> Unit, verifying: () -> Unit) {
                val input = current.authorization.authorize(openBrowser)
                verifying()
                current.manager.completeLogin(input)
            }
            override suspend fun signOut() { scheduler.cancel(); current.manager.signOut() }
        } }
    }

    val playlistSetup: PlaylistSetup? by lazy {
        val current = auth
        val api = spotify
        if (current == null || api == null) null else PlaylistSetupRepository(
            current.manager::requireAccount,
            object : SetupApi {
                override suspend fun create(name: String, description: String) = api.createPlaylist(name, description)
                override suspend fun get(id: String) = api.playlist(id)
                override suspend fun ownPlaylists(offset: Int) = api.myPlaylists(offset)
            },
            RoomSetupPersistence(database, FileCreationJournal(File(appContext.noBackupFilesDir, "playlist-setup"))),
        )
    }
}
