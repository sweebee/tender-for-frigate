package nl.wiebenieuwenhuis.tender.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import nl.wiebenieuwenhuis.tender.api.Http
import nl.wiebenieuwenhuis.tender.api.await
import okhttp3.Request

/** A picture from a Frigate server (with the login cookie), cached. */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
) {
    val ctx = LocalContext.current
    AsyncImage(
        model = url?.let { ImageRequest.Builder(ctx).data(it).crossfade(true).build() },
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

/** The last frame shown per URL, so a picture shows at once when a screen comes back. */
object FrameCache {
    private val cache = LruCache<String, ImageBitmap>(48)
    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, b: ImageBitmap) { cache.put(key, b) }
}

/**
 * A picture that refreshes every `intervalMs` (a camera's latest frame). A failed load keeps the
 * previous frame, so a flaky connection never shows a broken image; nothing flickers in between.
 * `placeholderUrl`: a smaller frame to show from the cache until the first one arrives.
 */
@Composable
fun RefreshingImage(
    url: String,
    intervalMs: Long,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderUrl: String? = null,
    contentDescription: String? = null,
) {
    var bitmap by remember(url) { mutableStateOf(FrameCache.get(url) ?: placeholderUrl?.let { FrameCache.get(it) }) }
    LaunchedEffect(url, intervalMs) {
        while (isActive) {
            val sep = if (url.contains('?')) '&' else '?'
            val b = runCatching {
                Http.client.newCall(Request.Builder().url("$url${sep}_t=${System.currentTimeMillis() / 1000}").build()).await().use { res ->
                    if (!res.isSuccessful) null else withContext(Dispatchers.IO) {
                        val bytes = res.body.bytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }
                }
            }.getOrNull()
            if (b != null) { bitmap = b; FrameCache.put(url, b) }
            if (intervalMs <= 0) break
            delay(if (b == null) maxOf(intervalMs, 2000) else intervalMs)
        }
    }
    Box(modifier.background(Color.Black)) {
        bitmap?.let { Image(it, contentDescription, Modifier.matchParentSize(), contentScale = contentScale) }
    }
}

/** A player for recordings and clips, released when the composable leaves. Plays Frigate's HLS and MP4 with the login cookie. */
@OptIn(UnstableApi::class)
@Composable
fun rememberVideoPlayer(): ExoPlayer {
    val ctx = LocalContext.current
    val player = remember {
        val factory = DefaultMediaSourceFactory(ctx).setDataSourceFactory(OkHttpDataSource.Factory(Http.client))
        ExoPlayer.Builder(ctx).setMediaSourceFactory(factory).build()
    }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/** Loads a URL in the player; `.m3u8` as HLS. */
fun ExoPlayer.load(url: String, startMs: Long = 0, play: Boolean = true) {
    val item = MediaItem.Builder().setUri(url).apply {
        if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
    }.build()
    setMediaItem(item, startMs)
    prepare()
    playWhenReady = play
}

/** The picture of an ExoPlayer, without controls (screens draw their own). */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: ExoPlayer, modifier: Modifier = Modifier, fill: Boolean = false) {
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                this.player = player
            }
        },
        update = {
            it.player = player
            it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        },
        onRelease = { it.player = null },
        modifier = modifier.background(Color.Black),
    )
}
