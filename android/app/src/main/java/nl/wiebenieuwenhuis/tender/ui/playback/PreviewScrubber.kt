package nl.wiebenieuwenhuis.tender.ui.playback

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.Http
import nl.wiebenieuwenhuis.tender.api.PreviewFile
import nl.wiebenieuwenhuis.tender.api.await
import nl.wiebenieuwenhuis.tender.api.nowSeconds
import nl.wiebenieuwenhuis.tender.api.previewFileUrl
import nl.wiebenieuwenhuis.tender.api.previewFrameUrl
import nl.wiebenieuwenhuis.tender.api.previewFrames
import nl.wiebenieuwenhuis.tender.api.previews
import okhttp3.Request
import kotlin.math.abs
import kotlin.math.floor

/**
 * Shows Frigate's low-resolution preview videos while the user drags a timeline, so the picture
 * follows the finger like in Frigate's web UI. Previews are one file per hour (about 320×180, a
 * frame every few seconds). The current hour has no file yet; for it, like Frigate's web UI, the
 * loose preview frames Frigate keeps while the hour runs are shown.
 */
@Stable
@OptIn(UnstableApi::class)
class PreviewScrubber(private val scope: CoroutineScope) {
    private var _player: ExoPlayer? = null
    /** Made on first use: Motion review has one per camera, most never scrubbed on a phone. */
    val player: ExoPlayer
        get() = _player ?: newExoPlayer().also { p ->
            p.volume = 0f
            p.playWhenReady = false
            // Exact seeks: preview files have a keyframe only every few minutes, so a seek with
            // tolerance lands far too early. They are small, so decoding up to the frame is quick.
            p.setSeekParameters(SeekParameters.EXACT)
            p.addListener(listener)
            _player = p
        }

    /** True while a preview frame for the scrub position is on screen. */
    var showing by mutableStateOf(false); private set
    /** Set while the frame on screen comes from the current hour's loose frames, not a video. */
    var frameImage by mutableStateOf<ImageBitmap?>(null); private set
    var videoAspect by mutableFloatStateOf(0f); private set

    private var previews: List<PreviewFile> = emptyList()
    private var client: FrigateClient? = null
    private var camera = ""
    private var loadedSrc: String? = null
    private var itemSrc: String? = null
    private var seeking = false
    private var seekGeneration = 0
    private var pendingMs: Long? = null

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            // A seek (or a new hour) has its frame on screen: show it, and go on to the latest position.
            if (loadedSrc != null && loadedSrc == itemSrc && frameImage == null) showing = true
            seeking = false
            seekNext()
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            if (size.width > 0 && size.height > 0) videoAspect = size.width * size.pixelWidthHeightRatio / size.height
        }
    }

    /** Loads the list of preview files for a camera and period. */
    suspend fun prepare(client: FrigateClient, camera: String, start: Double, end: Double) {
        this.client = client
        this.camera = camera
        frames = emptyList()
        framesLoaded = 0.0
        loadedSrc = null
        previews = runCatching { client.previews(camera, start, end) }.getOrDefault(emptyList())
    }

    /** Shows the preview frame nearest to `wall`, or hides the preview when there is none. */
    fun show(wall: Double) {
        val client = client ?: run { showing = false; return }
        val p = previews.firstOrNull { wall >= it.start && wall < it.end }
        if (p == null) {
            // Frigate writes an hour's file only after the hour; until then it keeps loose frames.
            if (wall > nowSeconds() - 7200) showFrame(wall) else showing = false
            return
        }
        frameImage = null
        pendingFrame = null
        if (loadedSrc != p.src) {
            loadedSrc = p.src
            if (itemSrc != p.src) {
                itemSrc = p.src
                // The previous hour's last frame stays on the surface until the new one lands.
                player.setMediaItem(MediaItem.fromUri(client.previewFileUrl(p.src)), ((wall - p.start) * 1000).toLong().coerceAtLeast(0))
                player.prepare()
                seeking = true
                watchSeek()
                return
            }
        }
        pendingMs = ((wall - p.start) * 1000).toLong().coerceAtLeast(0)
        seekNext()
    }

    /** Hides the preview, e.g. once the full-quality video has arrived. */
    fun hide() {
        showing = false
        frameImage = null
        pendingFrame = null
        hides++
        pendingMs = null
        loadedSrc = null
    }

    fun release() {
        _player?.release()
        _player = null
        itemSrc = null
    }

    /** Issues at most one seek at a time and always jumps to the latest requested position. */
    private fun seekNext() {
        if (seeking) return
        val t = pendingMs ?: return
        if (loadedSrc == null) return
        pendingMs = null
        seeking = true
        player.seekTo(t)
        watchSeek()
    }

    /** A seek that never renders a frame (past the end of the file) must not block the next ones. */
    private fun watchSeek() {
        val g = ++seekGeneration
        scope.launch {
            delay(700)
            if (seeking && g == seekGeneration) { seeking = false; seekNext() }
        }
    }

    // MARK: Frames of the running hour

    private var frames: List<Pair<Double, String>> = emptyList()
    private var framesLoaded = 0.0
    private var loadingFrames = false
    private var pendingFrame: Double? = null
    private var fetchingFrame = false
    private var shownFrame = ""
    /** Counts hide() calls, so a frame that arrives after the scrub ended is not shown. */
    private var hides = 0

    private fun showFrame(wall: Double) {
        pendingFrame = wall
        // New frames keep coming in; look again when scrubbing past the last one.
        val stale = nowSeconds() - framesLoaded > 3
        if (stale && (frames.isEmpty() || wall > (frames.lastOrNull()?.first ?: 0.0))) loadFrames()
        fetchNextFrame()
    }

    private fun loadFrames() {
        val client = client ?: return
        if (loadingFrames) return
        loadingFrames = true
        val now = nowSeconds()
        val from = floor(now / 3600) * 3600 - 3600
        scope.launch {
            try {
                val list = runCatching { client.previewFrames(camera, from, now) }.getOrNull() ?: return@launch
                framesLoaded = nowSeconds()
                frames = list
            } finally {
                loadingFrames = false
            }
            fetchNextFrame()
        }
    }

    /** Loads one frame at a time, always the one for the latest scrub position. */
    private fun fetchNextFrame() {
        val client = client
        val wall = pendingFrame
        if (fetchingFrame || wall == null || client == null || frames.isEmpty()) {
            if (frames.isEmpty() && !loadingFrames && pendingFrame != null) showing = false
            return
        }
        pendingFrame = null
        val i = frames.indexOfFirst { it.first >= wall }.let { if (it < 0) frames.size - 1 else it }
        // Nearest of the frames around the position.
        val j = if (i > 0 && abs(frames[i - 1].first - wall) < abs(frames[i].first - wall)) i - 1 else i
        val name = frames[j].second
        // Too far from any frame (nothing was recorded then): no preview.
        if (abs(frames[j].first - wall) >= 90) { showing = false; frameImage = null; return }
        if (name == shownFrame && frameImage != null) { showing = true; return }
        FrameImages.get(name)?.let {
            frameImage = it; shownFrame = name; showing = true
            fetchNextFrame()
            return
        }
        fetchingFrame = true
        val generation = hides
        scope.launch {
            try {
                val img = runCatching {
                    Http.client.newCall(Request.Builder().url(client.previewFrameUrl(name)).build()).await().use { res ->
                        if (!res.isSuccessful) null else withContext(Dispatchers.IO) {
                            val bytes = res.body.bytes()
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }
                    }
                }.getOrNull()
                if (img != null) {
                    FrameImages.put(name, img)
                    if (generation == hides) {
                        frameImage = img
                        shownFrame = name
                        showing = true
                    }
                }
            } finally {
                fetchingFrame = false
            }
            fetchNextFrame()
        }
    }
}

/** Loose preview frames, shared by every scrubber. */
private object FrameImages {
    private val cache = LruCache<String, ImageBitmap>(120)
    fun get(name: String): ImageBitmap? = cache.get(name)
    fun put(name: String, b: ImageBitmap) { cache.put(name, b) }
}

/** What a scrubber shows: a preview video frame, or a loose frame image. */
@Composable
fun PreviewSurface(preview: PreviewScrubber, modifier: Modifier = Modifier, fill: Boolean = false) {
    // Kept on screen (see-through) while hidden: the player renders a frame only onto a surface.
    Box(modifier.graphicsLayer { alpha = if (preview.showing) 1f else 0f }.background(Color.Black)) {
        val img = preview.frameImage
        if (img != null) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = if (fill) ContentScale.Crop else ContentScale.Fit)
        } else {
            PlayerTexture(preview.player, if (fill) 0f else preview.videoAspect, Modifier.fillMaxSize())
        }
    }
}
