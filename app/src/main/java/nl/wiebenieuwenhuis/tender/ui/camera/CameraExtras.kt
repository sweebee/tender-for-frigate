package nl.wiebenieuwenhuis.tender.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.get
import nl.wiebenieuwenhuis.tender.api.strings
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.Prefs
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.CameraDebugRoute
import nl.wiebenieuwenhuis.tender.ui.components.LabelIcon
import nl.wiebenieuwenhuis.tender.ui.components.MonoDigits
import nl.wiebenieuwenhuis.tender.ui.components.RowDivider
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.SwitchRow
import java.util.Locale

/**
 * Pan, tilt and zoom pads for a camera. Shows only what the camera supports (from
 * /api/<camera>/ptz/info) plus its presets. Commands go over Frigate's websocket while pressed.
 */
@Composable
fun PtzControls(conn: ServerConnection, client: FrigateClient, camera: String) {
    var features by remember { mutableStateOf<List<String>?>(null) }
    var presets by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(camera) {
        runCatching { client.json("api/$camera/ptz/info") }.getOrNull()?.let {
            features = it["features"].strings(); presets = it["presets"].strings() ?: emptyList()
        }
    }
    // Unknown capabilities: show everything.
    val canMove = features?.contains("pt") ?: true
    val canZoom = features?.contains("zoom") ?: true
    fun send(cmd: String) { conn.command("$camera/ptz", cmd) }
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(if (canMove) R.string.pan_tilt_zoom else R.string.zoom), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canMove) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pad(Icons.Filled.KeyboardArrowUp, "MOVE_UP", ::send)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pad(Icons.Filled.KeyboardArrowLeft, "MOVE_LEFT", ::send)
                    Spacer(Modifier.size(56.dp))
                    Pad(Icons.Filled.KeyboardArrowRight, "MOVE_RIGHT", ::send)
                }
                Pad(Icons.Filled.KeyboardArrowDown, "MOVE_DOWN", ::send)
            }
            if (canZoom) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pad(Icons.Filled.ZoomIn, "ZOOM_IN", ::send)
                Pad(Icons.Filled.ZoomOut, "ZOOM_OUT", ::send)
            }
        }
        if (presets.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            items(presets) { p ->
                AssistChip(onClick = { send("preset_$p") }, label = { Text(p.replaceFirstChar { it.uppercase() }) },
                    leadingIcon = { Icon(Icons.Outlined.CenterFocusWeak, null, Modifier.size(16.dp)) })
            }
        }
    }
}

/** Sends the command while held, STOP when let go. */
@Composable
private fun Pad(icon: androidx.compose.ui.graphics.vector.ImageVector, command: String, send: (String) -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(command) {
                awaitEachGesture {
                    awaitFirstDown()
                    send(command)
                    waitForUpOrCancellation()
                    send("STOP")
                }
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, command.replace('_', ' ').lowercase()) }
}

/**
 * Frigate's Debug view of one camera: the detect stream as Frigate sees it, with what it draws
 * on it switched on or off, and the objects it tracks right now.
 */
@Composable
fun CameraDebugScreen(route: CameraDebugRoute) {
    val conn = AppModel.connection(route.serverId)
    val camera = conn?.camera(route.camera)
    var tab by remember { mutableIntStateOf(0) }
    val opts = listOf(
        Triple("bbox", R.string.debug_bbox, R.string.debug_bbox_info) to true,
        Triple("timestamp", R.string.debug_timestamp, R.string.debug_timestamp_info) to false,
        Triple("zones", R.string.debug_zones, R.string.debug_zones_info) to false,
        Triple("mask", R.string.debug_mask, R.string.debug_mask_info) to false,
        Triple("motion", R.string.debug_motion, R.string.debug_motion_info) to true,
        Triple("regions", R.string.debug_regions, R.string.debug_regions_info) to false,
        Triple("paths", R.string.debug_paths, R.string.debug_paths_info) to false,
    )
    val values = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
        opts.forEach { (o, d) -> put(o.first, Prefs.bool("debug.${o.first}", d)) }
    } }
    val query = values.entries.associate { it.key to (if (it.value) "1" else "0") } + mapOf("height" to "720")
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    val client = conn?.client
    LaunchedEffect(query.toString(), client) {
        client ?: return@LaunchedEffect
        // A fresh picture as soon as the last one is in, at most five a second, as Frigate's debug image.
        while (isActive) {
            val started = System.currentTimeMillis()
            val b = runCatching {
                val bytes = client.data("api/${route.camera}/latest.jpg", query, timeoutSeconds = 10)
                withContext(Dispatchers.Default) { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            }.getOrNull()
            if (b != null) { image = b; failed = false } else { failed = image == null; delay(1000); continue }
            val wait = 200 - (System.currentTimeMillis() - started)
            if (wait > 0) delay(wait)
        }
    }
    val wide = nl.wiebenieuwenhuis.tender.ui.components.isWideLayout()
    ScreenScaffold(stringResource(R.string.debug), subtitle = camera?.displayName ?: Format.prettyName(route.camera)) { pad ->
        @Composable fun Picture(m: Modifier) {
            Box(m.aspectRatio(camera?.aspectRatio ?: (16f / 9f)).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                when {
                    image != null -> Image(image!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    failed -> Text(stringResource(R.string.no_picture), color = Color.White)
                    else -> CircularProgressIndicator()
                }
            }
        }
        @Composable fun Options() {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryTabRow(tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.debugging)) })
                    Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.object_list)) })
                }
                if (tab == 0) SectionCard {
                    opts.forEachIndexed { i, (o, _) ->
                        if (i > 0) RowDivider()
                        SwitchRow(stringResource(o.second), values[o.first] == true, { values[o.first] = it; Prefs.setBool("debug.${o.first}", it) },
                            subtitle = stringResource(o.third))
                    }
                } else {
                    val objects = (conn?.activity?.get(route.camera)?.objects ?: emptyList()).sortedWith(compareBy({ it.stationary }, { it.label }))
                    if (objects.isEmpty()) SectionCard { Text(stringResource(R.string.no_objects), Modifier.fillMaxWidth().padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                    for (o in objects) SectionCard(Modifier.alpha(if (o.stationary) 0.7f else 1f)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            LabelIcon(o.label)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(Format.labelName(o.label), fontWeight = FontWeight.SemiBold)
                                o.subLabel?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                if (o.stationary) Text(stringResource(R.string.stationary), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Stat(stringResource(R.string.score), o.score?.let { String.format(Locale.getDefault(), "%.1f%%", it * 100) } ?: "–")
                            Stat(stringResource(R.string.ratio), o.ratio?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "–")
                            Stat(stringResource(R.string.area), o.area?.toInt()?.toString() ?: "–")
                        }
                    }
                }
            }
        }
        if (wide) Row(Modifier.padding(pad).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Picture(Modifier.weight(1f))
            Column(Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState())) { Options() }
        } else Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Picture(Modifier.fillMaxWidth())
            Options()
        }
    }
}

@Composable
private fun Stat(title: String, value: String) {
    Column(Modifier.widthIn(min = 48.dp), horizontalAlignment = Alignment.End) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoDigits.merge(MaterialTheme.typography.labelLarge))
    }
}
