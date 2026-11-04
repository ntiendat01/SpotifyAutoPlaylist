package com.spotifyautoplaylist.spike

import android.content.Context
import android.util.Log
import com.spotifyautoplaylist.data.LocalDataReset
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.settings.SettingsRepository
import com.spotifyautoplaylist.spotify.SpotifyClient
import com.spotifyautoplaylist.spotify.SpotifyHttpLogger
import java.io.File

/**
 * Process-wide production graph: one Room database, one settings DataStore and the auth manager whose
 * account reset deletes both. Not used by the M0 probe screen yet (TASK-050B wires it into the UI).
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
}
