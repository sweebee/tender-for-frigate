package nl.wiebenieuwenhuis.tender.ui.live

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.CameraConfig
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.StreamChoice
import nl.wiebenieuwenhuis.tender.api.toDate
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.app.TileStream
import nl.wiebenieuwenhuis.tender.ui.components.LabelIcon
import nl.wiebenieuwenhuis.tender.ui.components.RefreshingImage
import nl.wiebenieuwenhuis.tender.ui.components.StatusDot
import nl.wiebenieuwenhuis.tender.ui.theme.Palette

/** A camera in the Live grid: its latest picture (or live video), objects, motion, and the red outline while an object moves. */
@Composable
fun CameraTile(conn: ServerConnection, camera: CameraConfig, modifier: Modifier = Modifier, wholePicture: Boolean = false, fill: Boolean = false) {
    val status = Palette.status
    val on = conn.isCameraEnabled(camera)
    val online = on && conn.isCameraOnline(camera.name)
    val objects = if (on) conn.activeObjects[camera.name] ?: emptyList() else emptyList()
    val activity = if (online) conn.activity[camera.name] else null
    val tracking = activity?.tracking?.isNotEmpty() == true
    val active = activity?.motion == true || tracking
    // Detection or recording switched off: orange, a warning rather than a fault.
    val detectOff = online && !conn.isDetectEnabled(camera)
    val recordOff = online && conn.isRecordSwitchedOff(camera)
    var autoLiveOn by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }

    // Automatic live view: go live on activity and stay live a little after it ends.
    LaunchedEffect(active) {
        if (active) autoLiveOn = true
        else if (autoLiveOn) { delay(10_000); autoLiveOn = false }
    }

    val shape = RoundedCornerShape(12.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            // With `fill` the picture takes the height the grid gives the tile (TileBand) and shows the
            // camera's whole picture inside it; otherwise the camera's own shape for one tile per row,
            // or 16:10 so tiles side by side line up.
            (if (fill) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(if (wholePicture) camera.aspectRatio else 16f / 10f))
                .clip(shape)
                .border(if (tracking) 1.5.dp else 1.dp, if (tracking) status.recording else MaterialTheme.colorScheme.outlineVariant, shape)
                .onSizeChanged { widthPx = it.width },
        ) {
            if (!on) {
                // Switched off in Frigate: no picture to show, and not a fault like Offline.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.VideocamOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val client = conn.client
                if (client != null) {
                    val factor = if (AppModel.isExpensiveNetwork) 2.5 else 1.0
                    val refresh = if (online) ((if (objects.isEmpty()) 6 else 2) * factor * 1000).toLong() else 0L
                    RefreshingImage(client.latestImageUrl(camera.name, 270), refresh, Modifier.fillMaxSize().alpha(if (online) 1f else 0.4f),
                        contentScale = if (fill) ContentScale.Fit else ContentScale.Crop)
                    val stream = tileStream(conn, camera, widthPx)
                    if (online && stream != null && (AppModel.showsLiveGrid || (AppModel.autoLive && autoLiveOn))) {
                        TileLivePlayer(conn, client, camera, stream.name, Modifier.fillMaxSize(), fill = !fill)
                    }
                }
            }
            if (on && !online) Row(
                Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(status.offline, size = 6.dp)
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.offline).uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            // Top left, like the label icons on events and recent alerts.
            if (AppModel.tileLabels && online && objects.isNotEmpty()) Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                objects.take(3).forEach { LabelIcon(it) }
            }
            if (AppModel.tileLabels && activity?.motion == true) MotionDot(Modifier.align(Alignment.TopEnd).padding(8.dp))
            if (AppModel.tileLabels && (detectOff || recordOff)) Row(Modifier.align(Alignment.BottomEnd).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (detectOff) SwitchedOffIcon(detect = true)
                if (recordOff) SwitchedOffIcon(detect = false)
            }
        }
        Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(camera.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            val meta = when {
                detectOff && recordOff -> stringResource(R.string.detect_and_record_off)
                detectOff -> stringResource(R.string.detect_off)
                recordOff -> stringResource(R.string.recording_off)
                !on -> stringResource(R.string.off)
                !online -> stringResource(R.string.offline)
                objects.isNotEmpty() -> stringResource(R.string.now)
                else -> conn.lastActivity[camera.name]?.let { nl.wiebenieuwenhuis.tender.ui.components.relativeTime(it) } ?: ""
            }
            Text(meta, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                color = if (detectOff || recordOff) status.warning else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The stream a live tile plays: the lightest (a camera's sub stream) unless the tile is wider
 * than about 800 pixels and the connection is not metered: then the main stream.
 */
private fun tileStream(conn: ServerConnection, camera: CameraConfig, widthPx: Int): StreamChoice? {
    val streams = StreamSupport.playable(conn.id, conn.liveStreams(camera, inFrigateOrder = false))
    return when (AppModel.tileStream) {
        TileStream.Main -> streams.firstOrNull()
        TileStream.Sub -> streams.lastOrNull()
        TileStream.Automatic -> if (streams.size > 1 && widthPx > 800 && !AppModel.isExpensiveNetwork) streams.first() else streams.lastOrNull()
    }
}

/** Muted live video for a tile; the picture underneath stays visible until the video plays. */
@Composable
fun TileLivePlayer(conn: ServerConnection, client: FrigateClient, camera: CameraConfig, stream: String, modifier: Modifier = Modifier, fill: Boolean = true) {
    val state = remember(stream) { LivePlayerState(stream, LiveMode.Mse) }
    // A stream this device cannot decode is remembered; the tile then picks another one (tileStream).
    LaunchedEffect(state.mseInfo) { state.mseInfo?.let { StreamSupport.check(conn.id, stream, it) } }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    state.suspended = !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LivePlayerView(client, camera.name, state, modifier.alpha(if (state.playing) 1f else 0f), fill = fill)
}

/** Pulsing red dot shown while Frigate sees motion, like the web UI. */
@Composable
fun MotionDot(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "motion")
    val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    Box(modifier.size(7.dp).alpha(a).background(Palette.status.recording, CircleShape))
}

/** Detection or recording switched off in Frigate: its symbol struck through, in orange. */
@Composable
fun SwitchedOffIcon(detect: Boolean, size: Dp = 13.dp) {
    val warning = Palette.status.warning
    Box(
        Modifier.size(20.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (detect) Icons.Outlined.CenterFocusStrong else Icons.Outlined.FiberManualRecord,
            stringResource(if (detect) R.string.detect_off else R.string.recording_off),
            Modifier.size(size).drawWithContent {
                drawContent()
                drawLine(warning, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(this.size.width, this.size.height), strokeWidth = 2.dp.toPx())
            },
            tint = warning,
        )
    }
}
