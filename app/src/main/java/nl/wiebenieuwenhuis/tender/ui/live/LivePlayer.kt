package nl.wiebenieuwenhuis.tender.ui.live

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.TenderApplication
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.FrigateJson
import nl.wiebenieuwenhuis.tender.api.Http
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.bool
import nl.wiebenieuwenhuis.tender.api.dbl
import nl.wiebenieuwenhuis.tender.api.get
import nl.wiebenieuwenhuis.tender.api.jsonOf
import nl.wiebenieuwenhuis.tender.api.obj
import nl.wiebenieuwenhuis.tender.api.str
import nl.wiebenieuwenhuis.tender.app.Prefs
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** How a live stream is played: go2rtc's MSE, WebRTC or Frigate's MJPEG snapshots, or the best that works. */
enum class LiveMode(val raw: String) { Auto("auto"), Mse("mse"), WebRtc("webrtc"), Mjpeg("mjpeg") }

/** State shared between a live player and its controls, as `LivePlayerState` in the iOS app. */
@Stable
class LivePlayerState(stream: String = "", preferred: LiveMode = LiveMode.Auto, muted: Boolean = true) {
    var muted by mutableStateOf(muted)
    var status by mutableStateOf("Connecting")
    var activeMode by mutableStateOf("")
    var playing by mutableStateOf(false)
    var videoWidth by mutableStateOf(0.0)
    var videoHeight by mutableStateOf(0.0)
    var hasAudio by mutableStateOf(false)
    var preferred by mutableStateOf(preferred)
    var stream by mutableStateOf(stream)
    /** Bumped to force a reload. */
    var reloadToken by mutableIntStateOf(0)
    /** While true no stream runs (screen hidden or app in the background). */
    var suspended by mutableStateOf(false)
    /** Two-way talk: WebRTC with the microphone sent to the camera's speaker. */
    var talking by mutableStateOf(false)
    /** Set when the microphone could not be used. */
    var micError by mutableStateOf<String?>(null)

    internal var web: WebView? = null

    fun setMutedNow(value: Boolean) {
        muted = value
        web?.evaluateJavascript("setMuted(${value})", null)
    }

    /** `status` as shown to the user; the page reports it in English. */
    val statusText: String
        get() = when {
            status.startsWith("Stream unavailable") -> L(R.string.stream_unavailable) + status.removePrefix("Stream unavailable")
            status == "Connecting" -> L(R.string.connecting)
            status == "Reconnecting" -> L(R.string.reconnecting)
            status == "Live" -> L(R.string.live)
            else -> status
        }

    companion object {
        /** Prefs key: the user last turned the sound on in a camera's live view. */
        const val SOUND_KEY = "liveSound"
        val soundOn: Boolean get() = Prefs.bool(SOUND_KEY, false)
    }
}

/**
 * Plays a go2rtc stream through Frigate's proxy in a web view: MSE over websocket first, then
 * WebRTC, then MJPEG snapshots from the Frigate API as the last fallback. Android's web view
 * has MediaSource and WebRTC, so this is the whole player (the iOS app also has a native one).
 * While talking the page runs from http://localhost (a secure page, needed for the microphone)
 * and the WebRTC handshake goes through the app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LivePlayerView(client: FrigateClient, camera: String, state: LivePlayerState, modifier: Modifier = Modifier, fill: Boolean = false) {
    val bridge = remember(client, camera, state) { PlayerBridge(client, camera, state) }
    val key = "${state.stream}|${state.preferred}|${state.reloadToken}|${state.suspended}|${state.talking}|$fill"
    DisposableEffect(bridge) {
        onDispose { bridge.dispose() }
    }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.domStorageEnabled = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                // Touches go to Compose (pinch to zoom, taps); the page needs none.
                isClickable = false
                isFocusable = false
                setOnTouchListener { _, _ -> false }
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    // The user already chose to talk; the app holds the microphone permission.
                    override fun onPermissionRequest(request: PermissionRequest) {
                        val audio = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                        if (audio.isNotEmpty()) request.grant(audio.toTypedArray()) else request.deny()
                    }
                }
                addJavascriptInterface(bridge, "Android")
                bridge.web = this
                state.web = this
            }
        },
        update = { web -> if (bridge.loadedKey != key) bridge.load(web, key, fill) },
        onRelease = { web ->
            web.evaluateJavascript("stopAll()", null)
            web.loadUrl("about:blank")
            web.removeJavascriptInterface("Android")
            web.destroy()
        },
        modifier = modifier,
    )
}

private class PlayerBridge(val client: FrigateClient, val camera: String, val state: LivePlayerState) {
    var web: WebView? = null
    var loadedKey = ""
    private var params = "{}"
    private var signal: WebSocket? = null
    private val main = Handler(Looper.getMainLooper())

    fun load(web: WebView, key: String, fill: Boolean) {
        loadedKey = key
        closeSignal()
        if (state.talking) TalkAudio.start() else TalkAudio.end()
        if (state.suspended) {
            web.evaluateJavascript("stopAll()", null)
            web.loadUrl("about:blank")
            state.playing = false
            return
        }
        val stream = state.stream.ifEmpty { camera }
        var modes = when (state.preferred) {
            LiveMode.Auto -> listOf("mse", "webrtc", "mjpeg")
            LiveMode.Mse -> listOf("mse", "mjpeg")
            LiveMode.WebRtc -> listOf("webrtc", "mjpeg")
            LiveMode.Mjpeg -> listOf("mjpeg")
        }
        if (fill) modes = listOf("mse")
        // Talking needs WebRTC (the only transport that sends audio back).
        if (state.talking) modes = listOf("webrtc")
        params = jsonOf(mapOf(
            "mse" to client.liveSocketUrl(stream, "mse"),
            "webrtc" to client.liveSocketUrl(stream, "webrtc"),
            "mjpeg" to client.mjpegUrl(camera, 720),
            "snapshot" to client.latestImageUrl(camera, 720),
            "modes" to modes,
            "muted" to state.muted,
            "fit" to if (fill) "cover" else "contain",
            "talk" to state.talking,
        )).toString()
        state.status = "Connecting"
        state.playing = false
        state.activeMode = ""
        val html = TenderApplication.context.assets.open("live_player.html").bufferedReader().use { it.readText() }
        val base = if (state.talking) "http://localhost/" else client.baseUrl.toString()
        web.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
    }

    @JavascriptInterface
    fun params(): String = params

    @JavascriptInterface
    fun post(json: String) {
        if (nl.wiebenieuwenhuis.tender.BuildConfig.DEBUG) android.util.Log.d("TenderLive", "$camera ${state.stream}: $json")
        val o = runCatching { FrigateJson.parseToJsonElement(json) }.getOrNull().obj() ?: return
        main.post {
            o["signal"].obj()?.let { sendSignal(it.toString()); return@post }
            o["micError"].str()?.let { state.micError = it }
            o["status"].str()?.let { state.status = it }
            o["mode"].str()?.let { state.activeMode = it }
            o["playing"].bool()?.let { state.playing = it }
            o["audio"].bool()?.let { state.hasAudio = it }
            val w = o["width"].dbl(); val h = o["height"].dbl()
            if (w != null && h != null && w > 0) { state.videoWidth = w; state.videoHeight = h }
        }
    }

    /** Relays the page's WebRTC handshake to go2rtc over a websocket opened by the app. */
    private fun sendSignal(text: String) {
        if (signal == null) {
            val stream = state.stream.ifEmpty { camera }
            val req = Request.Builder().url(client.liveSocketUrl(stream, "webrtc")).build()
            signal = Http.client.newWebSocket(req, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    main.post { if (signal === webSocket) web?.evaluateJavascript("onSignal($text)", null) }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    main.post { if (signal === webSocket) web?.evaluateJavascript("onSignal({type:'error', value:'connection closed'})", null) }
                }
            })
        }
        signal?.send(text)
    }

    private fun closeSignal() {
        signal?.close(1001, null)
        signal = null
    }

    fun dispose() {
        closeSignal()
        if (state.talking) TalkAudio.end()
        if (state.web === web) state.web = null
        web = null
    }
}

/** Speaker output and echo cancellation while talking; the earpiece would be far too quiet. */
object TalkAudio {
    private var active = false
    private val audio: AudioManager get() = TenderApplication.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun start() {
        if (active) return
        active = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { audio.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = true
        }
    }

    fun end() {
        if (!active) return
        active = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
        else @Suppress("DEPRECATION") { audio.isSpeakerphoneOn = false }
        audio.mode = AudioManager.MODE_NORMAL
    }
}
