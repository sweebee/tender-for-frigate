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
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlinx.coroutines.delay
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
 * The picture is kept close to live by playing a little faster whenever data has piled up.
 * `onFailure` is called once when the stream cannot play here; the web player then takes over.
 */
@OptIn(UnstableApi::class)
@Composable
fun NativeLivePlayer(client: FrigateClient, stream: String, state: LivePlayerState, fill: Boolean,
                     modifier: Modifier = Modifier, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val failure by rememberUpdatedState(onFailure)
    val session = remember(client, stream) { NativeSession(client, stream, state) }
    var aspect by remember(session) { mutableFloatStateOf(0f) }
    val exo = remember(session) {
        ExoPlayer.Builder(context)
            // Start on the first frames; read on whatever arrives, so nothing piles up in the socket.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 30_000, 100, 300).build())
            .build().apply {
                // Live video never takes the audio focus from other apps.
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
                volume = if (state.muted) 0f else 1f
                playWhenReady = true
            }
    }
    DisposableEffect(session) {
        val main = Handler(Looper.getMainLooper())
        var played = false
        var retries = 0
        var done = false
        fun fail(reason: String) {
            if (done) return
            done = true
            exo.stop()
            failure(reason)
        }
        fun connect() {
            exo.setMediaSource(session.mediaSource())
            exo.prepare()
        }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                played = true
                retries = 0
                state.playing = true
                state.status = "Live"
                state.activeMode = "mse"
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                val w = videoSize.width * videoSize.pixelWidthHeightRatio
                aspect = w / videoSize.height
                state.videoWidth = w.toDouble(); state.videoHeight = videoSize.height.toDouble()
                session.codecs?.let { state.mseInfo = MseInfo(it, w.toInt(), videoSize.height) }
            }
            override fun onTracksChanged(tracks: Tracks) {
                state.hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO)
            }
            override fun onPlayerError(error: PlaybackException) {
                // Before the first frame: this device cannot play it here (codec, no data). Once it
                // played: a dropped connection, tried again a few times.
                if (!played || ++retries > 3) { fail(error.message ?: "error"); return }
                state.status = "Reconnecting"
                main.postDelayed({ if (!done) connect() }, 1000)
            }
        }
        exo.addListener(listener)
        state.status = "Connecting"
        state.playing = false
        state.activeMode = "mse"
        state.hasAudio = false
        state.mseInfo = null
        connect()
        // Watchdog: a first frame within 20 s (some cameras send a keyframe only every 10 s), and
        // no stall of more than 6 s once playing.
        val started = System.currentTimeMillis()
        var stalledSince = 0L
        val watchdog = object : Runnable {
            override fun run() {
                if (done) return
                val now = System.currentTimeMillis()
                if (!played) {
                    if (now - started > 20_000) { fail("no video"); return }
                } else if (exo.playbackState == Player.STATE_BUFFERING) {
                    if (stalledSince == 0L) stalledSince = now
                    if (now - stalledSince > 6_000) {
                        stalledSince = 0L
                        if (++retries > 3) { fail("stalled"); return }
                        state.status = "Reconnecting"
                        connect()
                    }
                } else {
                    stalledSince = 0L
                    // Close to live: a little faster while more than a moment is buffered.
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
        main.postDelayed(watchdog, 500)
        onDispose {
            done = true
            main.removeCallbacksAndMessages(null)
            exo.removeListener(listener)
            exo.release()
        }
    }
    LaunchedEffect(exo, state.muted) { exo.volume = if (state.muted) 0f else 1f }
    LiveTexture(exo, aspect, fill, modifier)
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

/** One stream's connection details: the media source ExoPlayer reads, and what go2rtc said. */
@OptIn(UnstableApi::class)
private class NativeSession(val client: FrigateClient, val stream: String, val state: LivePlayerState) {
    /** go2rtc's codec string ("video/mp4; codecs=…"), once it sent it. */
    @Volatile var codecs: String? = null

    fun mediaSource(): ProgressiveMediaSource =
        ProgressiveMediaSource.Factory({ MseDataSource(this) }, ExtractorsFactory { arrayOf<Extractor>(FragmentedMp4Extractor()) })
            .createMediaSource(MediaItem.fromUri(Uri.parse("go2rtc://$stream")))

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

/**
 * go2rtc's MSE websocket as a stream of bytes for ExoPlayer: the init segment and the fragments
 * that follow, in order. Each `open` is a new connection (a reconnect after a stall).
 */
@OptIn(UnstableApi::class)
private class MseDataSource(private val session: NativeSession) : BaseDataSource(true) {
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
                webSocket.send(NativeSession.request)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!closed) queue.put(bytes.toByteArray())
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
