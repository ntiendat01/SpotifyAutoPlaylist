package com.spotifyautoplaylist.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class RulesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val saved = mutableListOf<Pair<Int, Int>>()
    private fun show(save: SaveRules? = { d, r -> saved += d to r; 2 }) = compose.setContent {
        ReplaySurface { RulesScreen(LibraryUiState.Ready(LibraryModel.build(LibraryFixtures.mixed(), NOW)), {}, save) }
    }
    private fun saveButton() = compose.onNodeWithText("Lưu quy tắc")

    @Test fun `draft preview save`() {
        show()
        compose.onNodeWithText("HOẶC").assertIsDisplayed()
        compose.onNodeWithText("chỉ tính lượt nghe từ 30 giây trở lên", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Ngưỡng theo ngày: 3 lượt trong một ngày").assertIsDisplayed()
        saveButton().performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Giảm Ngưỡng theo ngày").performClick()
        compose.onNodeWithText("2 bài sẽ đủ điều kiện thêm", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("• Bài thử A", substring = true).assertExists()
        compose.capture("050F-rules")
        saveButton().performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf(2 to 5), saved)
        compose.onNodeWithText("Đã lưu. Bài trong playlist không bị xóa.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `bounds disable steppers and discard restores`() {
        show()
        compose.onNodeWithContentDescription("Giảm Ngưỡng theo ngày").performClick()
        compose.onNodeWithContentDescription("Giảm Ngưỡng theo ngày").assertIsNotEnabled()
        compose.onNodeWithText("Bỏ thay đổi").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Ngưỡng theo ngày: 3 lượt trong một ngày").assertExists()
        saveButton().assertIsNotEnabled()
    }

    @Test fun `back with draft asks to discard`() {
        show()
        compose.onNodeWithContentDescription("Tăng Ngưỡng 72 giờ").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Ngưỡng 72 giờ: 6 lượt trong 72 giờ").assertExists()
        compose.runOnUiThread { assert(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()) { "no callback" } }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Bỏ thay đổi?").assertExists()
        compose.onNodeWithText("Tiếp tục sửa").performClick()
        compose.onNodeWithContentDescription("Ngưỡng 72 giờ: 6 lượt trong 72 giờ").assertExists()
    }

    @Test fun `save failure keeps draft with sanitized error`() {
        show { _, _ -> throw IllegalStateException("token=secret") }
        compose.onNodeWithContentDescription("Giảm Ngưỡng theo ngày").performClick()
        saveButton().performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Không lưu được quy tắc", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("secret", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Ngưỡng theo ngày: 2 lượt trong một ngày").assertExists()
    }
}
