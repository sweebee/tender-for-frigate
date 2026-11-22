package nl.wiebenieuwenhuis.tender.ui.live

import android.media.MediaCodecList
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.FrigateJson
import nl.wiebenieuwenhuis.tender.api.Http
import nl.wiebenieuwenhuis.tender.api.obj
import nl.wiebenieuwenhuis.tender.api.str
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays go2rtc's MSE stream without a web view, as the iOS app's `NativeLivePlayer`: the
 * fragmented MP4 that arrives over the websocket goes straight into ExoPlayer, which splits,
 * decodes and shows it (and plays its AAC audio). Faster to start and lighter than the web view.
 * The player comes from [LivePool], shared with every other view of the same stream: a camera
 * opened from its tile carries on with the tile's player, picture and all. `bridgeStream`: another
 * stream of the camera that already plays (its tile's sub stream), shown until this one has a frame.
 * `onFailure` is called once when the stream cannot play here; the web player then takes over.
 */
@Composable
fun NativeLivePlayer(client: FrigateClient, stream: String, state: LivePlayerState, fill: Boolean,
                     modifier: Modifier = Modifier, bridgeStream: String? = null, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val failure by rememberUpdatedState(onFailure)
    val entry = remember(client, stream) { LivePool.acquire(context, client, stream) }
    DisposableEffect(entry) { onDispose { LivePool.release(entry) } }
    // The tile's picture until this stream has a frame of its own.
    var bridge by remember { mutableStateOf<LiveEntry?>(null) }
    DisposableEffect(entry, bridgeStream) {
        val b = bridgeStream?.takeIf { it != stream && !entry.played && LivePool.isPlaying(client, it) }
            ?.let { LivePool.acquire(context, client, it) }
        bridge = b
        onDispose { if (b != null && bridge === b) { LivePool.release(b); bridge = null } }
    }
    LaunchedEffect(entry.played) {
        if (entry.played) bridge?.let { LivePool.release(it); bridge = null }
    }
    LaunchedEffect(entry) {
        state.activeMode = "mse"
        state.mseInfo = null
    }
    LaunchedEffect(entry, entry.played, entry.reconnecting) {
        state.playing = entry.played
        state.status = when {
            entry.reconnecting -> "Reconnecting"
            entry.played -> "Live"
            else -> "Connecting"
        }
    }
    LaunchedEffect(entry, entry.width, entry.height) {
        if (entry.width > 0 && entry.height > 0) {
            state.videoWidth = entry.width.toDouble(); state.videoHeight = entry.height.toDouble()
            entry.codecs?.let { state.mseInfo = MseInfo(it, entry.width, entry.height) }
        }
    }
    LaunchedEffect(entry, entry.hasAudio) { state.hasAudio = entry.hasAudio }
    LaunchedEffect(entry, entry.failure) { entry.failure?.let { failure(it) } }
    // The view on screen sets the sound: a tile stays silent, the camera's own screen follows its button.
    LaunchedEffect(entry, state.muted) { entry.exo.volume = if (state.muted) 0f else 1f }
    Box(modifier) {
        bridge?.let { LiveTexture(it.exo, it.aspect, fill, Modifier.fillMaxSize()) }
        LiveTexture(entry.exo, entry.aspect, fill, Modifier.fillMaxSize())
    }
}

/**
 * Running live players, one per server and stream, shared by the views that show it. A player
 * whose last view went away keeps running for a few seconds, so going from a tile to its camera
 * and back does not reconnect.
 */
object LivePool {
    private val entries = HashMap<String, LiveEntry>()
    private val main = Handler(Looper.getMainLooper())

    private fun key(client: FrigateClient, stream: String) = "${client.baseUrl}|$stream"

    fun isPlaying(client: FrigateClient, stream: String) = entries[key(client, stream)]?.played == true

    fun acquire(context: android.content.Context, client: FrigateClient, stream: String): LiveEntry {
        val k = key(client, stream)
        val entry = entries[k]?.takeIf { it.failure == null } ?: LiveEntry(k, context.applicationContext, client, stream).also {
            entries[k] = it
            it.start()
        }
        entry.users++
        main.removeCallbacksAndMessages(entry)
        return entry
    }

    fun release(entry: LiveEntry) {
        if (--entry.users > 0) return
        if (entry.failure != null) { close(entry); return }
        main.postAtTime({ if (entry.users == 0) close(entry) }, entry, android.os.SystemClock.uptimeMillis() + 5_000)
    }

    internal fun dropFailed(entry: LiveEntry) {
        if (entries[entry.key] === entry) entries.remove(entry.key)
        if (entry.users == 0) close(entry)
    }

    private fun close(entry: LiveEntry) {
        if (entries[entry.key] === entry) entries.remove(entry.key)
        entry.stop()
    }
}

/** One stream's player: its ExoPlayer, the connection behind it, and what views need to know. */
@OptIn(UnstableApi::class)
class LiveEntry internal constructor(internal val key: String, context: android.content.Context, val client: FrigateClient, val stream: String) {
    internal var users = 0
    var played by mutableStateOf(false); private set
    var reconnecting by mutableStateOf(false); private set
    var width by mutableIntStateOf(0); private set
    var height by mutableIntStateOf(0); private set
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 0f
    var hasAudio by mutableStateOf(false); private set
    /** Set once when the stream cannot play natively. */
    var failure by mutableStateOf<String?>(null); private set
    /** go2rtc's codec string ("video/mp4; codecs=…"), once it sent it. */
    @Volatile var codecs: String? = null

    val exo: ExoPlayer = ExoPlayer.Builder(context)
        // Start on the first frames; read on whatever arrives, so nothing piles up in the socket.
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 30_000, 100, 300).build())
        .build().apply {
            // Live video never takes the audio focus from other apps.
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
            volume = 0f
            playWhenReady = true
        }
    private val main = Handler(Looper.getMainLooper())
    private var retries = 0
    private var stopped = false
    private var started = 0L
    private var stalledSince = 0L

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            played = true
            reconnecting = false
            retries = 0
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            width = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
            height = videoSize.height
        }
        override fun onTracksChanged(tracks: Tracks) {
            hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO)
        }
        override fun onPlayerError(error: PlaybackException) {
            // Before the first frame: this device cannot play it here (codec, no data). Once it
            // played: a dropped connection, tried again a few times.
            if (!played || ++retries > 3) { fail(error.message ?: "error"); return }
            reconnecting = true
            main.postDelayed({ if (!stopped) connect() }, 1000)
        }
    }

    // Watchdog: a first frame within 20 s (some cameras send a keyframe only every 10 s), and no
    // stall of more than 6 s once playing. Close to live: a little faster while data piles up.
    private val watchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            val now = System.currentTimeMillis()
            if (!played) {
                if (now - started > 20_000) { fail("no video"); return }
            } else if (exo.playbackState == Player.STATE_BUFFERING) {
                if (stalledSince == 0L) stalledSince = now
                if (now - stalledSince > 6_000) {
                    stalledSince = 0L
                    if (++retries > 3) { fail("stalled"); return }
                    reconnecting = true
                    connect()
                }
            } else {
                stalledSince = 0L
                val behind = exo.totalBufferedDuration
                val speed = when {
                    behind > 2_000 -> 1.5f
                    behind > 700 -> 1.15f
                    else -> 1f
                }
                if (exo.playbackParameters.speed != speed) exo.playbackParameters = PlaybackParameters(speed)
            }
            main.postDelayed(this, 500)
        }
    }

    internal fun start() {
        exo.addListener(listener)
        started = System.currentTimeMillis()
        connect()
        main.postDelayed(watchdog, 500)
    }

    private fun connect() {
        exo.setMediaSource(
            ProgressiveMediaSource.Factory({ MseDataSource(this) }, ExtractorsFactory { arrayOf<Extractor>(FragmentedMp4Extractor()) })
                .createMediaSource(MediaItem.fromUri(Uri.parse("go2rtc://$stream"))))
        exo.prepare()
    }

    private fun fail(reason: String) {
        if (failure != null || stopped) return
        exo.stop()
        failure = reason
        LivePool.dropFailed(this)
    }

    internal fun stop() {
        if (stopped) return
        stopped = true
        main.removeCallbacksAndMessages(null)
        exo.removeListener(listener)
        exo.release()
    }

    companion object {
        /**
         * The codecs we can decode, as go2rtc expects them: H.264, HEVC when the device has a
         * decoder for it, and AAC (without an audio codec go2rtc sends video only).
         */
        val request: String by lazy {
            val codecs = mutableListOf("avc1.640029", "avc1.64002A", "avc1.640033")
            val hevc = runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
                }
            }.getOrDefault(false)
            if (hevc) codecs += "hvc1.1.6.L153.B0"
            codecs += listOf("mp4a.40.2", "mp4a.40.5")
            """{"type":"mse","value":"${codecs.joinToString(",")}"}"""
        }
    }
}

/** The video: fitted inside the frame, or (`fill`) covering it, cropped. */
@Composable
private fun LiveTexture(exo: ExoPlayer, aspect: Float, fill: Boolean, modifier: Modifier) {
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c -> TextureView(c).also { exo.setVideoTextureView(it) } },
            update = { exo.setVideoTextureView(it) },
            onRelease = { exo.clearVideoTextureView(it) },
            modifier = when {
                aspect <= 0f -> Modifier.fillMaxSize()
                fill -> Modifier.cover(aspect)
                else -> Modifier.aspectRatio(aspect)
            },
        )
    }
}

/** As large as needed to cover the frame at `aspect`, centred (the overflow is clipped). */
private fun Modifier.cover(aspect: Float) = layout { measurable, constraints ->
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    var cw = w
    var ch = (w / aspect).toInt()
    if (ch < h) { ch = h; cw = (h * aspect).toInt() }
    val placeable = measurable.measure(Constraints.fixed(cw, ch))
    layout(w, h) { placeable.place((w - cw) / 2, (h - ch) / 2) }
}

/**
 * go2rtc's MSE websocket as a stream of bytes for ExoPlayer: the init segment and the fragments
 * that follow, in order. Each `open` is a new connection (a reconnect after a stall).
 */
@OptIn(UnstableApi::class)
private class MseDataSource(private val session: LiveEntry) : BaseDataSource(true) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    @Volatile private var error: String? = null
    private var socket: WebSocket? = null
    private var uri: Uri? = null
    private var chunk: ByteArray? = null
    private var position = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val req = Request.Builder().url(session.client.liveSocketUrl(session.stream, "mse")).build()
        socket = Http.client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(LiveEntry.request)
            }
            /** An HEVC init segment, held until the first fragment shows its real parameter sets. */
            private var pendingInit: ByteArray? = null
            private var first = true
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (closed) return
                val data = bytes.toByteArray()
                if (first) {
                    first = false
                    if (HevcInitFix.isHevcInit(data)) { pendingInit = data; return }
                }
                pendingInit?.let { init ->
                    pendingInit = null
                    queue.put(HevcInitFix.patch(init, data))
                }
                queue.put(data)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { FrigateJson.parseToJsonElement(text) }.getOrNull().obj() ?: return
                when (o["type"].str()) {
                    "mse" -> session.codecs = o["value"].str()
                    "error" -> error = o["value"].str() ?: "error"
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                error = t.message ?: "connection closed"
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed) error = "connection closed"
            }
        })
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val current = chunk
            if (current != null && position < current.size) {
                val n = minOf(length, current.size - position)
                System.arraycopy(current, position, buffer, offset, n)
                position += n
                bytesTransferred(n)
                return n
            }
            if (closed) return C.RESULT_END_OF_INPUT
            val next = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { throw InterruptedIOException() }
            if (next != null) { chunk = next; position = 0; continue }
            error?.let { throw IOException(it) }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (closed) return
        closed = true
        socket?.close(1001, null)
        socket = null
        queue.clear()
        chunk = null
        if (uri != null) transferEnded()
        uri = null
    }
}
