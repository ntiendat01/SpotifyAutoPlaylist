package com.spotifyautoplaylist.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spotifyautoplaylist.data.sync.SyncRunStatus
import com.spotifyautoplaylist.library.*
import com.spotifyautoplaylist.library.LibraryFixtures.NOW
import com.spotifyautoplaylist.playlist.RelinkResult
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
class SettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var syncCalls = 0
    private val relinks = mutableListOf<Pair<String, Boolean>>()
    private val actions = object : SettingsActions {
        override suspend fun syncNow(): Boolean { syncCalls++; return true }
        override suspend fun relink(input: String, confirmed: Boolean): RelinkResult { relinks += input to confirmed; return RelinkResult.Rejected(RelinkResult.Reason.PRIVACY) }
    }
    private val rows = mutableStateOf(LibraryFixtures.mixed())
    private fun show(a: SettingsActions? = actions) = compose.setContent {
        ReplaySurface { SettingsScreen(LibraryUiState.Ready(LibraryModel.build(rows.value, NOW)), {}, a, "test", null) {} }
    }

    @Test fun `health diagnostics and manual sync result`() {
        show()
        compose.onNodeWithText("Đã đồng bộ lúc", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Đã thêm (đã xác minh) 1 · Đang chờ 1", substring = true).assertIsDisplayed()
        compose.capture("050G-settings")
        compose.onNodeWithText("app=test", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Sao chép chẩn đoán").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Xem chi tiết chẩn đoán").performScrollTo().performClick()
        compose.onNodeWithText("app=test", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ẩn chi tiết chẩn đoán").performScrollTo().performClick()
        compose.onNodeWithText("Đồng bộ ngay").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, syncCalls)
        compose.onNodeWithText("Đã yêu cầu đồng bộ. Đang chờ kết quả…").performScrollTo().assertIsDisplayed()
        rows.value = rows.value.copy(status = SyncRunStatus(outcome = "NETWORK_ERROR", finishedAtMs = System.currentTimeMillis() + 60_000, lastSuccessAtMs = NOW - LibraryFixtures.H))
        compose.onNodeWithText("Kết quả đồng bộ thủ công: Không có mạng", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bài thử", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Liên kết lại playlist").assertDoesNotExist()
        compose.capture("050G-sync-result")
    }

    @Test fun `relink entry only when required and needs confirmation`() {
        rows.value = rows.value.copy(status = SyncRunStatus(outcome = "PARTIAL", errorCode = "RELINK_REQUIRED", finishedAtMs = NOW))
        show()
        compose.onNodeWithText("Liên kết lại playlist").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Liên kết playlist Spotify").performScrollTo().performTextInput("https://open.spotify.com/playlist/x")
        compose.onNodeWithText("Liên kết lại").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Tôi xác nhận", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Liên kết lại").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf("https://open.spotify.com/playlist/x" to true), relinks)
        compose.onNodeWithText("Playlist phải ở chế độ riêng tư", substring = true).performScrollTo().assertIsDisplayed()
        compose.capture("050G-relink")
    }

    @Test fun `no spotify config disables sync now`() {
        show(null)
        compose.onNodeWithText("Đồng bộ ngay").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("chưa cấu hình Spotify", substring = true).assertExists()
    }
}
