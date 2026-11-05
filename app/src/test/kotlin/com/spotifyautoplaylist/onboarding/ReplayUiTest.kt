package com.spotifyautoplaylist.onboarding

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.spotifyautoplaylist.ui.*
import java.io.File
import org.junit.Assert.*
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
class ReplayUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `welcome has no bottom navigation and explains permissions`() {
        compose.setContent { ReplayTheme { OnboardingContent(OnboardingUiState(stage = OnboardingStage.WELCOME)) } }
        compose.onNodeWithText("Home").assertDoesNotExist()
        capture("welcome")
        compose.onNodeWithText("Kết nối Spotify").assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithText("Quyền truy cập & cách hoạt động").performScrollTo().performClick()
        compose.onNodeWithText("Quyền truy cập Spotify").assertIsDisplayed()
        // Release checklist privacy notice: local history storage + Spotify authorization (TASK-054).
        compose.onNodeWithText("Lịch sử nghe chỉ lưu trên điện thoại này", substring = true).assertIsDisplayed()
        compose.onNodeWithText("xin quyền đọc lượt phát gần đây", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Đã hiểu").performClick()
    }

    @Test fun `setup requires confirmation and never exposes tabs early`() {
        var created = 0
        compose.setContent { ReplayTheme { OnboardingContent(OnboardingUiState(stage = OnboardingStage.SETUP), onCreate = { created++ }) } }
        compose.onNodeWithText("Home").assertDoesNotExist()
        capture("setup")
        compose.onNodeWithText("Tạo playlist riêng tư").performScrollTo().performClick()
        assertEquals(0, created)
        compose.onNodeWithText("Tạo playlist trên Spotify?").assertIsDisplayed()
        compose.onNodeWithText("Hủy").performClick()
        assertEquals(0, created)
        compose.onNodeWithText("Tạo playlist riêng tư").performClick()
        compose.onNodeWithText("Xác nhận").performClick()
        assertEquals(1, created)
    }

    @Test fun `preview tabs navigate with selected semantics and preserve scroll`() {
        var exited = false
        compose.setContent { ReplayTheme { ReplayFoundation(preview = true, onExitPreview = { exited = true }) } }
        compose.onNodeWithText("Home").assertIsSelected()
        capture("foundation")
        compose.onNodeWithText("Mới vào bộ sưu tập").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lịch sử").performClick()
        compose.onNodeWithText("Lịch sử").assertIsSelected()
        compose.onNodeWithText("Chưa có lịch sử trên máy").assertIsDisplayed()
        compose.onNodeWithText("Bộ sưu tập").performClick()
        compose.onNode(hasText("Bộ sưu tập") and isSelected()).assertIsSelected()
        compose.onNodeWithText("Quy tắc").performClick()
        compose.onNode(hasText("Quy tắc") and isSelected()).assertIsSelected()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Mới vào bộ sưu tập").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mở cài đặt").performClick()
        compose.onNodeWithText("Cài đặt").assertIsDisplayed()
        compose.onNodeWithContentDescription("Quay lại").performClick()
        assertFalse(exited)
        compose.onNodeWithContentDescription("Quay lại").performClick()
        assertTrue(exited)
    }

    @Test @Config(qualifiers = "w320dp-h740dp")
    fun `large type on narrow screen keeps primary actions reachable and labels available`() {
        var previewed = false
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
                ReplayTheme { OnboardingContent(OnboardingUiState(stage = OnboardingStage.WELCOME, configured = false), onPreview = { previewed = true }) }
            }
        }
        capture("welcome-320-font130")
        compose.onNodeWithText("Kết nối Spotify").assertIsNotEnabled().assertIsDisplayed()
        compose.onNodeWithText("Xem thử giao diện").performClick()
        assertTrue(previewed)
    }

    @Test @Config(qualifiers = "w850dp-h393dp-land")
    fun `landscape setup scrolls to primary and recovery actions`() {
        compose.setContent { ReplayTheme { OnboardingContent(OnboardingUiState(stage = OnboardingStage.SETUP, recoveryRequired = true)) } }
        capture("setup-landscape")
        compose.onNodeWithText("Xác minh lại playlist").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tạo playlist riêng tư").assertDoesNotExist()
        compose.onNodeWithText("Xác minh và liên kết").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    /** Optional local visual evidence, always synthetic account-free UI, never a user's session. */
    private fun capture(name: String) {
        val path = System.getProperty("replay.screenshots").orEmpty()
        if (path.isBlank()) return
        // PixelCopy/forceRedraw needs a physical display. Draw the native Robolectric view instead.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val directory = File(path).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
