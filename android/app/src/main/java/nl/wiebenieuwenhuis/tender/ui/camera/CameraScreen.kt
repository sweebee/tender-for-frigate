package nl.wiebenieuwenhuis.tender.ui.camera

import nl.wiebenieuwenhuis.tender.ui.explore.pinchStep
import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.MainActivity
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.CameraConfig
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.FrigateException
import nl.wiebenieuwenhuis.tender.api.ReviewItem
import nl.wiebenieuwenhuis.tender.api.nowSeconds
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.Prefs
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.AppChrome
import nl.wiebenieuwenhuis.tender.ui.CameraDebugRoute
import nl.wiebenieuwenhuis.tender.ui.CameraRoute
import nl.wiebenieuwenhuis.tender.ui.EventRoute
import nl.wiebenieuwenhuis.tender.ui.ItemCache
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.PlaybackRoute
import nl.wiebenieuwenhuis.tender.ui.components.EmptyState
import nl.wiebenieuwenhuis.tender.ui.components.LabelIcon
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.MonoDigits
import nl.wiebenieuwenhuis.tender.ui.components.OverlayChip
import nl.wiebenieuwenhuis.tender.ui.components.RefreshingImage
import nl.wiebenieuwenhuis.tender.ui.components.RemoteImage
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.components.StatusDot
import nl.wiebenieuwenhuis.tender.ui.components.isPhoneLandscape
import nl.wiebenieuwenhuis.tender.ui.components.isWideLayout
import nl.wiebenieuwenhuis.tender.ui.live.StreamSupport
import nl.wiebenieuwenhuis.tender.ui.live.CameraSwitchItems
import nl.wiebenieuwenhuis.tender.ui.live.LiveMode
import nl.wiebenieuwenhuis.tender.ui.live.LivePlayerState
import nl.wiebenieuwenhuis.tender.ui.live.LivePlayerView
import nl.wiebenieuwenhuis.tender.ui.live.startOfToday
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import java.io.File
import java.util.Date

/**
 * A camera's live view, as the iOS app lays it out:
 * - phone upright: the video, its controls and the Today header stay put; only the events scroll;
 * - phone on its side (turned by hand or by the full-screen button): full-screen video with an
 *   overlay that hides itself;
 * - tablet: the video as large as fits with controls over it and today's events in a row under
 *   it; full screen fills the window without turning the device.
 */
@Composable
fun CameraScreen(route: CameraRoute) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val conn = AppModel.connection(route.serverId)
    val client = conn?.client
    val camera = conn?.camera(route.camera)
    if (conn == null || client == null || camera == null) {
        ScreenScaffold(Format.prettyName(route.camera)) { pad ->
            EmptyState(Icons.Outlined.VideocamOff, stringResource(R.string.camera_unavailable), stringResource(R.string.camera_unavailable_info), Modifier.padding(pad))
        }
        return
    }
    val streamKey = "liveStream.${route.serverId}.${route.camera}"
    val streams = conn.liveStreams(camera)
    val player = remember {
        // Streams this device cannot decode (too large for its decoder) are left out, as in the tiles.
        val usable = StreamSupport.playable(route.serverId, streams)
        val saved = Prefs.string(streamKey)?.takeIf { s -> usable.any { it.name == s } }
        // On mobile data start with the lighter sub stream when the camera has one.
        val first = if (AppModel.isExpensiveNetwork && usable.size > 1) usable.last() else usable.firstOrNull()
        // No restream to play: straight to snapshots instead of waiting for MSE and WebRTC to time out.
        val mode = if (streams.isEmpty() && conn.go2rtcStreams != null) LiveMode.Mjpeg else LiveMode.Auto
        LivePlayerState(saved ?: first?.name ?: camera.name, mode, muted = !LivePlayerState.soundOn)
    }
    // A stream the user picked in the menu is kept, even one this device turns out not to decode.
    var pickedStream by remember { mutableStateOf(false) }
    fun pickStream(s: String) { player.stream = s; Prefs.setString(streamKey, s); pickedStream = true }
    // The stream started but this device cannot decode it at its size: switch to a lighter one
    // rather than wait for MSE and WebRTC to fail and end up on snapshots.
    LaunchedEffect(player.mseInfo) {
        val info = player.mseInfo ?: return@LaunchedEffect
        val current = player.stream
        if (!StreamSupport.check(route.serverId, current, info) || pickedStream) return@LaunchedEffect
        StreamSupport.playable(route.serverId, streams).firstOrNull { it.name != current }?.let { player.stream = it.name }
    }
    val cameraOn = conn.isCameraEnabled(camera)
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle by lifecycleOwner.lifecycle.currentStateAsState()
    player.suspended = !lifecycle.isAtLeast(Lifecycle.State.STARTED) || !cameraOn

    val wide = isWideLayout()
    val phoneLandscape = isPhoneLandscape()
    var filling by remember { mutableStateOf(false) }        // tablet: video fills the window
    var turnedForFullscreen by remember { mutableStateOf(false) } // phone turned by the button
    val landscapeLayout = phoneLandscape || (wide && filling)

    var canTalk by remember { mutableStateOf(false) }
    var showPtz by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var snapshotBusy by remember { mutableStateOf(false) }
    var recordBusy by remember { mutableStateOf(false) }
    var manualEventId by remember { mutableStateOf<String?>(null) }
    var manualStarted by remember { mutableStateOf(0.0) }
    var recordingNow by remember { mutableStateOf<Boolean?>(null) }
    var micDenied by remember { mutableStateOf(false) }
    var switchingOn by remember { mutableStateOf(false) }
    val reviews = remember { mutableStateListOf<ReviewItem>() }
    val reviewActions = remember { nl.wiebenieuwenhuis.tender.ui.review.ReviewActions(scope) }
    reviewActions.onDeleted = { e -> reviews.removeAll { it.id == e.item.id } }
    var loadingReviews by remember { mutableStateOf(true) }
    // The overlay (landscape, tablet): shown, and restarted by every touch.
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayToken by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    fun keepOverlay() { overlayVisible = true; overlayToken++ }

    fun stopManualRecording() {
        val id = manualEventId ?: return
        manualEventId = null
        AppModel.scope.launch { runCatching { client.endEvent(id) } }
    }

    LaunchedEffect(player.stream) {
        canTalk = client.supportsTwoWayAudio(player.stream.ifEmpty { camera.name })
        if (!canTalk) player.talking = false
    }
    LaunchedEffect(client) {
        loadingReviews = true
        runCatching { client.reviews(cameras = listOf(camera.name), after = startOfToday() / 1000.0, limit = 50) }.getOrNull()?.let {
            reviews.clear(); reviews.addAll(it)
        }
        loadingReviews = false
    }
    // Shows REC only while Frigate is actually writing recordings: segments land 5 to 20 s late.
    LaunchedEffect(client) {
        var misses = 0
        while (isActive) {
            val now = nowSeconds()
            runCatching { client.recordings(camera.name, now - 120, now) }.getOrNull()?.let { segs ->
                val fresh = now - (segs.maxOfOrNull { it.endTime } ?: 0.0) < 45
                misses = if (fresh) 0 else misses + 1
                if (fresh) recordingNow = true
                else if (misses >= 2 || recordingNow == null) recordingNow = if (conn.isRecordEnabled(camera)) false else null
            }
            delay(10_000)
        }
    }
    // Switched on: Frigate starts the camera's stream a moment later; retry for half a minute.
    var wasOn by remember { mutableStateOf(cameraOn) }
    LaunchedEffect(cameraOn) {
        if (cameraOn && !wasOn) switchingOn = true
        if (!cameraOn) { switchingOn = false; stopManualRecording() }
        wasOn = cameraOn
    }
    LaunchedEffect(player.playing) { if (player.playing) switchingOn = false }
    LaunchedEffect(switchingOn) {
        if (!switchingOn) return@LaunchedEffect
        repeat(10) {
            delay(3000)
            if (!switchingOn) return@LaunchedEffect
            if (player.status.startsWith("Stream unavailable")) player.reloadToken++
        }
        switchingOn = false
    }
    LaunchedEffect(player.micError) {
        if (player.micError != null) { player.talking = false; player.micError = null; micDenied = true }
    }
    LaunchedEffect(toast) { toast?.let { delay(it.second); toast = null } }
    // The overlay hides after 4 s without touches, but not while talking, a menu or PTZ is open.
    LaunchedEffect(overlayToken, landscapeLayout, wide) {
        if (!landscapeLayout && !wide) return@LaunchedEffect
        delay(4000)
        if (player.talking || showPtz || menuOpen) return@LaunchedEffect
        overlayVisible = false
    }
    // Full screen on a phone (however it got there): no system bars.
    LaunchedEffect(landscapeLayout) { setSystemBars(hidden = landscapeLayout) }
    // Turned upright again by hand: no longer "turned for full screen".
    LaunchedEffect(phoneLandscape) { if (!phoneLandscape) turnedForFullscreen = false }
    // Leaving for the background ends a manual recording, as on iOS.
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) stopManualRecording() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    DisposableEffect(Unit) {
        onDispose {
            stopManualRecording()
            player.talking = false
            setSystemBars(hidden = false)
            AppChrome.immersive = false
            MainActivity.current?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { player.talking = true; if (player.muted) player.setMutedNow(false) } else micDenied = true
    }
    fun toggleTalk() {
        if (player.talking) { player.talking = false; return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            player.talking = true
            if (player.muted) player.setMutedNow(false)
        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun toggleSound() {
        player.setMutedNow(!player.muted)
        Prefs.setBool(LivePlayerState.SOUND_KEY, !player.muted)
    }
    fun snapshot() = scope.launch {
        snapshotBusy = true
        try {
            val bytes = client.data("api/${camera.name}/latest.jpg")
            val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "${camera.name}-${System.currentTimeMillis() / 1000}.jpg").apply { writeBytes(bytes) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, null))
        } catch (e: Exception) { error = e.message } finally { snapshotBusy = false }
    }
    fun toggleRecording() = scope.launch {
        recordBusy = true
        try {
            val id = manualEventId
            if (id != null) {
                try { client.endEvent(id); manualEventId = null; toast = ctx.getString(R.string.recording_stopped) to 2500L }
                catch (e: Exception) { error = ctx.getString(R.string.could_not_stop_recording, e.message ?: "") }
            } else {
                try {
                    manualEventId = client.startManualRecording(camera.name)
                    manualStarted = nowSeconds()
                    toast = if (recordingNow == false) ctx.getString(R.string.recording_started_snapshot_only) to 5000L
                    else ctx.getString(R.string.recording_started) to 2500L
                } catch (e: FrigateException.Unauthorized) { error = ctx.getString(R.string.only_admins_record) }
                catch (e: Exception) { error = ctx.getString(R.string.could_not_start_recording, e.message ?: "") }
            }
        } finally { recordBusy = false }
    }
    /** Phone: turn sideways (full screen) or back upright. Tablet: fill the window. */
    fun setFullscreen(on: Boolean) {
        val activity = MainActivity.current
        if (wide) {
            filling = on
            AppChrome.immersive = on
        } else if (activity != null) {
            turnedForFullscreen = on
            activity.requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // Back upright: let the sensor decide again a moment later (turning the phone gives full screen).
            if (!on) AppModel.scope.launch { delay(2500); if (!turnedForFullscreen) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        keepOverlay()
    }
    BackHandler(enabled = filling || turnedForFullscreen) { setFullscreen(false) }

    val aspect = if (player.videoWidth > 0) (player.videoWidth / player.videoHeight).toFloat() else camera.aspectRatio
    val subtitle = buildList {
        add(conn.server.name)
        if (player.videoWidth > 0) add("${player.videoWidth.toInt()} × ${player.videoHeight.toInt()}")
        if (player.activeMode.isNotEmpty()) add(player.activeMode.uppercase())
    }.joinToString(" · ")
    val objects = conn.activeObjects[camera.name] ?: emptyList()

    // The player keeps its connection when the layout changes (turning the phone, full screen).
    val playerContent = remember(client, camera.name) {
        movableContentOf { modifier: Modifier, chrome: Boolean, onTap: () -> Unit ->
            VideoBox(client, camera, player, conn, modifier, chrome, onTap, switchingOn, recordingNow, manualEventId != null, manualStarted,
                streams.map { it.title to it.name }) { s -> pickStream(s) }
        }
    }

    @Composable
    fun MoreMenu(tint: Color = MaterialTheme.colorScheme.onSurface) {
        Box {
            IconButton(onClick = { menuOpen = true; keepOverlay() }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more), tint = tint) }
            DropdownMenu(menuOpen, { menuOpen = false; keepOverlay() }) {
                listOf(LiveMode.Auto to stringResource(R.string.automatic), LiveMode.Mse to "MSE", LiveMode.WebRtc to "WebRTC", LiveMode.Mjpeg to "MJPEG").forEach { (m, t) ->
                    DropdownMenuItem(text = { Text(t) }, leadingIcon = { if (player.preferred == m) Icon(Icons.Filled.Check, null) },
                        onClick = { menuOpen = false; player.preferred = m; keepOverlay() })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.reload_stream)) }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                    onClick = { menuOpen = false; player.reloadToken++ })
                if (cameraOn) DropdownMenuItem(text = { Text(stringResource(R.string.debug)) }, leadingIcon = { Icon(Icons.Outlined.BugReport, null) },
                    onClick = { menuOpen = false; nav.navigate(CameraDebugRoute(route.serverId, route.camera)) })
                CameraSwitchItems(conn, camera) { menuOpen = false }
            }
        }
    }

    val audioEnabled = player.hasAudio || player.activeMode != "mjpeg"

    nl.wiebenieuwenhuis.tender.ui.review.ReviewActionsHost(reviewActions, Modifier.fillMaxSize()) {
    if (landscapeLayout) {
        // Full screen: video, and an overlay that hides on tap or after 4 s.
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            playerContent(Modifier.fillMaxSize(), false) { overlayVisible = !overlayVisible; if (overlayVisible) keepOverlay() }
            if (showPtz) Box(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 64.dp)) {
                PtzControls(conn, client, camera.name, overlay = true)
            }
            androidx.compose.animation.AnimatedVisibility(overlayVisible, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = { if (turnedForFullscreen || filling) setFullscreen(false) else nav.popBackStack() }) {
                            Icon(if (turnedForFullscreen || filling) Icons.Filled.FullscreenExit else Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(if (turnedForFullscreen || filling) R.string.exit_fullscreen else R.string.back), tint = Color.White)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(camera.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(subtitle, color = Color(0xFF9AA3AF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        StatusChips(conn, camera, recordingNow, manualEventId != null, manualStarted)
                        if (streams.size > 1) StreamChip(streams.map { it.title to it.name }, player.stream, Modifier,
                            onOpen = { menuOpen = true; keepOverlay() }, onClose = { menuOpen = false; keepOverlay() }) { s ->
                            pickStream(s)
                        }
                        if (AppModel.liveLabels) Clock(recordingNow == true && !conn.isRecordSwitchedOff(camera))
                        MoreMenu(Color.White)
                    }
                    Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).alpha(if (cameraOn) 1f else 0.4f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OverlayButton(if (player.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            stringResource(R.string.audio), enabled = cameraOn && audioEnabled) { toggleSound(); keepOverlay() }
                        OverlayButton(Icons.Filled.PhotoCamera, stringResource(R.string.snapshot), busy = snapshotBusy, enabled = cameraOn) { snapshot(); keepOverlay() }
                        OverlayButton(if (manualEventId == null) Icons.Outlined.FiberManualRecord else Icons.Filled.Stop, stringResource(R.string.record),
                            busy = recordBusy, ring = if (manualEventId != null) Palette.status.recording else null, enabled = cameraOn) { toggleRecording(); keepOverlay() }
                        if (canTalk) OverlayButton(if (player.talking) Icons.Filled.Mic else Icons.Filled.MicNone, stringResource(R.string.talk),
                            ring = if (player.talking) MaterialTheme.colorScheme.primary else null, enabled = cameraOn) { toggleTalk(); keepOverlay() }
                        if (camera.hasOnvif) OverlayButton(if (showPtz) Icons.Filled.Close else Icons.Outlined.OpenWith, stringResource(R.string.ptz), enabled = cameraOn) {
                            showPtz = !showPtz; keepOverlay()
                        }
                    }
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (AppModel.liveLabels) objects.forEach { LabelIcon(it, size = 22.dp) }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { nav.navigate(PlaybackRoute(route.serverId, route.camera, startOfToday())) },
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.55f))) {
                            Icon(Icons.Outlined.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.playback))
                        }
                    }
                }
            }
        }
    } else if (wide) {
        // Tablet: the video as large as fits, controls over it, today's events in a row under it.
        ScreenScaffold(camera.displayName, subtitle = subtitle, actions = { MoreMenu() }) { pad ->
            BoxWithConstraints(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
                val stripHeight = 196.dp
                val videoHeight = min(maxWidth / aspect, maxHeight - stripHeight).coerceAtLeast(160.dp)
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(videoHeight).width(videoHeight * aspect).clip(RoundedCornerShape(12.dp))) {
                        playerContent(Modifier.fillMaxSize(), true) { overlayVisible = !overlayVisible; if (overlayVisible) keepOverlay() }
                        androidx.compose.animation.AnimatedVisibility(overlayVisible || player.talking, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
                            Row(Modifier.padding(16.dp).alpha(if (cameraOn) 1f else 0.4f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                OverlayButton(if (player.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    stringResource(R.string.audio), size = 56, filled = !player.muted, enabled = cameraOn && audioEnabled) { toggleSound(); keepOverlay() }
                                if (canTalk) OverlayButton(if (player.talking) Icons.Filled.Mic else Icons.Filled.MicNone, stringResource(R.string.talk),
                                    size = 56, filled = player.talking, enabled = cameraOn) { toggleTalk(); keepOverlay() }
                                OverlayButton(Icons.Filled.PhotoCamera, stringResource(R.string.snapshot), size = 56, busy = snapshotBusy, enabled = cameraOn) { snapshot(); keepOverlay() }
                                OverlayButton(if (manualEventId == null) Icons.Outlined.FiberManualRecord else Icons.Filled.Stop, stringResource(R.string.record),
                                    size = 56, busy = recordBusy, filled = manualEventId != null, fillColor = Palette.status.recording, enabled = cameraOn) { toggleRecording(); keepOverlay() }
                                if (camera.hasOnvif) OverlayButton(Icons.Outlined.OpenWith, stringResource(R.string.ptz), size = 56, filled = showPtz, enabled = cameraOn) {
                                    showPtz = !showPtz; keepOverlay()
                                }
                                OverlayButton(Icons.Filled.Fullscreen, stringResource(R.string.fullscreen), size = 56) { setFullscreen(true) }
                            }
                        }
                        if (showPtz) Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 96.dp)) {
                            PtzControls(conn, client, camera.name, overlay = true)
                        }
                    }
                    TodayHeader(route) { }
                    TodayRow(client, route, reviews, loadingReviews, reviewActions)
                }
            }
        }
    } else {
        // Phone upright: video, controls and the Today header stay put; only the events scroll.
        ScreenScaffold(camera.displayName, subtitle = subtitle, actions = { MoreMenu() }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                playerContent(Modifier.fillMaxWidth().aspectRatio(aspect), true) {}
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).alpha(if (cameraOn) 1f else 0.35f), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ControlButton(if (player.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        stringResource(R.string.audio), active = !player.muted, enabled = cameraOn && audioEnabled) { toggleSound() }
                    if (canTalk) ControlButton(if (player.talking) Icons.Filled.Mic else Icons.Filled.MicNone, stringResource(R.string.talk),
                        active = player.talking, enabled = cameraOn) { toggleTalk() }
                    ControlButton(Icons.Filled.PhotoCamera, stringResource(R.string.snapshot), busy = snapshotBusy, enabled = cameraOn) { snapshot() }
                    ControlButton(if (manualEventId == null) Icons.Outlined.FiberManualRecord else Icons.Filled.Stop, stringResource(R.string.record),
                        active = manualEventId != null, activeColor = Palette.status.recording, busy = recordBusy, enabled = cameraOn) { toggleRecording() }
                    if (camera.hasOnvif) ControlButton(Icons.Outlined.OpenWith, stringResource(R.string.ptz), active = showPtz, enabled = cameraOn) { showPtz = !showPtz }
                    ControlButton(Icons.Filled.Fullscreen, stringResource(R.string.fullscreen), enabled = cameraOn) { setFullscreen(true) }
                }
                // PTZ under the controls: the video stays in view and usable while steering.
                androidx.compose.animation.AnimatedVisibility(showPtz) { Box(Modifier.fillMaxWidth().padding(top = 8.dp)) { PtzControls(conn, client, camera.name, overlay = false) } }
                TodayHeader(route, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { }
                when {
                    loadingReviews && reviews.isEmpty() -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    reviews.isEmpty() -> Text(stringResource(R.string.no_activity_today), Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyVerticalGrid(
                        GridCells.Fixed(2), Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(reviews, key = { it.id }) { r -> EventTile(client, route.serverId, r, reviewActions, Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }

    }
    error?.let { MessageDialog(stringResource(R.string.something_went_wrong), it) { error = null } }
    if (micDenied) AlertDialog(
        onDismissRequest = { micDenied = false },
        title = { Text(stringResource(R.string.microphone_not_available)) },
        text = { Text(stringResource(R.string.microphone_not_available_info)) },
        confirmButton = {
            TextButton(onClick = {
                micDenied = false
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            }) { Text(stringResource(R.string.settings)) }
        },
        dismissButton = { TextButton(onClick = { micDenied = false }) { Text(stringResource(R.string.ok)) } },
    )
    // A short message at the top, in the same pill as saving clips.
    toast?.let { (t, _) ->
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.TopCenter) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 4.dp) {
                Text(t, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Hides or shows the status and navigation bars (full-screen video). */
private fun setSystemBars(hidden: Boolean) {
    val activity = MainActivity.current ?: return
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (hidden) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else controller.show(WindowInsetsCompat.Type.systemBars())
}

/** The video with its picture underneath until it plays, status, the off cover and (with `chrome`) the clock and chips. */
@Composable
private fun VideoBox(
    client: FrigateClient, camera: CameraConfig, player: LivePlayerState, conn: ServerConnection,
    modifier: Modifier, chrome: Boolean, onTap: () -> Unit, switchingOn: Boolean, recordingNow: Boolean?,
    manualRecording: Boolean, manualStarted: Double, streams: List<Pair<String, String>>, onPickStream: (String) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var connectingShown by remember { mutableStateOf(false) }
    val cameraOn = conn.isCameraEnabled(camera)
    LaunchedEffect(player.playing) { connectingShown = false; if (!player.playing) { delay(600); connectingShown = !player.playing } }
    Box(modifier.background(Color.Black)) {
        // The video moves when zoomed; its overlays stay.
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }) {
            LivePlayerView(client, camera.name, player, Modifier.fillMaxSize())
            if (!player.playing) RefreshingImage(client.latestImageUrl(camera.name, 720), 1000, Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit, placeholderUrl = client.latestImageUrl(camera.name, 270))
        }
        // Pinch to zoom, double tap to reset, tap for the controls: on a layer above the web
        // view, which would otherwise take the touches itself.
        Box(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    pinchStep(scale, offset, centroid, pan, zoom, size, 6f).let { (s, o) -> scale = s; offset = o }
                }
            }
            .pointerInput(onTap) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }, onTap = { onTap() }) })
        if (!player.playing && cameraOn && !switchingOn) {
            if (player.status.startsWith("Stream unavailable")) Column(
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Warning, null, tint = Color.White)
                Text(player.statusText, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                TextButton(onClick = { player.reloadToken++ }) { Text(stringResource(R.string.retry)) }
            } else if (connectingShown) Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(12.dp), color = Color.White, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(6.dp))
                Text(player.statusText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (!cameraOn || (switchingOn && !player.playing)) OffCover(conn, camera, switching = cameraOn)
        if (chrome && cameraOn) Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (AppModel.liveLabels) Clock(recordingNow == true && !conn.isRecordSwitchedOff(camera))
            StatusChips(conn, camera, recordingNow, manualRecording, manualStarted)
            if (AppModel.liveLabels) (conn.activeObjects[camera.name] ?: emptyList()).forEach { LabelIcon(it, size = 22.dp) }
        }
        if (chrome && cameraOn && streams.size > 1) StreamChip(streams, player.stream, Modifier.align(Alignment.BottomEnd).padding(8.dp), onPick = onPickStream)
    }
}

/** "Not recording", detection or recording switched off (tap to switch on), and a running manual recording. */
@Composable
private fun StatusChips(conn: ServerConnection, camera: CameraConfig, recordingNow: Boolean?, manualRecording: Boolean, manualStarted: Double) {
    // A running manual recording always shows: it is the way to see it is on.
    if (!AppModel.liveLabels) { if (manualRecording) ManualRecordingBadge(manualStarted); return }
    if (recordingNow == false) WarningChip(stringResource(R.string.not_recording))
    if (conn.isCameraEnabled(camera)) {
        if (!conn.isDetectEnabled(camera)) WarningChip(stringResource(R.string.detect_off), stringResource(R.string.switch_detection_on)) { conn.setDetectEnabled(camera, true) }
        if (conn.isRecordSwitchedOff(camera)) WarningChip(stringResource(R.string.recording_off), stringResource(R.string.switch_recording_on)) { conn.setRecordEnabled(camera, true) }
    }
    if (manualRecording) ManualRecordingBadge(manualStarted)
}

@Composable
private fun TodayHeader(route: CameraRoute, modifier: Modifier = Modifier, unused: () -> Unit) {
    val nav = LocalNav.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.today), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Button(onClick = { nav.navigate(PlaybackRoute(route.serverId, route.camera, startOfToday())) }) {
            Icon(Icons.Outlined.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.playback))
        }
    }
}

/** Tablet: today's events as one row under the video. */
@Composable
private fun TodayRow(client: FrigateClient, route: CameraRoute, reviews: List<ReviewItem>, loading: Boolean, actions: nl.wiebenieuwenhuis.tender.ui.review.ReviewActions) {
    when {
        loading && reviews.isEmpty() -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        reviews.isEmpty() -> Text(stringResource(R.string.no_activity_today), Modifier.fillMaxWidth().padding(vertical = 20.dp),
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(reviews, key = { it.id }) { r -> EventTile(client, route.serverId, r, actions, Modifier.width(168.dp)) }
        }
    }
}

@Composable
private fun EventTile(client: FrigateClient, serverId: String, r: ReviewItem, actions: nl.wiebenieuwenhuis.tender.ui.review.ReviewActions, modifier: Modifier) {
    val nav = LocalNav.current
    nl.wiebenieuwenhuis.tender.ui.live.ReviewMenuBox(serverId, r, actions, onClick = { ItemCache.put(r); nav.navigate(EventRoute(serverId, r.id)) }, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))) {
                RemoteImage(client.thumbnailUrl(r), Modifier.fillMaxSize())
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) { r.labels.take(3).forEach { LabelIcon(it, size = 18.dp) } }
                r.endTime?.let { end -> OverlayChip(Format.duration(maxOf(0.0, end - r.startTime)), Modifier.align(Alignment.BottomEnd).padding(6.dp)) }
            }
            Text(Format.time(r.startTime), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Instead of the video while the camera is off in Frigate, or while it starts after being switched on. */
@Composable
private fun OffCover(conn: ServerConnection, camera: CameraConfig, switching: Boolean) {
    Column(Modifier.fillMaxSize().background(Color(0xFF0D0E10)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        if (switching) CircularProgressIndicator(color = Color.White)
        else Icon(Icons.Outlined.VideocamOff, null, tint = Color(0xFF9AA3AF), modifier = Modifier.size(30.dp))
        Text(stringResource(if (switching) R.string.switching_camera_on else R.string.camera_is_off), color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(stringResource(if (switching) R.string.switching_camera_on_info else R.string.camera_is_off_info), color = Color(0xFF9AA3AF),
            fontSize = 13.sp, textAlign = TextAlign.Center)
        if (!switching && camera.canSwitchOff) {
            val pending = conn.isSwitchPending(camera, "enabled")
            Button(onClick = { conn.setCameraEnabled(camera, true) }, enabled = !pending) {
                if (pending) SmallSpinner() else Text(stringResource(R.string.switch_on))
            }
        }
    }
}

/** The running clock, with a red dot while the camera is recording. */
@Composable
private fun Clock(recording: Boolean) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (isActive) { now = Date(); delay(1000) } }
    Row(Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp).height(22.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (recording) { StatusDot(Palette.status.recording, size = 7.dp); Spacer(Modifier.width(5.dp)) }
        Text(Format.time(now, seconds = true), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = MonoDigits)
    }
}

@Composable
private fun ManualRecordingBadge(started: Double) {
    var now by remember { mutableStateOf(nowSeconds()) }
    LaunchedEffect(Unit) { while (isActive) { now = nowSeconds(); delay(1000) } }
    Row(Modifier.background(Palette.status.recording, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp).height(22.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("● REC " + Format.duration(now - started), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = MonoDigits)
    }
}

/** "Detect off" / "Recording off" by the clock; a tap offers to switch it on again. */
@Composable
private fun WarningChip(text: String, switchOnText: String? = null, onSwitchOn: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(text, Modifier.background(Palette.status.warning, RoundedCornerShape(6.dp))
            .clickable(enabled = onSwitchOn != null) { open = true }.padding(horizontal = 8.dp, vertical = 3.dp),
            color = Palette.status.onLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (onSwitchOn != null) DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(switchOnText ?: stringResource(R.string.switch_on)) }, onClick = { open = false; onSwitchOn() })
        }
    }
}

@Composable
private fun StreamChip(
    streams: List<Pair<String, String>>, current: String, modifier: Modifier,
    onOpen: () -> Unit = {}, onClose: () -> Unit = {}, onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).clickable { open = true; onOpen() }.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Layers, stringResource(R.string.stream), Modifier.size(14.dp), tint = Color.White)
            Spacer(Modifier.width(4.dp))
            Text(streams.firstOrNull { it.second == current }?.first ?: current, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp), tint = Color.White)
        }
        DropdownMenu(open, { open = false; onClose() }) {
            for ((title, name) in streams) DropdownMenuItem(text = { Text(title) }, leadingIcon = { if (name == current) Icon(Icons.Filled.Check, null) },
                onClick = { open = false; onClose(); onPick(name) })
        }
    }
}

/** A round control under the video, with its label; a spinner while it waits for the server. */
@Composable
private fun ControlButton(icon: ImageVector, label: String, active: Boolean = false, activeColor: Color = MaterialTheme.colorScheme.primary,
                          enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.alpha(if (enabled) 1f else 0.35f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(onClick = onClick, enabled = enabled && !busy, shape = CircleShape,
            color = if (active) activeColor else MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (busy) SmallSpinner() else Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A round button over the video (landscape and tablet), with an optional on-state ring or fill. */
@Composable
private fun OverlayButton(
    icon: ImageVector, label: String, size: Int = 44, busy: Boolean = false, enabled: Boolean = true,
    ring: Color? = null, filled: Boolean = false, fillColor: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled && !busy, shape = CircleShape,
        color = if (filled) fillColor else Color.Black.copy(alpha = 0.6f),
        modifier = Modifier.size(size.dp).alpha(if (enabled) 1f else 0.4f).then(if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier)) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.45f).dp))
        }
    }
}
