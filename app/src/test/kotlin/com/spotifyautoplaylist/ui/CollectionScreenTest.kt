package com.spotifyautoplaylist.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spotifyautoplaylist.data.sync.SyncRunStatus
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
class CollectionScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun show(rows: LibraryRows) = compose.setContent {
        ReplaySurface { CollectionScreen(LibraryUiState.Ready(LibraryModel.build(rows, NOW)), {}, {}, {}) }
    }

    @Test fun `segments keep added and pending apart with verification time`() {
        show(LibraryFixtures.mixed())
        compose.onNodeWithText("Xác minh trên Spotify lần cuối", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Đã thêm (1)").assertIsSelected()
        compose.onNodeWithText("Bài thử C").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bài thử D").assertDoesNotExist()
        compose.capture("050E-collection")
        compose.onNodeWithText("Đang chờ (2)").performClick()
        compose.onNodeWithText("Bài thử D").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bài thử C").assertDoesNotExist()
        compose.capture("050E-collection-pending")
        compose.onNodeWithText("Cần kiểm tra (1)").performScrollTo().performClick()
        compose.onNodeWithText("Bài thử E").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ứng dụng không bao giờ tự xóa bài khỏi playlist.").performScrollTo().assertIsDisplayed()
    }

    @Test fun `never verified playlist shows unknown, not zero`() {
        show(LibraryFixtures.rows(status = SyncRunStatus()))
        compose.onNodeWithText("Chưa xác minh số bài trên Spotify").assertIsDisplayed()
        compose.onNodeWithText("Chưa kiểm tra playlist trên Spotify").assertIsDisplayed()
        compose.onNodeWithText("Chưa có bài đã xác minh trên Spotify").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cần kiểm tra (0)").performScrollTo().performClick()
        compose.onNodeWithText("Không có bài cần kiểm tra").assertIsDisplayed()
    }
}
