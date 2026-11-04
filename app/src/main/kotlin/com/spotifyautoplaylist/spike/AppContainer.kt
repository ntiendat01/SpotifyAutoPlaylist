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

    /** Null when the build has no Spotify configuration. */
    val auth: AndroidAuth? by lazy { AndroidAuth.create(appContext, listOf(LocalDataReset(database, settings))) }

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
            override suspend fun signOut() = current.manager.signOut()
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
