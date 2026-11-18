package nl.wiebenieuwenhuis.tender.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import nl.wiebenieuwenhuis.tender.api.MotionActivity
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.theme.Palette

private const val STRIP_BUCKET = 30.0

/**
 * The preview row of Frigate's recording view: the server's other cameras at the playhead's
 * moment, from their previews (as in Review's Motion tab), with a border on those that see motion
 * then. A tap plays that camera, at the same moment.
 */
@Composable
internal fun PlaybackCameraStrip(
    state: PlaybackState,
    height: Dp,
    modifier: Modifier = Modifier,
    onVideo: Boolean = false,
    margin: Dp = 16.dp,
    onUse: () -> Unit = {},
) {
    val conn = state.conn ?: return
    if (conn.cameras.size < 2) return
    CameraStrip(conn, state.camera, { state.wallTime }, state.dayStart, state.dayEnd, height, modifier, onVideo, margin) {
        state.switchCamera(it)
        onUse()
    }
}

@Composable
private fun CameraStrip(
    conn: ServerConnection,
    current: String,
    wallTime: () -> Double,
    dayStart: Double,
    dayEnd: Double,
    height: Dp,
    modifier: Modifier,
    onVideo: Boolean,
    margin: Dp,
    onSelect: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // One scrubber per camera, the one playing too: picked again, it is ready.
    val previews = remember(conn.id) { mutableStateMapOf<String, PreviewScrubber>() }
    // Motion buckets per camera, oldest first.
    val activity = remember(conn.id) { mutableStateMapOf<String, List<MotionActivity>>() }
    var loaded by remember(conn.id) { mutableStateOf(false) }
    // Each moment once per second: previews have about one frame a second.
    val second by remember { derivedStateOf { wallTime().toLong() } }
    val names = conn.cameras.map { it.name }

    LaunchedEffect(conn.id, names, dayStart) {
        for (n in names) if (previews[n] == null) previews[n] = PreviewScrubber(scope)
        val client = conn.client ?: return@LaunchedEffect
        coroutineScope {
            val jobs = names.mapNotNull { n -> previews[n]?.let { p -> async { p.prepare(client, n, dayStart - 3600, dayEnd) } } } +
                async {
                    val byCamera = client.motionActivityByCamera(names, dayStart, dayEnd, STRIP_BUCKET.toInt())
                    for ((cam, items) in byCamera) activity[cam] = items.sortedBy { it.startTime }
                }
            jobs.awaitAll()
        }
        loaded = true
        previews.values.forEach { it.show(wallTime()) }
    }
    LaunchedEffect(second) { previews.values.forEach { it.show(wallTime()) } }

    val shape = RoundedCornerShape(7.dp)
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = margin), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(conn.cameras.filter { it.name != current }, key = { it.name }) { cam ->
            val width = height * cam.aspectRatio.coerceIn(1f, 2.4f)
            val active = hasMotion(activity[cam.name], second.toDouble())
            val preview = previews[cam.name]
            Column(
                Modifier.width(width).clickable { onSelect(cam.name) }.semantics { contentDescription = cam.displayName },
            ) {
                Box(
                    Modifier.size(width, height).clip(shape).background(Color.Black)
                        .border(if (active) 2.dp else 1.dp, if (active) Palette.status.motion else MaterialTheme.colorScheme.outlineVariant, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (preview != null) PreviewSurface(preview, Modifier.fillMaxSize())
                    if (preview?.showing != true && loaded) {
                        Icon(Icons.Filled.VideocamOff, null, Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.45f))
                    }
                }
                Text(
                    cam.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp,
                    color = if (onVideo) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Motion in the bucket around `t`. */
private fun hasMotion(list: List<MotionActivity>?, t: Double): Boolean {
    if (list.isNullOrEmpty()) return false
    var lo = 0
    var hi = list.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (list[mid].startTime <= t) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    if (found < 0 || t - list[found].startTime >= STRIP_BUCKET) return false
    return (list[found].motion ?: 0.0) > 0
}
