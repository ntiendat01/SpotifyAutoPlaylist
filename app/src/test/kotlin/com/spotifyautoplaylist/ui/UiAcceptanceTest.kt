package com.spotifyautoplaylist.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.spotifyautoplaylist.data.sync.SyncRunStatus
import com.spotifyautoplaylist.library.*
import com.spotifyautoplaylist.library.LibraryFixtures.NOW
import com.spotifyautoplaylist.playlist.RelinkResult
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** TASK-050H: JVM evidence for UI-TC-003/005/013/015 across the data-backed screens. Not a device or TalkBack run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val actions = object : SettingsActions {
        override suspend fun syncNow() = true
        override suspend fun relink(input: String, confirmed: Boolean): RelinkResult = RelinkResult.Rejected(RelinkResult.Reason.UNAVAILABLE)
    }
    private val screens: Map<String, @Composable (LibraryUiState) -> Unit> = mapOf(
        "home" to { s -> HomeScreen(s, {}, {}, {}, null) },
        "history" to { s -> HistoryScreen(s, {}, {}) },
        "collection" to { s -> CollectionScreen(s, {}, {}, {}) },
        "rules" to { s -> RulesScreen(s, {}, { _, _ -> 0 }) },
        "settings" to { s -> SettingsScreen(s, {}, actions, "test", {}) {} },
    )

    private fun checkActions(name: String) {
        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("$name has actions", nodes.isNotEmpty())
        nodes.forEach { n ->
            val c = n.config
            val label = c.getOrNull(SemanticsProperties.Text)?.joinToString() ?: c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: n.children.firstNotNullOfOrNull { ch -> ch.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
            assertTrue("$name: unlabeled action #${n.id}", !label.isNullOrBlank())
            val h = with(Density(compose.density.density)) { maxOf(n.touchBoundsInRoot.height, n.size.height.toFloat()).toDp() }
            assertTrue("$name: '$label' touch height $h < 48dp", h >= 47.5.dp)
        }
    }

    @Test fun `every screen at 320dp and font 1_3 has labeled 48dp actions`() {
        val current = androidx.compose.runtime.mutableStateOf("home")
        val ready = LibraryUiState.Ready(LibraryModel.build(LibraryFixtures.mixed(), NOW))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                ReplaySurface { screens.getValue(current.value)(ready) }
            }
        }
        screens.keys.forEach { name ->
            current.value = name
            compose.waitForIdle()
            checkActions(name)
            compose.capture("050H-$name-320dp-font13")
        }
    }

    @Test fun `loading no account error and offline states on every screen`() {
        val state = androidx.compose.runtime.mutableStateOf<LibraryUiState>(LibraryUiState.Loading)
        val current = androidx.compose.runtime.mutableStateOf("home")
        compose.setContent { ReplaySurface { screens.getValue(current.value)(state.value) } }
        val offline = LibraryUiState.Ready(LibraryModel.build(LibraryFixtures.mixed().copy(
            status = SyncRunStatus(outcome = "NETWORK_ERROR", finishedAtMs = NOW, lastSuccessAtMs = NOW - 5 * LibraryFixtures.H)), NOW))
        screens.keys.forEach { name ->
            current.value = name
            state.value = LibraryUiState.Loading
            compose.onNodeWithText("Đang đọc dữ liệu trên máy…").assertExists()
            state.value = LibraryUiState.NoAccount
            compose.onNodeWithText("Chưa kết nối Spotify").assertExists()
            state.value = LibraryUiState.Error("Không đọc được dữ liệu trên máy.")
            compose.onNodeWithText("Không đọc được dữ liệu trên máy.").assertExists()
            compose.onNodeWithText("Thử lại", substring = true).assertExists()
            state.value = offline
            compose.waitForIdle()
            if (name in setOf("home", "settings")) compose.onAllNodesWithText("Không có mạng", substring = true).onFirst().assertExists()
            if (name == "settings") compose.capture("050H-settings-offline")
        }
    }

    @Test fun `zero data is honest on every screen`() {
        val current = androidx.compose.runtime.mutableStateOf("home")
        val empty = LibraryUiState.Ready(LibraryModel.build(LibraryFixtures.rows(playlist = null,
            status = SyncRunStatus(), coverage = com.spotifyautoplaylist.data.local.CoverageStatus.UNKNOWN), NOW))
        compose.setContent { ReplaySurface { screens.getValue(current.value)(empty) } }
        screens.keys.forEach { name ->
            current.value = name
            compose.waitForIdle()
            compose.onAllNodesWithText("Đã thêm vào Spotify").assertCountEquals(0)
            compose.capture("050H-$name-empty")
        }
    }
}
