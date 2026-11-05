package com.spotifyautoplaylist.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spotifyautoplaylist.library.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h850dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `selected Material containers stay in Replay palette`() {
        compose.setContent { ReplayTheme {
            assertEquals(ReplayTokens.Selected, MaterialTheme.colorScheme.secondaryContainer)
            assertEquals(ReplayTokens.Text, MaterialTheme.colorScheme.onSecondaryContainer)
            assertEquals(ReplayTokens.Elevated, MaterialTheme.colorScheme.tertiaryContainer)
        } }
    }

    @Test fun `busy action stays non clickable with visible progress and disabled variant`() {
        compose.setContent { ReplaySurface { Column {
            PrimaryActionButton("Sẵn sàng", {})
            PrimaryActionButton("Đang xử lý", {}, busy = true)
            PrimaryActionButton("Chưa sẵn sàng", {}, enabled = false)
        } } }
        compose.onNodeWithText("Sẵn sàng").assertIsEnabled()
        compose.onNodeWithText("Đang xử lý").assertIsNotEnabled()
        compose.onNodeWithText("Chưa sẵn sàng").assertIsNotEnabled()
        compose.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.capture("refresh-button-states")
    }

    @Test fun `long history returns from detail and restores tab scroll`() {
        val snapshot = LibraryModel.build(LibraryFixtures.mixed(), LibraryFixtures.NOW)
        val seed = snapshot.tracks.first()
        val ready = LibraryUiState.Ready(snapshot.copy(tracks = (0 until 100).map {
            seed.copy(trackId = "synthetic-$it", name = "Bài kiểm thử $it")
        }))
        compose.setContent { ReplayTheme { ReplayFoundation(preview = false, library = ready) } }
        compose.onNodeWithText("Lịch sử").performClick()
        compose.onNodeWithTag("library-list").performScrollToNode(hasText("Bài kiểm thử 70"))
        compose.onNodeWithText("Bài kiểm thử 70").performClick()
        compose.onNodeWithContentDescription("Quay lại").performClick()
        compose.onNodeWithText("Bài kiểm thử 70").assertIsDisplayed()
        compose.onNodeWithText("Quy tắc").performClick()
        compose.onNodeWithText("Lịch sử").performClick()
        compose.onNodeWithText("Bài kiểm thử 70").assertIsDisplayed()
        compose.capture("refresh-history-scroll-restored")
    }

    @Test @Config(qualifiers = "w320dp-h740dp")
    fun `font 2 keeps screen actions and list rows reachable`() {
        val ready = LibraryUiState.Ready(LibraryModel.build(LibraryFixtures.mixed(), LibraryFixtures.NOW))
        val current = mutableStateOf("home")
        val actions = object : SettingsActions {
            override suspend fun syncNow() = true
            override suspend fun relink(input: String, confirmed: Boolean) =
                com.spotifyautoplaylist.playlist.RelinkResult.Rejected(com.spotifyautoplaylist.playlist.RelinkResult.Reason.UNAVAILABLE)
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                ReplaySurface { key(current.value) {
                    when (current.value) {
                        "home" -> HomeScreen(ready, {}, {}, {}, {})
                        "history" -> HistoryScreen(ready, {}, {})
                        "collection" -> CollectionScreen(ready, {}, {}, {})
                        "rules" -> RulesScreen(ready, {}, { _, _ -> 0 })
                        else -> SettingsScreen(ready, {}, actions, "test", {}) {}
                    }
                } }
            }
        }
        for (screen in listOf("home", "history", "collection", "rules", "settings")) {
            current.value = screen
            compose.waitForIdle()
            when (screen) {
                "home" -> compose.onNodeWithText("Mở trong Spotify").performScrollTo().assertIsDisplayed()
                "history", "collection" -> {
                    compose.onNodeWithTag("library-list").performScrollToNode(hasText("Bài thử C"))
                    compose.onNodeWithText("Bài thử C").assertIsDisplayed()
                }
                "rules" -> compose.onNodeWithText("Lưu quy tắc").performScrollTo().assertIsDisplayed()
                "settings" -> compose.onNodeWithText("Đồng bộ ngay").performScrollTo().assertIsDisplayed()
            }
            compose.capture("refresh-$screen-320dp-font2")
        }
    }

    @Test @Config(qualifiers = "w850dp-h393dp-land")
    fun `landscape retains tabs search rules and settings actions`() {
        val ready = LibraryUiState.Ready(LibraryModel.build(LibraryFixtures.mixed(), LibraryFixtures.NOW))
        compose.setContent { ReplayTheme { ReplayFoundation(preview = false, library = ready,
            onOpenPlaylist = {}, onSaveRules = { _, _ -> 0 }) } }
        compose.onNodeWithText("Mở trong Spotify").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lịch sử").performClick()
        compose.onNodeWithTag("library-list").performScrollToNode(hasText("Tìm bài hát, nghệ sĩ"))
        compose.onNodeWithText("Tìm bài hát, nghệ sĩ").performTextInput("Bài thử A")
        compose.capture("refresh-history-landscape")
        compose.onNode(hasText("Quy tắc") and hasClickAction()).performClick()
        compose.onNodeWithText("Lưu quy tắc").performScrollTo().assertIsDisplayed()
        compose.capture("refresh-rules-landscape")
    }
}
