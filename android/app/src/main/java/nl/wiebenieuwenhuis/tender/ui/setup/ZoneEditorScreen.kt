package nl.wiebenieuwenhuis.tender.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.bool
import nl.wiebenieuwenhuis.tender.api.dbl
import nl.wiebenieuwenhuis.tender.api.get
import nl.wiebenieuwenhuis.tender.api.obj
import nl.wiebenieuwenhuis.tender.api.str
import nl.wiebenieuwenhuis.tender.api.strings
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.ZoneEditorRoute
import nl.wiebenieuwenhuis.tender.ui.components.ConfirmDialog
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.ProgressButton
import nl.wiebenieuwenhuis.tender.ui.components.RemoteImage
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.readableWidth
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import java.text.Normalizer
import kotlin.math.hypot
import kotlin.math.roundToInt

// Drawing a camera's zones and masks on its picture, as Frigate's Masks / Zones settings do: drag the
// corners, tap the + between two corners to add one, long-press a corner to remove it. Saved with
// PUT /api/config/set as Frigate's web interface saves them; they apply without a restart.

/** The kinds of shape, by the name Frigate's websocket uses for them. */
enum class ZoneKind(val raw: String) { Zone("zone"), MotionMask("motion_mask"), ObjectMask("object_mask") }

private data class ZonePoint(val x: Float, val y: Float)

private data class ZoneDraft(
    val kind: ZoneKind,
    /** The config key; empty for a new one until it has a name. */
    val name: String,
    val title: String,
    /** Corners, 0…1 across the picture. */
    val points: List<ZonePoint>,
    val enabled: Boolean = true,
    /** Zones: the objects that count in it (empty: all). */
    val objects: List<String> = emptyList(),
    /** Zones: frames an object must be inside before it counts as in the zone. */
    val inertia: Int = 3,
    /** Zones: seconds an object must stay before it counts as loitering (0: off). */
    val loiteringTime: Int = 0,
    /** Object masks that hold for one object type only (objects.filters.<label>.mask); null: every object. */
    val label: String? = null,
) {
    val isNew: Boolean get() = name.isEmpty()

    /** "0.123,0.456,…" with three decimals, as Frigate writes them. */
    val coordinates: String
        get() = points.joinToString(",") { p ->
            listOf(p.x, p.y).joinToString(",") { "%.3f".format(java.util.Locale.ROOT, it.coerceIn(0f, 1f)) }
        }

    companion object {
        /** A square in the middle of the picture, to drag into shape. */
        fun new(kind: ZoneKind) = ZoneDraft(kind, "", "",
            listOf(ZonePoint(0.35f, 0.35f), ZonePoint(0.65f, 0.35f), ZonePoint(0.65f, 0.65f), ZonePoint(0.35f, 0.65f)))

        /** The key Frigate uses for a name: lower case, words joined with underscores. */
        fun key(title: String): String {
            val folded = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            return folded.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString("_")
        }

        /** Frigate's "x1,y1,x2,y2,…": fractions of the picture, or pixels of the detect size in older configs. */
        fun points(raw: JsonElement?, width: Double, height: Double): List<ZonePoint> {
            val text = raw.str() ?: raw.strings()?.joinToString(",") ?: ""
            val n = text.split(",").mapNotNull { it.trim().toDoubleOrNull() }
            val pixels = n.any { it > 1.0001 }
            return (0 until n.size - 1 step 2).map { i ->
                if (pixels) ZonePoint((n[i] / width).toFloat(), (n[i + 1] / height).toFloat()) else ZonePoint(n[i].toFloat(), n[i + 1].toFloat())
            }
        }
    }
}

/** The shapes of a kind (and object type) on a camera, as `name → item`. */
private fun shapeNode(cam: JsonElement?, kind: ZoneKind, label: String?): JsonObject? = when (kind) {
    ZoneKind.Zone -> cam["zones"].obj()
    ZoneKind.MotionMask -> cam["motion"]["mask"].obj()
    ZoneKind.ObjectMask -> if (label != null) cam["objects"]["filters"][label]["mask"].obj() else cam["objects"]["mask"].obj()
}

private fun draftFrom(cam: JsonElement?, kind: ZoneKind, name: String, label: String?): ZoneDraft? {
    val item = shapeNode(cam, kind, label)?.get(name)?.obj() ?: return null
    val w = cam["detect"]["width"].dbl() ?: 1.0
    val h = cam["detect"]["height"].dbl() ?: 1.0
    return ZoneDraft(
        kind = kind, name = name,
        title = item["friendly_name"].str()?.takeIf { it.isNotEmpty() } ?: Format.prettyName(name),
        points = ZoneDraft.points(item["coordinates"], w, h),
        enabled = item["enabled"].bool() ?: true,
        objects = item["objects"].strings() ?: emptyList(),
        inertia = item["inertia"].dbl()?.roundToInt() ?: 3,
        loiteringTime = item["loitering_time"].dbl()?.roundToInt() ?: 0,
        label = label,
    )
}

/**
 * The draft the route asks for, read from the camera's config: `zone` of `kind` (for one object
 * type when `label` is set), or a new one. Frigate 0.17+ names its masks; older configs keep masks
 * as bare coordinate lists, which (as in the iOS app) are not edited here.
 */
private fun resolveDraft(route: ZoneEditorRoute, cam: JsonElement?): ZoneDraft {
    val kind = ZoneKind.entries.firstOrNull { it.raw == route.kind } ?: ZoneKind.Zone
    val label = route.label?.takeIf { kind == ZoneKind.ObjectMask && it.isNotEmpty() }
    val name = route.zone?.takeIf { it.isNotEmpty() } ?: return ZoneDraft.new(kind).copy(label = label)
    return draftFrom(cam, kind, name, label)
        // The name alone: look in every kind.
        ?: ZoneKind.entries.firstNotNullOfOrNull { draftFrom(cam, it, name, null) }
        ?: ZoneDraft.new(kind).copy(label = label)
}

@Composable
fun ZoneEditorScreen(route: ZoneEditorRoute) {
    val conn = AppModel.connection(route.serverId)
    val camera = route.camera
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<ZoneDraft?>(null) }
    var original by remember { mutableStateOf<ZoneDraft?>(null) }
    var taken by remember { mutableStateOf<Set<String>>(emptySet()) }
    val labels = remember { mutableStateListOf<String>() }
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<Int?>(null) }

    fun kindTitle(kind: ZoneKind) = L(when (kind) {
        ZoneKind.Zone -> R.string.setup_zone
        ZoneKind.MotionMask -> R.string.setup_motion_mask
        ZoneKind.ObjectMask -> R.string.setup_object_mask
    })

    LaunchedEffect(route) {
        val client = conn?.client ?: return@LaunchedEffect
        val cfg = runCatching { client.rawConfig() }.getOrNull()
        val cam = cfg["cameras"][camera]
        var d = resolveDraft(route, cam)
        taken = shapeNode(cam, d.kind, d.label)?.keys ?: emptySet()
        if (d.isNew && d.title.isEmpty()) {
            // A ready name ("Zone 2"), which can be changed.
            val t = kindTitle(d.kind)
            d = d.copy(title = (1..99).map { "$t $it" }.firstOrNull { ZoneDraft.key(it) !in taken } ?: t)
        }
        original = d
        draft = d
        val tracked = cam["objects"]["track"].strings() ?: emptyList()
        labels.clear()
        labels.addAll((tracked + d.objects).distinct().sortedBy { Format.labelName(it) })
    }

    /** Where the shape sits in a camera's config, and the topic that applies a change at once. */
    fun location(d: ZoneDraft, name: String, value: Any?): Pair<Map<String, Any?>, String> = when (d.kind) {
        ZoneKind.Zone -> mapOf("zones" to mapOf(name to value)) to "config/cameras/$camera/zones"
        ZoneKind.MotionMask -> mapOf("motion" to mapOf("mask" to mapOf(name to value))) to "config/cameras/$camera/motion"
        ZoneKind.ObjectMask -> (if (d.label != null) mapOf("objects" to mapOf("filters" to mapOf(d.label to mapOf("mask" to mapOf(name to value)))))
            else mapOf("objects" to mapOf("mask" to mapOf(name to value)))) to "config/cameras/$camera/objects"
    }

    fun save() {
        val d = draft ?: return
        val c = conn ?: return
        val client = c.client ?: return
        val title = d.title.trim()
        val name = if (d.isNew) ZoneDraft.key(title) else d.name
        if (name.isEmpty()) { errorMessage = L(R.string.setup_zone_name_missing); return }
        if (d.isNew && name in taken) { errorMessage = L(R.string.setup_zone_name_taken); return }
        val value = linkedMapOf<String, Any?>("coordinates" to d.coordinates, "enabled" to d.enabled)
        if (title != name) value["friendly_name"] = title
        if (d.kind == ZoneKind.Zone) {
            value["inertia"] = d.inertia
            value["loitering_time"] = d.loiteringTime
            if (d.objects.isNotEmpty()) value["objects"] = d.objects
            else if (original?.objects?.isNotEmpty() == true) value["objects"] = null
        }
        val (data, topic) = location(d, name, value)
        saving = true
        scope.launch {
            try {
                client.setConfig(mapOf("cameras" to mapOf(camera to data)), requiresRestart = false, updateTopic = topic)
                c.refresh(reloadConfig = true)
                nav.popBackStack()
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                saving = false
            }
        }
    }

    fun delete() {
        val d = draft ?: return
        val c = conn ?: return
        val client = c.client ?: return
        saving = true
        deleting = true
        val (data, topic) = location(d, d.name, null)
        scope.launch {
            try {
                client.setConfig(mapOf("cameras" to mapOf(camera to data)), requiresRestart = false, updateTopic = topic)
                // A deleted zone also leaves the review settings that name it, as Frigate's own page does.
                if (d.kind == ZoneKind.Zone) {
                    val review = runCatching { client.rawConfig() }.getOrNull()["cameras"][camera]["review"]
                    val update = linkedMapOf<String, Any?>()
                    for (part in listOf("alerts", "detections")) {
                        val zones = review[part]["required_zones"].strings() ?: emptyList()
                        if (d.name in zones) update[part] = mapOf("required_zones" to zones.filter { it != d.name })
                    }
                    if (update.isNotEmpty()) {
                        client.setConfig(mapOf("cameras" to mapOf(camera to mapOf("review" to update))), requiresRestart = false,
                            updateTopic = "config/cameras/$camera/review")
                    }
                }
                c.refresh(reloadConfig = true)
                nav.popBackStack()
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                saving = false
                deleting = false
            }
        }
    }

    val d = draft
    val kindName = d?.let { kindTitle(it.kind) } ?: ""
    val canSave = d != null && d.title.isNotBlank() && d.points.size >= 3 && !saving
    ScreenScaffold(
        title = if (d?.isNew == true) stringResource(R.string.setup_new_shape, kindName.lowercase()) else kindName,
        subtitle = conn?.camera(camera)?.displayName ?: Format.prettyName(camera),
        actions = {
            ProgressButton(stringResource(R.string.save), busy = saving && !deleting, onClick = { save() }, enabled = canSave,
                modifier = Modifier.padding(end = 8.dp))
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
            if (d == null) { LoadingBox(); return@Box }
            Column(
                // Dragging a corner must not scroll the page.
                Modifier.readableWidth().verticalScroll(rememberScrollState(), enabled = dragging == null).padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val imageUrl = conn?.client?.latestImageUrl(camera, 720)
                ShapeCanvas(
                    points = d.points, kind = d.kind, imageUrl = imageUrl,
                    aspect = conn?.camera(camera)?.aspectRatio ?: (16f / 9f),
                    dragging = dragging, onDragging = { dragging = it },
                    onMove = { i, p -> draft = draft?.let { it.copy(points = it.points.toMutableList().also { l -> if (i in l.indices) l[i] = p }) } },
                    onInsert = { i ->
                        draft = draft?.let {
                            val a = it.points[i]; val b = it.points[(i + 1) % it.points.size]
                            it.copy(points = it.points.toMutableList().also { l -> l.add(i + 1, ZonePoint((a.x + b.x) / 2, (a.y + b.y) / 2)) })
                        }
                    },
                    onRemove = { i ->
                        draft = draft?.let { if (it.points.size > 3 && dragging == null) it.copy(points = it.points.filterIndexed { j, _ -> j != i }) else it }
                    },
                )
                Note(stringResource(R.string.setup_zone_canvas_note))

                FormCard {
                    OutlinedTextField(d.title, { v -> draft = draft?.copy(title = v) }, label = { Text(stringResource(R.string.setup_name)) },
                        placeholder = { Text(kindName) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(14.dp))
                    RowDivider()
                    SwitchRow(stringResource(R.string.setup_active), d.enabled, { v -> draft = draft?.copy(enabled = v) })
                }
                if (d.isNew && d.title.isNotEmpty()) Note(stringResource(R.string.setup_saved_as, ZoneDraft.key(d.title)))
                d.label?.let { Note(stringResource(R.string.setup_only_for, Format.labelName(it))) }

                if (d.kind == ZoneKind.Zone) {
                    SectionLabel(stringResource(R.string.setup_objects_in_zone), Modifier.padding(top = 6.dp))
                    FormCard {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (label in labels) {
                                    val on = label in d.objects
                                    ToggleChip(Format.labelName(label), on, {
                                        draft = draft?.let { it.copy(objects = if (on) it.objects - label else it.objects + label) }
                                    })
                                }
                            }
                            Text(stringResource(if (d.objects.isEmpty()) R.string.setup_zone_all_objects else R.string.setup_zone_only_objects),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SectionLabel(stringResource(R.string.setup_timing), Modifier.padding(top = 6.dp))
                    FormCard {
                        StepperRow(stringResource(R.string.setup_frames_before), d.inertia, "", step = 1, min = 1, max = 30) { v ->
                            draft = draft?.copy(inertia = v)
                        }
                        RowDivider()
                        StepperRow(stringResource(R.string.setup_loitering_after), d.loiteringTime, "s", step = 5, min = 0, max = 3600) { v ->
                            draft = draft?.copy(loiteringTime = v)
                        }
                    }
                    Note(stringResource(R.string.setup_timing_note))
                }

                if (!d.isNew) {
                    DestructiveWideButton(stringResource(R.string.setup_delete_shape, kindName.lowercase()), onClick = { confirmDelete = true },
                        busy = deleting, enabled = !saving)
                }
            }
        }
    }

    if (confirmDelete && d != null) {
        ConfirmDialog(
            title = stringResource(R.string.setup_delete_shape_title, d.title),
            message = stringResource(R.string.setup_delete_shape_message),
            confirmText = stringResource(R.string.delete),
            onConfirm = { delete() },
            onDismiss = { confirmDelete = false },
        )
    }
    errorMessage?.let { MessageDialog(stringResource(R.string.not_saved), it) { errorMessage = null } }
}

/** A number with − and + beside it. */
@Composable
private fun StepperRow(title: String, value: Int, unit: String, step: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { onChange((value - step).coerceAtLeast(min)) }, enabled = value > min) { Icon(Icons.Filled.Remove, "−") }
        Text("$value$unit", Modifier.widthIn(min = 40.dp), style = MaterialTheme.typography.bodyLarge.merge(nl.wiebenieuwenhuis.tender.ui.components.MonoDigits),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange((value + step).coerceAtMost(max)) }, enabled = value < max) { Icon(Icons.Filled.Add, "+") }
    }
}

/** The camera's picture with the shape on it: corners to drag, + between corners, a magnifier while dragging. */
@Composable
private fun ShapeCanvas(
    points: List<ZonePoint>,
    kind: ZoneKind,
    imageUrl: String?,
    aspect: Float,
    dragging: Int?,
    onDragging: (Int?) -> Unit,
    onMove: (Int, ZonePoint) -> Unit,
    onInsert: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val color = when (kind) {
        ZoneKind.Zone -> MaterialTheme.colorScheme.primary
        ZoneKind.MotionMask -> Color(0xFFBFBFBF)
        ZoneKind.ObjectMask -> Palette.status.destructive
    }
    val density = LocalDensity.current
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis.coerceAtLeast(500)
    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(12.dp)).background(Color.Black)
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val currentPoints by rememberUpdatedState(points)
        if (imageUrl != null) RemoteImage(imageUrl, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxSize()) {
            if (points.size < 2) return@Canvas
            val path = Path().apply {
                moveTo(points[0].x * w, points[0].y * h)
                points.drop(1).forEach { lineTo(it.x * w, it.y * h) }
                close()
            }
            drawPath(path, color.copy(alpha = if (kind == ZoneKind.Zone) 0.25f else 0.45f))
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        }
        // + between each two corners, to add one there.
        if (dragging == null) {
            for (i in points.indices) {
                val a = points[i]; val b = points[(i + 1) % points.size]
                val cx = (a.x + b.x) / 2 * w; val cy = (a.y + b.y) / 2 * h
                val r = with(density) { 18.dp.toPx() }
                Box(
                    Modifier.offset { IntOffset((cx - r).roundToInt(), (cy - r).roundToInt()) }.size(36.dp)
                        .pointerInput(i) { detectTapGestures { onInsert(i) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(20.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, null, Modifier.size(12.dp), tint = color)
                    }
                }
            }
        }
        // Corners: drag to move, hold still to remove.
        for (i in points.indices) {
            val p = points[i]
            val r = with(density) { 22.dp.toPx() }
            Box(
                Modifier.offset { IntOffset((p.x * w - r).roundToInt(), (p.y * h - r).roundToInt()) }.size(44.dp)
                    .pointerInput(i, w, h) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var moved = false
                            var pos = Offset(currentPoints[i].x * w, currentPoints[i].y * h)
                            val slop = viewConfiguration.touchSlop
                            var travelled = 0f
                            // Held still for a while: remove the corner.
                            val heldUp = withTimeoutOrNull(longPressMs) {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                                    if (!c.pressed) return@withTimeoutOrNull true
                                    travelled += c.positionChange().let { hypot(it.x, it.y) }
                                    pos += c.positionChange()
                                    c.consume()
                                    if (travelled > slop) { moved = true; return@withTimeoutOrNull false }
                                }
                                @Suppress("UNREACHABLE_CODE") false
                            }
                            if (heldUp == null && !moved) { onRemove(i); return@awaitEachGesture }
                            if (heldUp == true) return@awaitEachGesture
                            onDragging(i)
                            onMove(i, ZonePoint((pos.x / w).coerceIn(0f, 1f), (pos.y / h).coerceIn(0f, 1f)))
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                pos += c.positionChange()
                                c.consume()
                                onMove(i, ZonePoint((pos.x / w).coerceIn(0f, 1f), (pos.y / h).coerceIn(0f, 1f)))
                            }
                            onDragging(null)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(18.dp).background(if (dragging == i) color else Color.White, CircleShape).border(3.dp, color, CircleShape))
            }
        }
        // A magnifier above the finger while dragging, so the corner can be placed exactly.
        val di = dragging
        if (di != null && di in points.indices && imageUrl != null) {
            val p = points[di]
            val zoom = 2.5f
            val diameter = with(density) { 96.dp.toPx() }
            val px = p.x * w; val py = p.y * h
            val mx = px.coerceIn(diameter / 2, w - diameter / 2)
            val my = if (py > diameter + with(density) { 20.dp.toPx() }) py - diameter / 2 - with(density) { 24.dp.toPx() }
                else py + diameter / 2 + with(density) { 24.dp.toPx() }
            Box(
                Modifier.offset { IntOffset((mx - diameter / 2).roundToInt(), (my - diameter / 2).roundToInt()) }.size(96.dp)
                    .shadow(6.dp, CircleShape).clip(CircleShape).background(Color.Black).border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val zw = with(density) { (w * zoom).toDp() }
                val zh = with(density) { (h * zoom).toDp() }
                RemoteImage(
                    imageUrl,
                    Modifier.requiredSize(zw, zh).offset {
                        IntOffset((w * zoom / 2 - px * zoom).roundToInt(), (h * zoom / 2 - py * zoom).roundToInt())
                    },
                    contentScale = ContentScale.FillBounds,
                )
                Icon(Icons.Filled.Add, null, Modifier.size(16.dp), tint = color)
            }
        }
    }
}

