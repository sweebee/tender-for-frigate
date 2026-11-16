package nl.wiebenieuwenhuis.tender.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.CameraConfig
import nl.wiebenieuwenhuis.tender.api.MotionActivity
import nl.wiebenieuwenhuis.tender.api.nowSeconds
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.PlaybackRoute
import nl.wiebenieuwenhuis.tender.ui.components.ServerBadge
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

private const val BUCKET = 30.0

private class MotionCam(val conn: ServerConnection, val camera: CameraConfig) {
    val id: String = "${conn.id}/${camera.name}"
}

/**
 * Review's Motion view, like Frigate's: every camera's preview at one shared moment, with the
 * motion of all cameras on a timeline beside them. Dragging the timeline or playing (fast by
 * default) moves every preview together; cameras with motion at that moment get a border. A tap
 * on a camera opens its recording at that moment. Shown inside the Review tab, without its own bar.
 *
 * `cameraFilter`: only these cameras (empty: all). `start`/`end`: the period, epoch seconds;
 * the last 24 hours by default.
 */
@Composable
fun MotionReviewPane(
    modifier: Modifier = Modifier,
    cameraFilter: Set<String> = emptySet(),
    start: Double? = null,
    end: Double? = null,
) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    // Tablet: larger previews, buttons and timeline. Not on a phone held sideways, nor in a window too low for it.
    val wide = nl.wiebenieuwenhuis.tender.ui.components.isWideLayout() &&
        currentWindowAdaptiveInfo().windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    val connections = AppModel.scopedConnections.filter { it.isOnline }
    val cams = connections.flatMap { c ->
        c.cameras.filter { cameraFilter.isEmpty() || it.name in cameraFilter }.map { MotionCam(c, it) }
    }
    val periodStart = start ?: (nowSeconds() - 86400)

    /** The shared moment every preview shows. */
    var wallTime by remember { mutableDoubleStateOf(nowSeconds() - 30) }
    var rangeStart by remember { mutableDoubleStateOf(nowSeconds() - 86400) }
    var rangeEnd by remember { mutableDoubleStateOf(nowSeconds()) }
    val previews = remember { mutableStateMapOf<String, PreviewScrubber>() }
    /** Motion buckets per camera ("server/camera"), oldest first. */
    val activity = remember { mutableStateMapOf<String, List<MotionActivity>>() }
    /** All cameras' motion together, for the timeline. */
    var combined by remember { mutableStateOf<List<MotionActivity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var rate by remember { mutableDoubleStateOf(8.0) }

    DisposableEffect(Unit) { onDispose { previews.values.forEach { it.release() } } }

    fun move(t: Double) {
        wallTime = min(max(t, rangeStart), rangeEnd)
        for (p in previews.values) p.show(wallTime)
    }

    /** Motion in the bucket around `t` for one camera. */
    fun hasMotion(id: String, t: Double): Boolean {
        val list = activity[id] ?: return false
        if (list.isEmpty()) return false
        var lo = 0
        var hi = list.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (list[mid].startTime <= t) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (found < 0 || t - list[found].startTime >= BUCKET) return false
        return (list[found].motion ?: 0.0) > 0
    }

    val loadKey = cams.map { it.id } + "$periodStart-$end"
    LaunchedEffect(loadKey) {
        loading = true
        val now = min(end ?: nowSeconds(), nowSeconds())
        // A new period starts at its end (most recent).
        if (rangeStart != periodStart || wallTime > now || wallTime < periodStart) wallTime = now - 30
        rangeEnd = now
        rangeStart = periodStart
        for (cam in cams) if (previews[cam.id] == null) previews[cam.id] = PreviewScrubber(scope)
        coroutineScope {
            val jobs = cams.mapNotNull { cam ->
                val client = cam.conn.client ?: return@mapNotNull null
                val p = previews[cam.id] ?: return@mapNotNull null
                async { p.prepare(client, cam.camera.name, rangeStart - 3600, now) }
            } + connections.mapNotNull { conn ->
                // Motion per server, every camera on its own (Frigate merges cameras asked for together).
                val client = conn.client ?: return@mapNotNull null
                val names = conn.cameras.filter { cameraFilter.isEmpty() || it.name in cameraFilter }.map { it.name }
                async {
                    val byCamera = client.motionActivityByCamera(names, rangeStart, now, BUCKET.toInt())
                    for ((cam, items) in byCamera) activity["${conn.id}/$cam"] = items.sortedBy { it.startTime }
                }
            }
            jobs.awaitAll()
        }
        combined = combine(activity)
        loading = false
        move(wallTime)
    }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val tick = 0.25
        while (playing) {
            delay((tick * 1000).toLong())
            if (!playing) break
            if (wallTime + tick * rate >= rangeEnd) { move(rangeEnd); playing = false; break }
            move(wallTime + tick * rate)
        }
    }

    Row(modifier.fillMaxSize()) {
        // The controls under the grid, so they never cover a camera's name.
        Column(Modifier.weight(1f).fillMaxHeight()) {
            LazyVerticalGrid(
                GridCells.Adaptive(if (wide) 260.dp else 150.dp), Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(cams, key = { it.id }) { cam ->
                    val preview = previews[cam.id]
                    val active by remember(cam.id) { derivedStateOf { hasMotion(cam.id, wallTime) } }
                    val shape = RoundedCornerShape(12.dp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(cam.camera.aspectRatio)
                            .clip(shape)
                            .background(Color.Black)
                            .border(if (active) 3.dp else 1.dp, if (active) Palette.status.motion else MaterialTheme.colorScheme.outlineVariant, shape)
                            .clickable {
                                val t = wallTime
                                playing = false
                                val day = Calendar.getInstance().apply {
                                    timeInMillis = (t * 1000).toLong()
                                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                                }.timeInMillis
                                nav.navigate(PlaybackRoute(cam.conn.id, cam.camera.name, day, startTime = t))
                            }
                            .semantics { contentDescription = cam.camera.displayName },
                    ) {
                        if (preview != null) PreviewSurface(preview, Modifier.fillMaxSize())
                        if (preview?.showing != true && !loading) {
                            Text(stringResource(R.string.playback_no_preview), Modifier.align(Alignment.Center), color = Color.White.copy(alpha = 0.6f),
                                fontSize = 13.sp)
                        }
                        Row(
                            Modifier.align(Alignment.BottomStart).padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (connections.size > 1) ServerBadge(cam.conn.server, size = 18.dp)
                            Text(cam.camera.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            // The same round buttons as the playback screens.
            val size = if (wide) 56.dp else 44.dp
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayButton(Icons.Filled.Replay10, stringResource(R.string.playback_back_10), size) { move(wallTime - 10) }
                OverlayButton(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (playing) R.string.playback_pause else R.string.playback_play), if (wide) 64.dp else 44.dp) {
                    // At the end: start ten minutes back.
                    if (!playing && wallTime >= rangeEnd - 5) move(rangeEnd - 600)
                    playing = !playing
                }
                OverlayButton(Icons.Filled.Forward10, stringResource(R.string.playback_forward_10), size) { move(wallTime + 10) }
                OverlayMenuButton("${rate.toInt()}x", stringResource(R.string.playback_speed), listOf(4.0, 8.0, 12.0, 16.0), rate,
                    { "${it.toInt()}x" }, size) { rate = it }
            }
        }
        Box(
            Modifier
                .width(if (wide) 220.dp else 136.dp)
                .fillMaxHeight()
                .padding(end = if (wide) 16.dp else 8.dp, top = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)),
        ) {
            PlaybackTimeline(
                rangeStart, rangeEnd, { wallTime }, combined, emptyList(), RecordingMap.full(rangeStart, rangeEnd),
                onScrub = { playing = false; move(it) },
                onScrubEnd = { t, _ -> move(t) },
                modifier = Modifier.fillMaxSize(),
                compact = !wide,
            )
            if (loading && combined.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

/**
 * One bucket per moment for the timeline: the strongest motion of any camera, each camera
 * measured against its own busiest moment. Otherwise one camera that always moves (trees, a road)
 * would make every other camera's motion a thin line.
 */
private fun combine(activity: Map<String, List<MotionActivity>>): List<MotionActivity> {
    val byTime = HashMap<Double, Double>()
    for (list in activity.values) {
        val peak = list.maxOfOrNull { it.motion ?: 0.0 } ?: 0.0
        if (peak <= 0) continue
        for (a in list) {
            val m = a.motion ?: 0.0
            if (m > 0) byTime[a.startTime] = max(byTime[a.startTime] ?: 0.0, m / peak)
        }
    }
    return byTime.keys.sorted().map { MotionActivity(it, byTime[it], null) }
}
