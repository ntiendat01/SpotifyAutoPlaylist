package com.spotifyautoplaylist.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Public album images only. No token headers, disk cache, redirects, user data or request logging. */
internal object ArtworkImages {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_EDGE = 512
    private val slots = Semaphore(4)
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun permitted(raw: String?): Boolean = runCatching {
        val uri = URI(raw ?: return false)
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return false
        uri.scheme == "https" && (host == "scdn.co" || host.endsWith(".scdn.co")) &&
            uri.userInfo == null && uri.fragment == null && uri.port in listOf(-1, 443) &&
            !uri.path.isNullOrBlank()
    }.getOrDefault(false)

    suspend fun load(url: String): Bitmap? {
        if (!permitted(url)) return null
        cache.get(url)?.let { return it }
        return slots.withPermit {
            cache.get(url)?.let { return@withPermit it }
            withContext(Dispatchers.IO) {
                val connection = URL(url).openConnection() as HttpsURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.useCaches = false
                    if (connection.responseCode != 200 || connection.contentLengthLong > MAX_BYTES) return@withContext null
                    val bytes = connection.inputStream.use(::readBounded) ?: return@withContext null
                    decode(bytes)?.also { cache.put(url, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null // Missing/unsupported artwork retains the neutral placeholder.
                } finally { connection.disconnect() }
            }
        }
    }

    internal fun readBounded(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    internal fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.size > MAX_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight).toLong() > MAX_EDGE.toLong() * sample) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** Artwork is decorative next to the song/playlist name. Bounds never change during loading. */
@Composable
fun TrackArtwork(modifier: Modifier = Modifier, url: String? = null) {
    val preview = LocalInspectionMode.current
    val bitmap by produceState<Bitmap?>(null, url, preview) {
        value = null
        if (!preview && url != null) value = ArtworkImages.load(url)
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) Image(loaded.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        else ReplayIcon(ReplayGlyph.Music, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
