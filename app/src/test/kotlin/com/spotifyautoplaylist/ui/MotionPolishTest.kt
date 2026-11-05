package com.spotifyautoplaylist.ui

import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class MotionPolishTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `system animator scale zero means reduced motion`() {
        Settings.Global.putFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var seen: Boolean? = null
        compose.setContent { seen = reducedMotion() }
        compose.waitForIdle()
        assertEquals(true, seen)
    }

    @Test fun `progress exposes exact value while animating and with reduced motion`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { ReplaySurface { CompositionLocalProvider(LocalReducedMotion provides false) {
            PlaylistHero("Replay", 1) { ThresholdProgress(2, 4, "lần nghe") } } } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(.5f, 0f..1f))).assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.capture("050I-hero-glow")
    }

    @Test fun `reduced motion renders final state immediately`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { ReplaySurface { CompositionLocalProvider(LocalReducedMotion provides true) {
            ThresholdProgress(3, 3, "lần nghe") } } }
        compose.mainClock.advanceTimeByFrame()
        compose.capture("050I-reduced-motion")
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(1f, 0f..1f))).assertExists()
    }
}
