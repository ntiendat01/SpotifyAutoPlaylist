package com.spotifyautoplaylist.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.spotifyautoplaylist.data.local.CoverageStatus
import com.spotifyautoplaylist.data.sync.SyncRunStatus
import com.spotifyautoplaylist.library.*
import com.spotifyautoplaylist.library.LibraryFixtures.NOW
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
class HomeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun ready(rows: LibraryRows = LibraryFixtures.mixed()) = LibraryUiState.Ready(LibraryModel.build(rows, NOW))
    private fun show(state: LibraryUiState, retry: () -> Unit = {}, settings: () -> Unit = {}) =
        compose.setContent { ReplaySurface { HomeScreen(state, retry, onOpenPlaylist = {}, onOpenSettings = settings, onOpenTrack = null) } }

    @Test fun `home shows confirmed count, near tracks and only verified additions`() {
        var settings = 0
        show(ready(), settings = { settings++ })
        compose.onNodeWithText("1 bài đã xác minh trên Spotify").assertIsDisplayed()
        compose.onNodeWithText("Replay thử").assertIsDisplayed()
        compose.capture("050C-home")
        compose.onNodeWithText("Bài thử A").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2/3 hôm nay").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("4/5 trong 72 giờ").performScrollTo().assertIsDisplayed()
        // Pending/blocked/verifying tracks never appear as collected.
        compose.onNodeWithText("Bài thử C").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bài thử D").assertDoesNotExist()
        compose.onNodeWithText("Bài thử E").assertDoesNotExist()
        compose.onNodeWithText("Bài thử G").assertDoesNotExist()
        compose.onNodeWithContentDescription("T5 05/11: 15 lượt").performScrollTo().assertIsDisplayed()
        compose.capture("050C-home-pulse")
        compose.onNodeWithText("Đã đồng bộ", substring = true).performScrollTo().performClick()
        assertEquals(1, settings)
    }

    @Test fun `unknown count and partial coverage are explicit`() {
        show(ready(LibraryFixtures.rows(status = SyncRunStatus(), coverage = CoverageStatus.PARTIAL_BOOTSTRAP)))
        compose.onNodeWithText("Chưa xác minh số bài trên Spotify").assertIsDisplayed()
        compose.onNodeWithText("Chưa đồng bộ lần nào").assertIsDisplayed()
        compose.onNodeWithText("Lịch sử có thể chưa đầy đủ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Chưa có bài nào gần đạt ngưỡng").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Chưa có bài đã xác minh trên Spotify").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Chưa có lượt phát được ghi nhận.").performScrollTo().assertIsDisplayed()
        compose.capture("050C-home-empty")
    }

    @Test fun `offline keeps cached content with a warning strip`() {
        show(ready(LibraryFixtures.mixed().copy(status = SyncRunStatus(outcome = "NETWORK_ERROR", lastSuccessAtMs = NOW - 5 * LibraryFixtures.H))))
        compose.onNodeWithText("Không có mạng · Hiển thị dữ liệu trên máy").assertIsDisplayed()
        compose.onNodeWithText("1 bài đã xác minh trên Spotify").assertIsDisplayed()
        compose.capture("050C-home-offline")
    }

    @Test fun `loading, no account and error states`() {
        var retried = 0
        var state by androidx.compose.runtime.mutableStateOf<LibraryUiState>(LibraryUiState.Loading)
        compose.setContent { ReplaySurface { HomeScreen(state, { retried++ }, null, null, null) } }
        compose.onNodeWithText("Đang đọc dữ liệu trên máy…").assertIsDisplayed()
        state = LibraryUiState.NoAccount
        compose.onNodeWithText("Chưa kết nối Spotify").assertIsDisplayed()
        state = LibraryUiState.Error("Không đọc được dữ liệu trên máy.")
        compose.onNodeWithText("Không đọc được dữ liệu trên máy.").assertIsDisplayed()
        compose.onNodeWithText("Thử lại").performClick()
        assertEquals(1, retried)
    }

    @Test @Config(qualifiers = "w320dp-h740dp")
    fun `large font narrow screen keeps content reachable`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                ReplaySurface { HomeScreen(ready(), {}, {}, {}, null) }
            }
        }
        compose.onNodeWithText("Mở trong Spotify").assertIsDisplayed().assertHasClickAction()
        compose.capture("050C-home-large-font")
        compose.onNodeWithText("Nhịp nghe 7 ngày").performScrollTo().assertIsDisplayed()
    }
}
