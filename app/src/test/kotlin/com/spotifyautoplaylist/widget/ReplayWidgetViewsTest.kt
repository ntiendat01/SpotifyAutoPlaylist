package com.spotifyautoplaylist.widget

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.spotifyautoplaylist.data.sync.SyncRunStatus
import com.spotifyautoplaylist.spike.R
import com.spotifyautoplaylist.ui.ReplayTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Inflates each tier's RemoteViews and checks the bound copy and new-UI palette. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplayWidgetViewsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val now = 1_700_000_000_000L
    private val ready = WidgetUiMapper.map(WidgetPersistedInput(true, true, "abcdefghijklmnopqrstuv", "Replay thử", null, 7,
        SyncRunStatus(outcome = "SUCCESS", finishedAtMs = now, lastSuccessAtMs = now), now))
    private val offline = ready.copy(phase = WidgetPhase.OFFLINE, statusLine = "Không có mạng · Hiển thị dữ liệu trên máy", statusIsProblem = true)

    private fun inflate(tier: WidgetLayoutTier, state: WidgetUiState): View =
        ReplayWidgetViews.forTier(context, tier, state, 1).apply(context, FrameLayout(context))

    private fun text(root: View, id: Int) = root.findViewById<TextView>(id).text.toString()

    @Test fun `every tier binds count, status and a playlist action`() {
        for (tier in WidgetLayoutTier.entries) {
            val root = inflate(tier, ready)
            assertEquals(tier.name, "7", text(root, R.id.widget_count))
            assertTrue(tier.name, text(root, R.id.widget_status).startsWith("Đã đồng bộ lúc"))
            assertNotNull(tier.name, root.findViewById<View>(R.id.widget_open_playlist))
            assertTrue(tier.name, root.findViewById<View>(android.R.id.background).hasOnClickListeners())
        }
    }

    @Test fun `medium and large show caption, app action and sync time`() {
        val medium = inflate(WidgetLayoutTier.MEDIUM, ready)
        assertEquals("bài đã xác minh trên Spotify", text(medium, R.id.widget_count_caption))
        assertTrue(text(medium, R.id.widget_last_sync_short).startsWith("Thành công "))
        assertTrue(medium.findViewById<View>(R.id.widget_open_app).hasOnClickListeners())
        val large = inflate(WidgetLayoutTier.LARGE, ready)
        assertEquals("Replay thử", text(large, R.id.widget_playlist_name))
        assertTrue(text(large, R.id.widget_last_sync).startsWith("Đồng bộ thành công gần nhất"))
    }

    @Test fun `playlist action is hidden when nothing is linked`() {
        val out = WidgetUiMapper.map(WidgetPersistedInput(true, false, null, null, null, null, SyncRunStatus(), now))
        for (tier in WidgetLayoutTier.entries) {
            assertEquals(tier.name, View.GONE, inflate(tier, out).findViewById<View>(R.id.widget_open_playlist).visibility)
            assertEquals(tier.name, View.VISIBLE, inflate(tier, ready).findViewById<View>(R.id.widget_open_playlist).visibility)
        }
    }

    @Test fun `problem status uses warning color like the in-app banner`() {
        val root = inflate(WidgetLayoutTier.MEDIUM, offline)
        assertEquals(ReplayTokens.Warning.toArgb(), root.findViewById<TextView>(R.id.widget_status).currentTextColor)
        val ok = inflate(WidgetLayoutTier.MEDIUM, ready)
        assertEquals(ReplayTokens.Text.toArgb(), ok.findViewById<TextView>(R.id.widget_status).currentTextColor)
    }

    @Test fun `widget colors mirror ReplayTokens`() {
        val pairs = mapOf(
            R.color.widget_background to ReplayTokens.Background, R.color.widget_surface to ReplayTokens.Surface,
            R.color.widget_elevated to ReplayTokens.Elevated, R.color.widget_stroke to ReplayTokens.Stroke,
            R.color.widget_green to ReplayTokens.Green, R.color.widget_on_green to ReplayTokens.OnGreen,
            R.color.widget_soft to ReplayTokens.Soft, R.color.widget_selected to ReplayTokens.Selected,
            R.color.widget_text to ReplayTokens.Text, R.color.widget_text_secondary to ReplayTokens.Secondary,
            R.color.widget_muted to ReplayTokens.Muted, R.color.widget_warning to ReplayTokens.Warning,
            R.color.widget_error to ReplayTokens.Error,
        )
        for ((res, token) in pairs) assertEquals(context.resources.getResourceEntryName(res), token.toArgb(), context.getColor(res))
    }

    /** Renders each tier at a representative launcher size into replay.screenshots; no-op when unset. */
    @Test fun `render tiers for docs`() {
        val path = System.getProperty("replay.screenshots").orEmpty()
        if (path.isEmpty()) return
        val density = context.resources.displayMetrics.density
        val cases = listOf(
            Triple(WidgetLayoutTier.COMPACT, 160 to 64, "compact-2x1"),
            Triple(WidgetLayoutTier.MEDIUM, 320 to 150, "medium-4x2"),
            Triple(WidgetLayoutTier.MEDIUM, 250 to 130, "medium-4x2-min"),
            Triple(WidgetLayoutTier.LARGE, 380 to 280, "large-5x4"),
        )
        val states = mapOf("ready" to ready, "offline" to offline,
            "loggedout" to WidgetUiMapper.map(WidgetPersistedInput(true, false, null, null, null, null, SyncRunStatus(), now)))
        java.io.File(path).mkdirs()
        for ((tier, size, name) in cases) for ((label, state) in states) {
            val view = inflate(tier, state)
            val w = (size.first * density).toInt(); val h = (size.second * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(0xFF2B3A4A.toInt()) // neutral wallpaper so the rounded card edge is visible
            view.draw(canvas)
            java.io.File(path, "widget-$name-$label.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
