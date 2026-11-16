package nl.wiebenieuwenhuis.tender.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
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
import nl.wiebenieuwenhuis.tender.ui.live.CameraSwitchItems
import nl.wiebenieuwenhuis.tender.ui.live.LiveMode
import nl.wiebenieuwenhuis.tender.ui.live.LivePlayerState
import nl.wiebenieuwenhuis.tender.ui.live.LivePlayerView
import nl.wiebenieuwenhuis.tender.ui.live.startOfToday
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import java.io.File
import java.util.Date

/** A camera's live view: the stream, sound, talk, snapshot, manual recording, PTZ, full screen and today's events. */
@Composable
fun CameraScreen(route: CameraRoute) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val conn = AppModel.connection(route.serverId)
    val client = conn?.client
    val camera = conn?.camera(route.camera)
    if (conn == null || client == null || camera == null) {
        ScreenScaffold(route.camera) { pad ->
            EmptyState(Icons.Outlined.VideocamOff, stringResource(R.string.camera_unavailable), stringResource(R.string.camera_unavailable_info), Modifier.padding(pad))
        }
        return
    }
    val streamKey = "liveStream.${route.serverId}.${route.camera}"
    val streams = conn.liveStreams(camera)
    val player = remember {
        val saved = Prefs.string(streamKey)?.takeIf { s -> streams.any { it.name == s } }
        LivePlayerState(saved ?: streams.firstOrNull()?.name ?: camera.name, muted = !LivePlayerState.soundOn)
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    player.suspended = !lifecycle.isAtLeast(Lifecycle.State.STARTED)

    var canTalk by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var showPtz by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var snapshotBusy by remember { mutableStateOf(false) }
    var recordBusy by remember { mutableStateOf(false) }
    var manualEventId by remember { mutableStateOf<String?>(null) }
    var manualStarted by remember { mutableStateOf(0.0) }
    var recordingNow by remember { mutableStateOf<Boolean?>(null) }
    var micDenied by remember { mutableStateOf(false) }
    val reviews = remember { mutableStateListOf<ReviewItem>() }
    var loadingReviews by remember { mutableStateOf(true) }
    val cameraOn = conn.isCameraEnabled(camera)

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
    LaunchedEffect(toast) { if (toast != null) { delay(3000); toast = null } }
    // Like Frigate's web view: leaving the camera ends a manual recording.
    DisposableEffect(Unit) {
        onDispose {
            manualEventId?.let { id -> AppModel.scope.launch { runCatching { client.endEvent(id) } } }
            player.talking = false
            setFullscreen(false)
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
                try { client.endEvent(id); manualEventId = null; toast = ctx.getString(R.string.recording_stopped) }
                catch (e: Exception) { error = ctx.getString(R.string.could_not_stop_recording, e.message ?: "") }
            } else {
                try {
                    manualEventId = client.startManualRecording(camera.name)
                    manualStarted = nowSeconds()
                    toast = ctx.getString(if (recordingNow == false) R.string.recording_started_snapshot_only else R.string.recording_started)
                } catch (e: FrigateException.Unauthorized) { error = ctx.getString(R.string.only_admins_record) }
                catch (e: Exception) { error = ctx.getString(R.string.could_not_start_recording, e.message ?: "") }
            }
        } finally { recordBusy = false }
    }

    val aspect = if (player.videoWidth > 0) (player.videoWidth / player.videoHeight).toFloat() else camera.aspectRatio

    @Composable
    fun Player(modifier: Modifier, fill: Boolean) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var connectingShown by remember { mutableStateOf(false) }
        LaunchedEffect(player.playing) { connectingShown = false; if (!player.playing) { delay(600); connectingShown = !player.playing } }
        Box(modifier.background(Color.Black)) {
            // Pinch to zoom, double tap to reset; the video moves, its overlays stay.
            Box(Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }, onTap = { if (fullscreen) overlayTap() }) }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            ) {
                LivePlayerView(client, camera.name, player, Modifier.fillMaxSize())
                if (!player.playing) RefreshingImage(client.latestImageUrl(camera.name, 720), 1000, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit, placeholderUrl = client.latestImageUrl(camera.name, 270))
            }
            if (!player.playing) {
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
            if (!cameraOn) OffCover(conn, camera)
            if (cameraOn) Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Clock(recordingNow == true && !conn.isRecordSwitchedOff(camera))
                if (recordingNow == false) WarningChip(stringResource(R.string.not_recording))
                if (!conn.isDetectEnabled(camera)) WarningChip(stringResource(R.string.detect_off)) { conn.setDetectEnabled(camera, true) }
                if (conn.isRecordSwitchedOff(camera)) WarningChip(stringResource(R.string.recording_off)) { conn.setRecordEnabled(camera, true) }
                if (manualEventId != null) ManualRecordingBadge(manualStarted)
                (conn.activeObjects[camera.name] ?: emptyList()).forEach { LabelIcon(it, size = 22.dp) }
            }
            if (cameraOn && streams.size > 1) StreamChip(streams.map { it.title to it.name }, player.stream, Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                player.stream = it; Prefs.setString(streamKey, it)
            }
        }
    }

    if (fullscreen) {
        // Full screen in landscape, with an overlay that hides on tap.
        var overlay by remember { mutableStateOf(true) }
        overlayTapHandler = { overlay = !overlay }
        LaunchedEffect(overlay) { if (overlay) { delay(4000); overlay = false } }
        BackHandler { fullscreen = false; setFullscreen(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Player(Modifier.fillMaxSize(), fill = false)
            if (overlay) {
                Row(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OverlayButton(if (player.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.audio)) { toggleSound() }
                    if (canTalk) OverlayButton(if (player.talking) Icons.Filled.Mic else Icons.Filled.MicNone, stringResource(R.string.talk), active = player.talking) { toggleTalk() }
                    OverlayButton(Icons.Filled.FullscreenExit, stringResource(R.string.exit_fullscreen)) { fullscreen = false; setFullscreen(false) }
                }
            }
        }
        return
    }

    val multi = AppModel.servers.size > 1
    ScreenScaffold(
        camera.displayName, subtitle = if (multi) conn.server.name else null,
        actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(menu, { menu = false }) {
                    Text(stringResource(R.string.playback_mode), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(LiveMode.Auto to stringResource(R.string.automatic), LiveMode.Mse to "MSE", LiveMode.WebRtc to "WebRTC", LiveMode.Mjpeg to "MJPEG").forEach { (m, t) ->
                        DropdownMenuItem(text = { Text(t) }, trailingIcon = { if (player.preferred == m) Icon(Icons.Filled.Check, null) },
                            onClick = { menu = false; player.preferred = m })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.reload_stream)) }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                        onClick = { menu = false; player.reloadToken++ })
                    if (cameraOn) DropdownMenuItem(text = { Text(stringResource(R.string.debug)) }, leadingIcon = { Icon(Icons.Outlined.BugReport, null) },
                        onClick = { menu = false; nav.navigate(CameraDebugRoute(route.serverId, route.camera)) })
                    CameraSwitchItems(conn, camera) { menu = false }
                }
            }
        },
    ) { pad ->
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "player") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Player(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(12.dp)), fill = false)
                    if (cameraOn) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ControlButton(if (player.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            stringResource(R.string.audio), active = !player.muted) { toggleSound() }
                        if (canTalk) ControlButton(if (player.talking) Icons.Filled.Mic else Icons.Filled.MicNone, stringResource(R.string.talk), active = player.talking) { toggleTalk() }
                        ControlButton(Icons.Filled.PhotoCamera, stringResource(R.string.snapshot), busy = snapshotBusy) { snapshot() }
                        ControlButton(if (manualEventId == null) Icons.Outlined.FiberManualRecord else Icons.Filled.Stop, stringResource(R.string.record),
                            active = manualEventId != null, activeColor = Palette.status.recording, busy = recordBusy) { toggleRecording() }
                        if (camera.hasOnvif) ControlButton(Icons.Outlined.OpenWith, stringResource(R.string.ptz)) { showPtz = true }
                        ControlButton(Icons.Filled.Fullscreen, stringResource(R.string.fullscreen)) { fullscreen = true; setFullscreen(true) }
                    }
                    player.micError?.let { Text(stringResource(R.string.microphone_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.today), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Button(onClick = { nav.navigate(PlaybackRoute(route.serverId, route.camera, startOfToday())) }) {
                            Icon(Icons.Outlined.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.playback))
                        }
                    }
                    if (loadingReviews && reviews.isEmpty()) Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else if (reviews.isEmpty()) Text(stringResource(R.string.no_activity_today), Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(reviews, key = { it.id }) { r ->
                Column(Modifier.clickable { ItemCache.put(r); nav.navigate(EventRoute(route.serverId, r.id)) }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                        RemoteImage(client.thumbnailUrl(r), Modifier.fillMaxSize())
                        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) { r.labels.take(3).forEach { LabelIcon(it, size = 18.dp) } }
                        r.endTime?.let { end -> OverlayChip(Format.duration(maxOf(0.0, end - r.startTime)), Modifier.align(Alignment.BottomEnd).padding(6.dp)) }
                    }
                    Text(Format.time(r.startTime), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showPtz) ModalBottomSheet(onDismissRequest = { showPtz = false }) { PtzControls(conn, client, camera.name) }
    error?.let { MessageDialog(stringResource(R.string.something_went_wrong), it) { error = null } }
    if (micDenied) MessageDialog(stringResource(R.string.microphone_not_available), stringResource(R.string.microphone_not_available_info)) { micDenied = false }
    toast?.let { t -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) { Snackbar { Text(t) } } }
}

private var overlayTapHandler: () -> Unit = {}
private fun overlayTap() = overlayTapHandler()

/** Turns the phone to landscape and hides the bars for full-screen video, and back. */
fun setFullscreen(on: Boolean) {
    val activity = MainActivity.current ?: return
    AppChrome.immersive = on
    activity.requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (on) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else controller.show(WindowInsetsCompat.Type.systemBars())
}

@Composable
private fun OffCover(conn: ServerConnection, camera: CameraConfig) {
    Column(Modifier.fillMaxSize().background(Color(0xFF0D0E10)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        Icon(Icons.Outlined.VideocamOff, null, tint = Color(0xFF9AA3AF), modifier = Modifier.size(30.dp))
        Text(stringResource(R.string.camera_is_off), color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.camera_is_off_info), color = Color(0xFF9AA3AF), fontSize = 13.sp, textAlign = TextAlign.Center)
        if (camera.canSwitchOff) {
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
private fun WarningChip(text: String, onSwitchOn: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(text, Modifier.background(Palette.status.warning, RoundedCornerShape(6.dp))
            .clickable(enabled = onSwitchOn != null) { open = true }.padding(horizontal = 8.dp, vertical = 3.dp),
            color = Palette.status.onLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (onSwitchOn != null) DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.switch_on)) }, onClick = { open = false; onSwitchOn() })
        }
    }
}

@Composable
private fun StreamChip(streams: List<Pair<String, String>>, current: String, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).clickable { open = true }.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Layers, stringResource(R.string.stream), Modifier.size(14.dp), tint = Color.White)
            Spacer(Modifier.width(4.dp))
            Text(streams.firstOrNull { it.second == current }?.first ?: current, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp), tint = Color.White)
        }
        DropdownMenu(open, { open = false }) {
            for ((title, name) in streams) DropdownMenuItem(text = { Text(title) }, trailingIcon = { if (name == current) Icon(Icons.Filled.Check, null) },
                onClick = { open = false; onPick(name) })
        }
    }
}

/** A round control under the video, with its label; a spinner while it waits for the server. */
@Composable
private fun ControlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false,
                          activeColor: Color = MaterialTheme.colorScheme.primary, busy: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(onClick = onClick, enabled = !busy, shape = CircleShape,
            color = if (active) activeColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (busy) SmallSpinner() else Icon(icon, label, tint = if (active) activeColor else MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OverlayButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Black.copy(alpha = 0.55f), modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else Color.White) }
    }
}
