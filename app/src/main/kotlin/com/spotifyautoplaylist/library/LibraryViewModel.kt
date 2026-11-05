package com.spotifyautoplaylist.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.auth.AuthException
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.settings.SettingsRepository
import com.spotifyautoplaylist.data.sync.SyncStatusRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface LibraryLoad {
    data object NoAccount : LibraryLoad
    data object AuthProblem : LibraryLoad
    data class Loaded(val rows: LibraryRows) : LibraryLoad
}

/** Read side for the main tabs; production reads Room/DataStore for the signed-in account only. */
interface LibrarySource {
    suspend fun load(): LibraryLoad
    /** Emits whenever the persisted sync status or the account's rules change. */
    fun changes(): Flow<Unit>
}

class RoomLibrarySource(
    private val account: suspend () -> AccountKey?,
    private val database: ReplayDatabase,
    private val settings: SettingsRepository,
    private val status: SyncStatusRepository,
) : LibrarySource {
    override suspend fun load(): LibraryLoad {
        val key = try { account() } catch (_: AuthException) { return LibraryLoad.AuthProblem } ?: return LibraryLoad.NoAccount
        val id = key.spotifyUserId
        val prefs = settings.current(key)
        return LibraryLoad.Loaded(LibraryRows(
            tracks = database.history().tracks(id), events = database.history().allEvents(id),
            qualifications = database.qualifications().all(id), outbox = database.playlists().outboxAll(id),
            playlist = database.playlists().managed(id), syncState = database.syncState().get(id),
            dailyThreshold = prefs.dailyThreshold, rollingThreshold = prefs.rollingThreshold, status = status.current(key),
        ))
    }

    override fun changes(): Flow<Unit> = flow {
        val key = try { account() } catch (_: AuthException) { null } ?: return@flow
        emitAll(combine(status.status(key), settings.settings(key)) { _, _ -> })
    }
}

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data object NoAccount : LibraryUiState
    data class Error(val message: String) : LibraryUiState
    data class Ready(val snapshot: LibrarySnapshot) : LibraryUiState
}

class LibraryViewModel(private val source: LibrarySource, private val clock: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val mutable = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val state: StateFlow<LibraryUiState> = mutable.asStateFlow()
    private val lock = Mutex()

    init {
        viewModelScope.launch {
            refreshNow()
            source.changes().collect { refreshNow() }
        }
    }

    fun refresh() { viewModelScope.launch { refreshNow() } }

    private suspend fun refreshNow() = lock.withLock {
        mutable.value = try {
            when (val load = source.load()) {
                LibraryLoad.NoAccount -> LibraryUiState.NoAccount
                LibraryLoad.AuthProblem -> LibraryUiState.Error("Cần đăng nhập lại hoặc tài khoản không khớp. Dữ liệu trên máy được giữ nguyên.")
                is LibraryLoad.Loaded -> LibraryUiState.Ready(LibraryModel.build(load.rows, clock()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep cached content if we had it; never show raw exception text.
            (mutable.value as? LibraryUiState.Ready) ?: LibraryUiState.Error("Không đọc được dữ liệu trên máy.")
        }
    }
}
