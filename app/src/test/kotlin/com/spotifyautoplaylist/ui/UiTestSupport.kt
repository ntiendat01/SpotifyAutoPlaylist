package com.spotifyautoplaylist.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import java.io.File

/** Draws the Robolectric decor view into replay.screenshots (app/build/reports/ui); no-op when unset. */
fun AndroidComposeTestRule<*, out ComponentActivity>.capture(name: String) {
    val path = System.getProperty("replay.screenshots").orEmpty()
    if (path.isBlank()) return
    runOnIdle {
        val view = activity.window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val directory = File(path).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Screens are hosted by ReplayScaffold in the app; tests give them the same page background. */
@androidx.compose.runtime.Composable
fun ReplaySurface(content: @androidx.compose.runtime.Composable () -> Unit) = ReplayTheme {
    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { content() }
}
