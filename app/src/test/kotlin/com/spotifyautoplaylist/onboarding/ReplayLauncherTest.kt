package com.spotifyautoplaylist.onboarding

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spotifyautoplaylist.spike.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Exercises the real manifest Application/container/launcher; no login or external writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h850dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReplayLauncherTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun `fresh real launcher enters account-free preview and returns to welcome`() {
        compose.onNodeWithText("Xem thử giao diện").performClick()
        compose.onNodeWithText("Home").assertIsSelected()
        compose.onNodeWithText("Xem trước giao diện · Không kết nối Spotify. Không có dữ liệu tài khoản.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Quay lại").performClick()
        compose.onNodeWithText("Kết nối Spotify").assertIsDisplayed()
        compose.onNodeWithText("Home").assertDoesNotExist()
    }
}
