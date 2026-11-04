package com.spotifyautoplaylist.spike

import android.app.Application

/** Exactly one auth mutex, API client, Room and DataStore graph across activity recreation. */
class ReplayApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
