package nl.wiebenieuwenhuis.tender.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.CameraConfig
import nl.wiebenieuwenhuis.tender.api.CameraStats
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateException
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.bool
import nl.wiebenieuwenhuis.tender.api.dbl
import nl.wiebenieuwenhuis.tender.api.get
import nl.wiebenieuwenhuis.tender.api.logLines
import nl.wiebenieuwenhuis.tender.api.obj
import nl.wiebenieuwenhuis.tender.api.str
import nl.wiebenieuwenhuis.tender.api.strings
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.ZoneEditorRoute
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.MonoDigits
import nl.wiebenieuwenhuis.tender.ui.components.RemoteImage
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.components.StatusDot
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import nl.wiebenieuwenhuis.tender.api.CameraStorage
import nl.wiebenieuwenhuis.tender.api.StorageStats

// MARK: - Zones and masks

private enum class ShapeKind(val raw: String) { Zone("zone"), MotionMask("motion_mask"), ObjectMask("object_mask") }

/** A zone or mask of a camera; `points` are 0…1 across the picture. */
private data class MaskShape(
    val kind: ShapeKind, val name: String, val title: String, val points: List<Offset>, val enabled: Boolean,
    /** Object masks for one object type only (objects.filters.<label>.mask). */
    val label: String? = null,
) {
    val id: String get() = kind.raw + "/" + (label?.let { "$it/" } ?: "") + name
}

/**
 * A camera's zones and masks on its picture, each to switch on or off (as Frigate's Masks / Zones
 * page does). Zones open in the zone editor.
 */
@Composable
fun ZonesMasksScreen(route: ZonesMasksRoute) {
    val nav = LocalNav.current
    val camera = route.camera
    val conn = AppModel.connection(route.serverId)
    var shapes by remember { mutableStateOf<List<MaskShape>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    /** The config names its masks (motion.mask absent or a map); a list of coordinates has no names to add to. */
    var namedMasks by remember { mutableStateOf(true) }
    /** Shapes switched on or off and not yet confirmed by Frigate. */
    val switching = remember { mutableStateSetOf<String>() }
    val isAdmin = conn?.isAdmin ?: true
    val scope = rememberCoroutineScope()

    suspend fun load() {
        val cam = runCatching { conn?.client?.rawConfig() }.getOrNull()?.get("cameras")?.get(camera)
        if (cam == null) { loaded = true; return }
        val w = cam["detect"]["width"].dbl() ?: 1.0
        val h = cam["detect"]["height"].dbl() ?: 1.0
        val out = mutableListOf<MaskShape>()
        val motionMask = cam["motion"]["mask"]
        namedMasks = motionMask == null || motionMask is JsonObject
        fun add(node: JsonElement?, kind: ShapeKind, label: String? = null) {
            for ((name, item) in (node.obj() ?: return).entries.sortedBy { it.key }) {
                if (item !is JsonObject) continue
                val title = item["friendly_name"].str()?.takeIf { it.isNotEmpty() } ?: Format.prettyName(name)
                out.add(MaskShape(kind, name, title, points(item["coordinates"], w, h), item["enabled"].bool() ?: true, label))
            }
        }
        add(cam["zones"], ShapeKind.Zone)
        add(cam["motion"]["mask"], ShapeKind.MotionMask)
        add(cam["objects"]["mask"], ShapeKind.ObjectMask)
        // Masks for one object type, as Frigate's page makes them ("Object Mask 1 (person)").
        cam["objects"]["filters"].obj()?.entries?.sortedBy { it.key }?.forEach { (label, filter) -> add(filter["mask"], ShapeKind.ObjectMask, label) }
        shapes = out
        loaded = true
    }
    LaunchedEffect(Unit) { load() }

    suspend fun serverState(shape: MaskShape): Boolean? {
        val cam = runCatching { conn?.client?.rawConfig() }.getOrNull()?.get("cameras")?.get(camera) ?: return null
        val node = when (shape.kind) {
            ShapeKind.Zone -> cam["zones"]
            ShapeKind.MotionMask -> cam["motion"]["mask"]
            ShapeKind.ObjectMask -> if (shape.label != null) cam["objects"]["filters"][shape.label]["mask"] else cam["objects"]["mask"]
        }
        return node[shape.name]["enabled"].bool()
    }

    fun set(shape: MaskShape, on: Boolean) {
        val c = conn ?: return
        val client = c.client ?: return
        if (shape.label != null) {
            // A mask for one object type has no switch on Frigate's websocket: it is switched in the config.
            val data = mapOf("cameras" to mapOf(camera to mapOf("objects" to mapOf("filters" to mapOf(shape.label to
                mapOf("mask" to mapOf(shape.name to mapOf("enabled" to on))))))))
            scope.launch { runCatching { client.setConfig(data, false, "config/cameras/$camera/objects") } }
        } else if (!c.command("$camera/${shape.kind.raw}/${shape.name}/set", if (on) "ON" else "OFF")) {
            return
        }
        shapes = shapes.map { if (it.id == shape.id) it.copy(enabled = on) else it }
        switching.add(shape.id)
        // Wait until Frigate's config shows it, then show what Frigate has.
        scope.launch {
            for (i in 0 until 10) {
                delay(500)
                if (serverState(shape) == on) break
            }
            load()
            switching.remove(shape.id)
        }
    }

    val cam = conn?.camera(camera)
    ScreenScaffold(
        stringResource(R.string.settings_zones_masks), subtitle = cam?.displayName,
        actions = {
            if (isAdmin && loaded) Box {
                var addMenu by remember { mutableStateOf(false) }
                BusyIconButton(Icons.Outlined.Add, stringResource(R.string.settings_new_zone), false) { addMenu = true }
                DropdownMenu(addMenu, { addMenu = false }) {
                    (listOf("zone" to R.string.settings_new_zone_item) +
                        (if (namedMasks) listOf("motion_mask" to R.string.settings_new_motion_mask, "object_mask" to R.string.settings_new_object_mask) else emptyList())
                    ).forEach { (k, t) ->
                        DropdownMenuItem(text = { Text(stringResource(t)) }, onClick = {
                            addMenu = false; nav.navigate(ZoneEditorRoute(route.serverId, camera, kind = k))
                        })
                    }
                }
            }
        },
    ) { inner ->
        SettingsPage(inner) {
            val client = conn?.client
            if (client != null) {
                val zoneColor = MaterialTheme.colorScheme.primary
                val objectColor = MaterialTheme.colorScheme.error
                Box(Modifier.fillMaxWidth().aspectRatio(cam?.aspectRatio ?: (16f / 9f)).clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)) {
                    RemoteImage(client.latestImageUrl(camera, 540), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    Canvas(Modifier.fillMaxSize()) {
                        for (s in shapes) {
                            if (!s.enabled || s.points.size <= 2) continue
                            val color = when (s.kind) { ShapeKind.Zone -> zoneColor; ShapeKind.MotionMask -> Color.Gray; ShapeKind.ObjectMask -> objectColor }
                            val path = Path().apply {
                                s.points.forEachIndexed { i, p ->
                                    val x = p.x * size.width; val y = p.y * size.height
                                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                                }
                                close()
                            }
                            drawPath(path, color.copy(alpha = if (s.kind == ShapeKind.Zone) 0.25f else 0.45f))
                            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                        }
                    }
                }
            }
            if (!loaded) {
                LoadingBox()
            } else if (shapes.isEmpty()) {
                Text(stringResource(if (isAdmin) R.string.settings_no_zones_admin else R.string.settings_no_zones),
                    Modifier.fillMaxWidth().padding(vertical = 20.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                for ((kind, title) in listOf(ShapeKind.Zone to R.string.settings_zones, ShapeKind.MotionMask to R.string.settings_motion_masks,
                    ShapeKind.ObjectMask to R.string.settings_object_masks)) {
                    val list = shapes.filter { it.kind == kind }
                    if (list.isEmpty()) continue
                    SectionLabel(stringResource(title), Modifier.padding(top = 10.dp))
                    SectionCard {
                        list.forEachIndexed { i, s ->
                            if (i > 0) RowDivider()
                            val color = when (kind) { ShapeKind.Zone -> MaterialTheme.colorScheme.primary; ShapeKind.MotionMask -> Color.Gray
                                ShapeKind.ObjectMask -> MaterialTheme.colorScheme.error }
                            val editable = isAdmin
                            Row(Modifier.fillMaxWidth().padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    Modifier.weight(1f).clickable(enabled = editable) { nav.navigate(ZoneEditorRoute(route.serverId, camera, s.name,
                                        kind = when (kind) { ShapeKind.Zone -> "zone"; ShapeKind.MotionMask -> "motion_mask"; ShapeKind.ObjectMask -> "object_mask" },
                                        label = s.label)) }
                                        .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(Modifier.size(14.dp).background(color.copy(alpha = 0.7f), RoundedCornerShape(3.dp)))
                                    Column(Modifier.weight(1f, fill = false)) {
                                        Text(s.title, style = MaterialTheme.typography.bodyLarge)
                                        s.label?.let {
                                            Text(stringResource(R.string.settings_only_for, Format.labelName(it)), style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (editable) Icon(Icons.Outlined.Edit, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (s.id in switching) SmallSpinner()
                                Switch(s.enabled, { set(s, it) }, enabled = isAdmin && s.id !in switching,
                                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.status.online))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Frigate's "x1,y1,x2,y2,…": fractions of the picture, or pixels of the detect size in older configs. */
private fun points(raw: JsonElement?, width: Double, height: Double): List<Offset> {
    val text = raw.str() ?: raw.strings()?.joinToString(",") ?: ""
    val n = text.split(",").mapNotNull { it.trim().toDoubleOrNull() }
    val pixels = n.any { it > 1.0001 }
    return (0 until n.size - 1 step 2).map { i ->
        if (pixels) Offset((n[i] / width).toFloat(), (n[i + 1] / height).toFloat()) else Offset(n[i].toFloat(), n[i + 1].toFloat())
    }
}

// MARK: - MQTT

/** The MQTT connection, to read (it is set in the config file). */
@Composable
fun MqttScreen(route: MqttRoute) {
    val conn = AppModel.connection(route.serverId)
    var mqtt by remember { mutableStateOf<JsonElement?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        mqtt = runCatching { conn?.client?.rawConfig() }.getOrNull()?.get("mqtt")
        loaded = true
    }
    ScreenScaffold("MQTT") { inner ->
        SettingsPage(inner) {
            if (!loaded) {
                LoadingBox()
            } else {
                val m = mqtt
                val rows = listOf(
                    stringResource(R.string.settings_enabled) to stringResource(if (m["enabled"].bool() == true) R.string.settings_yes else R.string.settings_no),
                    stringResource(R.string.server) to (m["host"].str()?.takeIf { it.isNotEmpty() }
                        ?.let { host -> listOfNotNull(host, m["port"].dbl()?.toLong()?.toString()).joinToString(":") }
                        ?: stringResource(R.string.settings_not_set)),
                    stringResource(R.string.settings_topic_prefix) to (m["topic_prefix"].str() ?: ""),
                    stringResource(R.string.settings_client_id) to (m["client_id"].str() ?: ""),
                    stringResource(R.string.settings_stats_interval) to (m["stats_interval"].dbl()?.let { "${it.toLong()} s" } ?: ""),
                ).filter { it.second.isNotEmpty() }
                SectionCard {
                    rows.forEachIndexed { i, (title, value) ->
                        if (i > 0) RowDivider()
                        SelectionContainer { InfoRow(title, value) }
                    }
                }
                Footnote(stringResource(R.string.settings_mqtt_footer))
            }
        }
    }
}

// MARK: - Camera order

/** The order of the server's cameras, by dragging, saved as each camera's `ui.order`. */
@Composable
fun CameraOrderScreen(route: CameraOrderRoute) {
    val nav = LocalNav.current
    val conn = AppModel.connection(route.serverId)
    val cameras = remember { mutableStateListOf<CameraConfig>().apply { addAll(orderedCameras(route.serverId)) } }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeight = 56.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    val scope = rememberCoroutineScope()

    fun save() {
        val c = conn ?: return
        val client = c.client ?: return
        saving = true
        val data = cameras.mapIndexed { i, cam -> cam.name to mapOf("ui" to mapOf("order" to i + 1)) }.toMap()
        scope.launch {
            try {
                client.setConfig(mapOf("cameras" to data), false, null)
                c.refresh(reloadConfig = true)
                nav.popBackStack()
            } catch (e: Exception) { errorMessage = e.message } finally { saving = false }
        }
    }

    ScreenScaffold(
        stringResource(R.string.settings_camera_order),
        actions = {
            TextButton(onClick = { save() }, enabled = !saving) {
                Box(contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.save), maxLines = 1, color = if (saving) Color.Transparent else MaterialTheme.colorScheme.primary)
                    if (saving) SmallSpinner()
                }
            }
        },
    ) { inner ->
        SettingsPage(inner) {
            SectionCard {
                Column {
                    cameras.forEach { cam ->
                        key(cam.name) {
                            val isDragging = dragging == cam.name
                            Box(
                                Modifier.fillMaxWidth().height(rowHeight).zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                                    .background(if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent),
                            ) {
                                Row(Modifier.fillMaxSize().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(cam.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                    Box(
                                        Modifier.size(48.dp).pointerInput(cam.name) {
                                            detectDragGestures(
                                                onDragStart = { dragging = cam.name; dragOffset = 0f },
                                                onDragEnd = { dragging = null; dragOffset = 0f },
                                                onDragCancel = { dragging = null; dragOffset = 0f },
                                            ) { change, amount ->
                                                change.consume()
                                                dragOffset += amount.y
                                                val i = cameras.indexOfFirst { it.name == cam.name }
                                                if (dragOffset > rowPx / 2 && i in 0 until cameras.lastIndex) {
                                                    cameras.add(i + 1, cameras.removeAt(i)); dragOffset -= rowPx
                                                } else if (dragOffset < -rowPx / 2 && i > 0) {
                                                    cameras.add(i - 1, cameras.removeAt(i)); dragOffset += rowPx
                                                }
                                            }
                                        },
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.Outlined.DragHandle, stringResource(R.string.settings_drag_to_reorder), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
            }
            Footnote(stringResource(R.string.settings_camera_order_footer))
        }
    }
    errorMessage?.let { MessageDialog(stringResource(R.string.not_saved), it) { errorMessage = null } }
}

// MARK: - System status

/** How the server is doing: each camera's frames per second, the detectors, the processors, the disks, and Frigate's logs. */
@Composable
fun SystemStatusScreen(route: SystemStatusRoute) {
    val conn = AppModel.connection(route.serverId)
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var service by remember { mutableStateOf("frigate") }
    var loadingLogs by remember { mutableStateOf(false) }
    var logError by remember { mutableStateOf<String?>(null) }
    var cameraStorage by remember { mutableStateOf<List<CameraStorage>?>(null) }
    // Frigate could not add up the recordings (or this user may not ask): the section stays away.
    var cameraStorageFailed by remember { mutableStateOf(false) }

    suspend fun loadCameraStorage() {
        val client = conn?.client ?: return
        if (!conn.isAdmin) return
        try {
            cameraStorage = client.cameraStorage()
            cameraStorageFailed = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cameraStorage == null) cameraStorageFailed = true
        }
    }

    suspend fun loadLogs() {
        val client = conn?.client ?: return
        loadingLogs = true
        try {
            // Newest first: the page opens on what just happened.
            logs = client.logLines(service).takeLast(200).reversed().map { SettingsFormat.hidingPasswords(it) }
            logError = null
        } catch (e: FrigateException.Unauthorized) {
            logError = L(R.string.settings_logs_admin_only)
        } catch (e: Exception) {
            logError = e.message
        } finally {
            loadingLogs = false
        }
    }
    LaunchedEffect(service) { logs = emptyList(); loadLogs() }
    // Frigate adds up every recording for this, so once on opening rather than with the stats.
    LaunchedEffect(conn) { loadCameraStorage() }
    // Stats every five seconds while open, as Frigate's system page does.
    LaunchedEffect(conn) {
        while (isActive) {
            conn?.refresh()
            delay(5000)
        }
    }

    ScreenScaffold(stringResource(R.string.settings_system_status), subtitle = conn?.server?.name) { inner ->
        SettingsPage(inner, onRefresh = { conn?.refresh(); loadLogs(); loadCameraStorage() }) {
            val stats = conn?.stats
            // The version and uptime are on the server page's status card; this page starts with the cameras.
            if (conn != null && stats != null) {
                SectionLabel(stringResource(R.string.settings_cameras))
                SectionCard {
                    val names = conn.cameras.map { it.name }.filter { stats.cameras[it] != null }
                    names.forEachIndexed { i, name ->
                        if (i > 0) RowDivider()
                        CameraStatsRow(conn, name, stats.cameras.getValue(name))
                    }
                }
                Footnote(stringResource(R.string.settings_camera_stats_footer))

                SectionLabel(stringResource(R.string.settings_processing), Modifier.padding(top = 10.dp))
                SectionCard {
                    val rows = buildList {
                        stats.detectors.entries.sortedBy { it.key }.forEach { (name, speed) ->
                            add(L(R.string.settings_detector_named, name) to (speed?.let { String.format(Locale.getDefault(), "%.1f ms", it) } ?: ""))
                        }
                        stats.cpuPercent?.let { add("CPU" to "${it.roundToInt()} %") }
                        stats.gpus.entries.sortedBy { it.key }.forEach { (name, g) -> add(name to (g.first ?: "")) }
                    }
                    rows.forEachIndexed { i, (title, value) ->
                        if (i > 0) RowDivider()
                        InfoRow(title, value, mono = true)
                    }
                }

                SectionLabel(stringResource(R.string.settings_storage), Modifier.padding(top = 10.dp))
                SectionCard {
                    stats.storage.entries.sortedBy { it.key }.forEachIndexed { i, (path, s) ->
                        if (i > 0) RowDivider()
                        val used = s.used; val total = s.total
                        InfoRow(path.replace("/media/frigate/", ""),
                            if (used != null && total != null) "${SettingsFormat.size(used)} / ${SettingsFormat.size(total)}" else "", mono = true)
                    }
                }

                val cams = cameraStorage
                val disk = stats.recordingStorage
                if (conn.isAdmin && !cameraStorageFailed && cams?.isEmpty() != true && disk != null) {
                    SectionLabel(stringResource(R.string.settings_camera_storage), Modifier.padding(top = 10.dp))
                    if (cams != null) CameraStorageCard(cams, disk) { conn.camera(it)?.displayName ?: Format.prettyName(it) }
                    // Frigate adds up every recording for this, which can take a few seconds.
                    else SectionCard {
                        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionLabel(stringResource(R.string.settings_logs))
                    Text(stringResource(R.string.settings_newest_first), Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val services = listOf("frigate" to "Frigate", "go2rtc" to "go2rtc", "nginx" to "nginx")
                SingleChoiceSegmentedButtonRow {
                    services.forEachIndexed { i, (key, title) ->
                        SegmentedButton(
                            selected = service == key, onClick = { service = key },
                            shape = SegmentedButtonDefaults.itemShape(i, services.size),
                            label = { Text(title, maxLines = 1, softWrap = false) },
                            icon = {},
                        )
                    }
                }
            }
            SectionCard {
                Box(Modifier.fillMaxWidth().padding(12.dp)) {
                    when {
                        loadingLogs && logs.isEmpty() -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { SmallSpinner() }
                        logError != null -> Text(logError ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                val warn = Palette.status.warning
                                for (line in logs) {
                                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 13.sp,
                                        color = when {
                                            line.contains("ERROR") || line.contains("error") -> MaterialTheme.colorScheme.error
                                            line.contains("WARN") -> warn
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        })
                                }
                            }
                        }
                    }
                }
            }
            Footnote(stringResource(R.string.settings_logs_footer))
        }
    }
}

/** Frigate's chart colours, in the same order, so a camera has the same colour in both. */
private val storagePalette = listOf(0xFF008FFB, 0xFF00E396, 0xFFFEB019, 0xFFFF4560, 0xFF775DD0,
    0xFF3F51B5, 0xFF03A9F4, 0xFF4CAF50, 0xFFF9CE1D, 0xFFFF9800).map { Color(it) }

/**
 * What each camera's recordings take of the recordings disk, as on Frigate's Storage page: one bar
 * with a colour per camera, then per camera its size, share of the disk and recording rate.
 */
@Composable
private fun CameraStorageCard(cameras: List<CameraStorage>, disk: StorageStats, name: (String) -> String) {
    data class Row(val title: String, val usage: Double, val bandwidth: Double?, val color: Color)
    val total = (disk.total ?: 0.0).coerceAtLeast(1.0)
    val cameraUsage = cameras.sumOf { it.usage }
    val used = disk.used ?: cameraUsage
    val rows = cameras.mapIndexed { i, c -> Row(name(c.camera), c.usage, c.bandwidth, storagePalette[i % storagePalette.size]) } +
        Row(stringResource(R.string.settings_storage_other), (used - cameraUsage).coerceAtLeast(0.0), null, Color(0xFF606060)) +
        Row(stringResource(R.string.settings_storage_unused), (total - used).coerceAtLeast(0.0), null, Color(0xFF404040))
    SectionCard {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text(SettingsFormat.size(cameraUsage), style = MaterialTheme.typography.labelLarge.merge(MonoDigits))
                Text(" / ${SettingsFormat.size(disk.total ?: 0.0)}", style = MaterialTheme.typography.labelLarge.merge(MonoDigits),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Color(0xFF404040))) {
                rows.forEach { row ->
                    val share = (row.usage / total).coerceIn(0.0, 1.0).toFloat()
                    if (share > 0f) Box(Modifier.fillMaxHeight().weight(share).background(row.color))
                }
                // The rest of the bar when the rows add up to less than the disk.
                val rest = 1f - rows.sumOf { (it.usage / total).coerceIn(0.0, 1.0) }.toFloat()
                if (rest > 0.0001f) Spacer(Modifier.weight(rest))
            }
        }
        rows.forEach { row ->
            RowDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(row.color))
                Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Column(horizontalAlignment = Alignment.End) {
                    Text(SettingsFormat.size(row.usage), style = MaterialTheme.typography.bodyMedium.merge(MonoDigits))
                    val percent = String.format(Locale.getDefault(), "%.2f%%", row.usage / total * 100)
                    Text(row.bandwidth?.let { stringResource(R.string.settings_storage_share_rate, percent, SettingsFormat.size(it)) } ?: percent,
                        style = MaterialTheme.typography.labelSmall.merge(MonoDigits), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CameraStatsRow(conn: ServerConnection, name: String, s: CameraStats) {
    val title = conn.camera(name)?.displayName ?: Format.prettyName(name)
    val down = (s.cameraFps ?: 0.0) == 0.0
    val skipping = (s.skippedFps ?: 0.0) > 0.5
    fun fps(v: Double?) = String.format(Locale.getDefault(), "%.1f", v ?: 0.0)
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(if (down) Palette.status.offline else if (skipping) Palette.status.warning else Palette.status.online)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.settings_fps_value, fps(s.cameraFps)), style = MaterialTheme.typography.bodyMedium.merge(MonoDigits))
            Text(stringResource(R.string.settings_detection_skipped, fps(s.detectionFps), fps(s.skippedFps)),
                style = MaterialTheme.typography.labelSmall.merge(MonoDigits),
                color = if (skipping) Palette.status.warning else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

