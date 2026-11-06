package com.spotifyautoplaylist.onboarding

/**
 * Runtime Client ID + owner user ID gate (TASK-061). Production wires this to
 * [com.spotifyautoplaylist.spike.AppContainer.configureAuthIds]; tests may omit it.
 */
interface RuntimeAuthIds {
    fun isConfigured(): Boolean
    /** Masked Client ID / owner ID for Settings; never the raw values. */
    fun maskedIds(): Pair<String, String>?
    /**
     * Persists IDs. Changing an existing pair requires [confirmed]=true and wipes local account data.
     * First-time setup may pass confirmed=false.
     */
    suspend fun save(clientId: String, ownerUserId: String, confirmed: Boolean)
}
