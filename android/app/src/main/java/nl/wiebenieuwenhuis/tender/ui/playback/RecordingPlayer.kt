package nl.wiebenieuwenhuis.tender.ui.playback

import nl.wiebenieuwenhuis.tender.ui.explore.pinchStep
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.ui.components.newExoPlayer
import kotlin.math.abs

/**
 * Wraps an ExoPlayer for HLS recordings from Frigate's /vod endpoint, as `RecordingPlayer` in the
 * iOS app: position, duration, buffering and the user's wish to play, as Compose state.
 */
@Stable
@OptIn(UnstableApi::class)
class RecordingPlayer(private val scope: CoroutineScope) {
    val exo: ExoPlayer = newExoPlayer()
    var currentTime by mutableDoubleStateOf(0.0); private set
    var duration by mutableDoubleStateOf(0.0); private set
    var isPlaying by mutableStateOf(false); private set
    var isBuffering by mutableStateOf(false); private set
    var rate by mutableFloatStateOf(1f); private set
    var error by mutableStateOf<String?>(null); private set
    /** Incremented each time the current item plays to its end. */
    var finishedCount by mutableIntStateOf(0); private set
    /** Whether the user wants playback running. A stall must not count as pausing. */
    var wantsToPlay by mutableStateOf(false); private set
    /** The new item's start, until it is ready. */
    var pendingStart by mutableStateOf<Double?>(null); private set
    var videoAspect by mutableFloatStateOf(0f); private set
    private var url: String? = null
    private var retries = 0
    private var ticker: Job? = null

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) retries = 0
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                when (state) {
                    Player.STATE_READY -> {
                        pendingStart = null
                        exo.duration.takeIf { it != C.TIME_UNSET }?.let { duration = it / 1000.0 }
                    }
                    Player.STATE_ENDED -> {
                        isPlaying = false
                        wantsToPlay = false
                        finishedCount++
                    }
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                // A dropped connection: open the recording again where it was, twice at most.
                val u = url
                if (u != null && retries < 2) {
                    retries++
                    val at = currentTime
                    val play = wantsToPlay
                    scope.launch { delay(800L * retries); replaceItem(u, at, play) }
                    return
                }
                error = L(R.string.playback_failed)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) videoAspect = size.width * size.pixelWidthHeightRatio / size.height
            }
        })
        ticker = scope.launch {
            while (isActive) {
                delay(100)
                if (exo.playbackState == Player.STATE_IDLE || pendingStart != null) continue
                currentTime = exo.currentPosition / 1000.0
                exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { duration = it / 1000.0 }
            }
        }
    }

    fun load(url: String, startAt: Double = 0.0, autoplay: Boolean = true) {
        retries = 0
        replaceItem(url, startAt, autoplay)
    }

    private fun replaceItem(url: String, startAt: Double, autoplay: Boolean) {
        this.url = url
        error = null
        currentTime = startAt
        duration = 0.0
        pendingStart = startAt
        val item = MediaItem.Builder().setUri(url).apply { if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        exo.setSeekParameters(SeekParameters.EXACT)
        exo.setMediaItem(item, (startAt * 1000).toLong())
        exo.prepare()
        wantsToPlay = autoplay
        exo.playWhenReady = autoplay
    }

    /** After a failure, the next play or seek opens the recording again. */
    private fun reloadIfFailed(at: Double, autoplay: Boolean): Boolean {
        val u = url ?: return false
        if (error == null) return false
        load(u, at, autoplay)
        return true
    }

    fun play() {
        if (reloadIfFailed(currentTime, true)) return
        if (exo.playbackState == Player.STATE_ENDED || (duration > 0 && currentTime >= duration - 0.3)) exo.seekTo(0)
        if (exo.playbackState == Player.STATE_IDLE && url != null) exo.prepare()
        wantsToPlay = true
        exo.playWhenReady = true
    }

    fun pause() {
        wantsToPlay = false
        exo.playWhenReady = false
    }

    fun toggle() = if (wantsToPlay) pause() else play()

    /**
     * `earlier`: how far before `seconds` the player may land, on a keyframe, to start sooner;
     * it never lands later than asked. An exact seek decodes from the keyframe before it, which
     * on cameras with few keyframes takes seconds.
     */
    fun seek(seconds: Double, precise: Boolean = false, earlier: Double = 0.0) {
        val t = maxOf(0.0, if (duration > 0) minOf(seconds, duration) else seconds)
        if (reloadIfFailed(t, wantsToPlay)) return
        currentTime = t
        exo.setSeekParameters(
            when {
                earlier > 0 -> SeekParameters((earlier * 1_000_000).toLong(), 0)
                precise -> SeekParameters.EXACT
                else -> SeekParameters(500_000, 500_000)
            }
        )
        exo.seekTo((t * 1000).toLong())
    }

    /** Exact, so the position does not snap back to a nearby keyframe once the seek lands. */
    fun skip(delta: Double) = seek(currentTime + delta, precise = true)

    fun changeRate(r: Float) {
        rate = r
        exo.setPlaybackSpeed(r)
    }

    fun stop() {
        wantsToPlay = false
        url = null
        exo.playWhenReady = false
        exo.stop()
        exo.clearMediaItems()
        pendingStart = null
    }

    fun release() {
        ticker?.cancel()
        exo.release()
    }

    companion object {
        val rates = listOf(0.5f, 1f, 2f)
        fun rateTitle(r: Float): String = if (r == 0.5f) "½x" else "${r.toInt()}x"
    }
}

@Composable
fun rememberRecordingPlayer(): RecordingPlayer {
    val scope = rememberCoroutineScope()
    val player = remember { RecordingPlayer(scope) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/**
 * The picture of an ExoPlayer on a TextureView (so other pictures can lie over it and it can be
 * zoomed), fitted to its own aspect ratio and centred.
 */
@Composable
fun PlayerTexture(player: ExoPlayer, aspect: Float, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
            update = { player.setVideoTextureView(it) },
            onRelease = { player.clearVideoTextureView(it) },
            modifier = if (aspect > 0) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize(),
        )
    }
}

/**
 * A recording with pinch to zoom and drag to pan while zoomed; a tap toggles the controls and a
 * double tap zooms back out.
 */
@Composable
fun ZoomableVideo(player: RecordingPlayer, modifier: Modifier = Modifier, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoomed = scale > 1.01f
    Box(
        modifier
            .background(Color.Black)
            .pointerInput(zoomed) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = if (zoomed) ({ scale = 1f; offset = Offset.Zero }) else null,
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    pinchStep(scale, offset, centroid, pan, zoom, size, 6f).let { (s, o) -> scale = s; offset = o }
                }
            },
    ) {
        PlayerTexture(
            player.exo, player.videoAspect,
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        )
    }
}

/** Round translucent button used over video. `busy`: a spinner instead of the icon, and no second tap. */
@Composable
fun OverlayButton(
    icon: ImageVector,
    description: String,
    size: Dp = 44.dp,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(size * 0.45f), color = Color.White, strokeWidth = 2.dp)
        else Icon(icon, null, Modifier.size(size * 0.5f), tint = Color.White)
    }
}

/**
 * A round button over video with a text (the speed) that opens a menu of choices. `onOpen` and
 * `onClose` bracket the open menu, so the controls around it can stay while it is open.
 */
@Composable
fun <T> OverlayMenuButton(
    title: String,
    description: String,
    options: List<T>,
    selected: T,
    optionTitle: (T) -> String,
    size: Dp = 44.dp,
    onOpen: () -> Unit = {},
    onClose: () -> Unit = {},
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    fun close() { if (open) { open = false; onClose() } }
    // Gone while open (the controls faded out after all): the menu counts as closed.
    DisposableEffect(Unit) { onDispose { if (open) { open = false; onClose() } } }
    Box {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(role = Role.Button) { if (!open) { open = true; onOpen() } }
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (size > 50.dp) 16.sp else 13.sp, maxLines = 1)
        }
        DropdownMenu(open, onDismissRequest = ::close) {
            for (o in options) {
                DropdownMenuItem(
                    text = { Text(optionTitle(o)) },
                    leadingIcon = { if (o == selected) Icon(Icons.Filled.Check, null) else Box(Modifier.size(24.dp)) },
                    onClick = { close(); onSelect(o) },
                )
            }
        }
    }
}

/**
 * The playback controls drawn over a recording: back 10 s, play/pause, forward 10 s and the
 * speed. `togglePlay`: the playback screen also jumps over gaps. `onUse`: keeps the controls up.
 * `onMenuOpen`/`onMenuClose`: the speed menu opens and closes (the controls stay while it is open).
 */
@Composable
fun PlaybackOverlayControls(
    player: RecordingPlayer,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    togglePlay: (() -> Unit)? = null,
    onUse: () -> Unit = {},
    onMenuOpen: () -> Unit = {},
    onMenuClose: () -> Unit = {},
) {
    VideoOverlayControls(
        playing = player.wantsToPlay, rate = player.rate,
        onSkip = { player.skip(it) }, onTogglePlay = togglePlay ?: player::toggle, onRate = { player.changeRate(it) },
        modifier = modifier, large = large, onUse = onUse, onMenuOpen = onMenuOpen, onMenuClose = onMenuClose,
    )
}

/** [PlaybackOverlayControls] for any player: the same buttons, with the player's state and calls passed in. */
@Composable
fun VideoOverlayControls(
    playing: Boolean,
    rate: Float,
    onSkip: (Double) -> Unit,
    onTogglePlay: () -> Unit,
    onRate: (Float) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    onUse: () -> Unit = {},
    onMenuOpen: () -> Unit = {},
    onMenuClose: () -> Unit = {},
) {
    val size = if (large) 56.dp else 44.dp
    Row(modifier.padding(if (large) 16.dp else 10.dp), horizontalArrangement = Arrangement.spacedBy(if (large) 16.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        OverlayButton(Icons.Filled.Replay10, stringResource(R.string.playback_back_10), size) { onSkip(-10.0); onUse() }
        OverlayButton(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.playback_pause else R.string.playback_play),
            if (large) 64.dp else 44.dp,
        ) { onTogglePlay(); onUse() }
        OverlayButton(Icons.Filled.Forward10, stringResource(R.string.playback_forward_10), size) { onSkip(10.0); onUse() }
        OverlayMenuButton(
            RecordingPlayer.rateTitle(rate), stringResource(R.string.playback_speed), RecordingPlayer.rates, rate,
            RecordingPlayer::rateTitle, size, onOpen = { onMenuOpen(); onUse() }, onClose = { onMenuClose(); onUse() },
        ) { onRate(it); onUse() }
    }
}

/** Enters or leaves full screen over a video. */
@Composable
fun FullscreenButton(isOn: Boolean, size: Dp = 36.dp, onClick: () -> Unit) {
    OverlayButton(
        if (isOn) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
        stringResource(if (isOn) R.string.playback_exit_fullscreen else R.string.playback_fullscreen), size, onClick = onClick,
    )
}

/** A loading spinner that stands out on any picture: on a dark disc; large where the video is large. */
@Composable
fun VideoSpinner(large: Boolean = false) {
    val d = if (large) 64.dp else 36.dp
    Box(Modifier.size(d).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(d * 0.55f), color = Color.White, strokeWidth = if (large) 3.dp else 2.dp)
    }
}
