package com.spotifyautoplaylist.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spotifyautoplaylist.library.*
import com.spotifyautoplaylist.library.LibraryFixtures.NOW
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
class HistoryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ready = LibraryUiState.Ready(LibraryModel.build(LibraryFixtures.mixed(), NOW))

    @Test fun `search, filter, detail and back restore state`() {
        compose.setContent { ReplayTheme { ReplayFoundation(preview = false, library = ready, onOpenUrl = {}) } }
        compose.onNodeWithText("Lịch sử").performClick()
        compose.onNodeWithText("7 bài", substring = true).assertIsDisplayed()
        compose.capture("050D-history")
        compose.onNode(hasText("Gần đạt") and hasClickAction()).performClick()
        compose.onNode(hasText("Gần đạt") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("2 bài", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Tìm bài hát hoặc nghệ sĩ").performTextInput("bai thu b")
        compose.onNodeWithText("1 bài", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Bài thử A").assertDoesNotExist()
        compose.onNodeWithText("Bài thử B").performClick()
        compose.onNodeWithText("Lượt phát Spotify ghi nhận không đồng nghĩa nghe hết bài").assertIsDisplayed()
        compose.onNodeWithText("Các lượt phát (4)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mở bài trong Spotify").assertHasClickAction()
        compose.capture("050D-track-detail")
        compose.onNodeWithContentDescription("Quay lại").performClick()
        compose.onNodeWithText("1 bài", substring = true).assertIsDisplayed()
        compose.onNode(hasText("Gần đạt") and hasClickAction()).assertIsSelected()
    }

    @Test fun `no match and empty history states`() {
        compose.setContent { ReplaySurface { HistoryScreen(ready, {}, {}) } }
        compose.onNodeWithText("Tìm bài hát hoặc nghệ sĩ").performTextInput("zzz")
        compose.onNodeWithText("Không có bài phù hợp").assertIsDisplayed()
    }

    @Test fun `empty account shows explicit empty history`() {
        val empty = LibraryUiState.Ready(LibraryModel.build(LibraryFixtures.rows(), NOW))
        compose.setContent { ReplaySurface { HistoryScreen(empty, {}, {}) } }
        compose.onNodeWithText("Chưa có lịch sử trên máy").assertIsDisplayed()
    }

    @Test fun `unknown track id in detail is explicit`() {
        compose.setContent { ReplaySurface { TrackDetailScreen(ready, "missing", {}, null) } }
        compose.onNodeWithText("Không tìm thấy bài trên máy").assertIsDisplayed()
    }
}
