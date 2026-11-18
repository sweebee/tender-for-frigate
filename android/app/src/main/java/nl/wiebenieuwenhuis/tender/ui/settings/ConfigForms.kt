package nl.wiebenieuwenhuis.tender.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.Lq
import nl.wiebenieuwenhuis.tender.api.bool
import nl.wiebenieuwenhuis.tender.api.dbl
import nl.wiebenieuwenhuis.tender.api.get
import nl.wiebenieuwenhuis.tender.api.obj
import nl.wiebenieuwenhuis.tender.api.strings
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.MonoDigits
import nl.wiebenieuwenhuis.tender.ui.components.ProgressButton
import nl.wiebenieuwenhuis.tender.ui.components.ReadableWidth
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import java.util.Locale
import kotlin.math.roundToInt

// The sections of a server's settings (snapshots, motion, review, …) as forms over Frigate's config.
// Each form reads its values from /api/config, collects changes, and saves them in one go as
// Frigate's own settings pages do: PUT /api/config/set with the changed keys, the update topic that
// applies them at once, and a restart only where Frigate needs one.

/** One value in a form. */
sealed class ConfigValue {
    data class B(val value: Boolean) : ConfigValue()
    data class N(val value: Double) : ConfigValue()
    data class Labels(val value: List<String>) : ConfigValue()

    /** As JSON for Frigate: whole numbers as integers. */
    val json: Any
        get() = when (this) {
            is B -> value
            is N -> if (value == Math.rint(value)) value.toLong() else value
            is Labels -> value
        }
}

sealed class FieldKind {
    data object Toggle : FieldKind()
    /** A whole or decimal number with a unit shown after it ("d", "s", "%", "score"). */
    data class Number(val unit: String, val step: Double, val min: Double, val max: Double) : FieldKind()
    /** A list picked from options (labels, zones). */
    data class Labels(val options: List<String>, val display: (String) -> String) : FieldKind()
}

/** A setting in a form, at a dotted path under the section ("retain.default"). */
data class ConfigField(
    val path: String,
    val title: String,
    val kind: FieldKind,
    val footer: String? = null,
    /** Changing it takes a restart of Frigate. */
    val restart: Boolean = false,
    /** What Frigate uses when the config does not set it. */
    val defaultNumber: Double? = null,
    val defaultBool: Boolean? = null,
    /** Shown only while the toggle at this path is on (an alert's labels while alerts are on). */
    val shownWhen: String? = null,
)

data class ConfigGroup(
    val title: String? = null,
    val fields: List<ConfigField>,
    /** Shown only while this holds for the form's current values (a label's filters while it is tracked). */
    val visible: ((Map<String, ConfigValue>) -> Boolean)? = null,
)

/** Where Frigate applies a section at once, as its web interface sends it. */
object ConfigTopics {
    /** Sections whose global value is every camera's default: a global change goes to all cameras. */
    private val cameraDefaults = setOf("detect", "objects", "motion", "record", "snapshots", "review", "audio", "notifications", "ffmpeg")
    private val cameraSections = setOf("detect", "record", "snapshots", "motion", "objects", "review", "audio", "notifications",
        "live", "birdseye", "face_recognition", "lpr", "semantic_search", "mqtt", "onvif", "zones", "ui")

    fun topic(section: String, camera: String?): String? {
        if (camera != null) return if (section in cameraSections) "config/cameras/$camera/$section" else null
        return if (section in cameraDefaults) "config/cameras/*/$section" else "config/$section"
    }
}

/** A form: its title, an introduction, and its groups, built once the config is loaded. */
class FormSpec(val title: String, val intro: String? = null, val groups: (JsonObject) -> List<ConfigGroup>)

/** The forms of a server's settings, for all cameras (camera null) or one. */
object ServerSections {
    /** Labels to offer: those the server tracks anywhere, and the common ones. */
    fun objectLabels(config: JsonObject): List<String> {
        val labels = mutableSetOf("person", "car", "bicycle", "motorcycle", "bus", "truck", "dog", "cat", "bird", "horse", "package")
        config["cameras"].obj()?.values?.forEach { cam -> cam["objects"]["track"].strings()?.let { labels.addAll(it) } }
        config["objects"]["track"].strings()?.let { labels.addAll(it) }
        return labels.toList()
    }

    fun audioLabels(config: JsonObject): List<String> {
        val labels = mutableSetOf("bark", "fire_alarm", "speech", "yell")
        config["cameras"].obj()?.values?.forEach { cam -> cam["audio"]["listen"].strings()?.let { labels.addAll(it) } }
        return labels.toList()
    }

    fun zones(config: JsonObject, camera: String?): List<String> =
        camera?.let { config["cameras"][it]["zones"].obj()?.keys?.toList() } ?: emptyList()

    private val label: (String) -> String = { Format.labelName(it) }
    private val zoneName: (String) -> String = { Format.prettyName(it) }

    // Frigate sets no upper limit on keeping days or post-capture seconds: only a large sane maximum here.
    private fun days(path: String, title: String, default: Double? = null, max: Double = 36_500.0) =
        ConfigField(path, title, FieldKind.Number("d", 1.0, 0.0, max), defaultNumber = default)
    private fun seconds(path: String, title: String, max: Double = 86_400.0) = ConfigField(path, title, FieldKind.Number("s", 1.0, 0.0, max))
    /** Frigate refuses more than 60 seconds before an alert or detection (MAX_PRE_CAPTURE). */
    private const val MAX_PRE_CAPTURE = 60.0

    fun spec(section: String, camera: String?): FormSpec = when (section) {
        "record" -> FormSpec(L(R.string.settings_record), if (camera == null) L(R.string.settings_record_intro) else null) { _ ->
            listOf(
                ConfigGroup(fields = listOf(ConfigField("enabled", L(R.string.settings_record), FieldKind.Toggle))),
                ConfigGroup(L(R.string.settings_keep), listOf(
                    days("continuous.days", L(R.string.settings_keep_continuous)),
                    days("motion.days", L(R.string.settings_keep_motion)),
                    days("alerts.retain.days", L(R.string.settings_alerts)),
                    days("detections.retain.days", L(R.string.settings_detections)),
                )),
                ConfigGroup(L(R.string.settings_around_alert), listOf(
                    seconds("alerts.pre_capture", L(R.string.settings_before), MAX_PRE_CAPTURE), seconds("alerts.post_capture", L(R.string.settings_after)))),
                ConfigGroup(L(R.string.settings_around_detection), listOf(
                    seconds("detections.pre_capture", L(R.string.settings_before), MAX_PRE_CAPTURE), seconds("detections.post_capture", L(R.string.settings_after)))),
            )
        }
        "snapshots" -> FormSpec(L(R.string.settings_snapshots), L(R.string.settings_snapshots_intro)) { _ ->
            listOf(
                ConfigGroup(fields = listOf(ConfigField("enabled", L(R.string.settings_snapshots), FieldKind.Toggle))),
                ConfigGroup(L(R.string.settings_keep), listOf(days("retain.default", L(R.string.settings_days), 10.0, max = 365.0))),
                ConfigGroup(L(R.string.settings_picture), listOf(
                    ConfigField("bounding_box", L(R.string.settings_bounding_box), FieldKind.Toggle),
                    ConfigField("crop", L(R.string.settings_crop), FieldKind.Toggle),
                    ConfigField("timestamp", L(R.string.settings_timestamp), FieldKind.Toggle),
                    ConfigField("quality", L(R.string.settings_quality), FieldKind.Number("%", 5.0, 10.0, 100.0), defaultNumber = 70.0),
                )),
            )
        }
        "detect" -> FormSpec(L(R.string.settings_object_detection)) { _ ->
            listOf(
                ConfigGroup(fields = listOf(ConfigField("enabled", L(R.string.settings_object_detection), FieldKind.Toggle))),
                ConfigGroup(L(R.string.settings_speed), listOf(
                    ConfigField("fps", L(R.string.settings_fps), FieldKind.Number("fps", 1.0, 1.0, 30.0),
                        footer = L(R.string.settings_fps_footer), restart = camera != null, defaultNumber = 5.0),
                )),
            )
        }
        "objects" -> FormSpec(L(R.string.settings_objects), L(R.string.settings_objects_intro)) { config ->
            val node = if (camera != null) config["cameras"][camera]["objects"] else config["objects"]
            val tracked = node["track"].strings() ?: emptyList()
            val options = objectLabels(config)
            val groups = mutableListOf(ConfigGroup(fields = listOf(
                ConfigField("track", L(R.string.settings_track), FieldKind.Labels(options, label), footer = L(R.string.settings_objects_footer)),
            )))
            // A section per label that can be tracked, shown while it is picked above.
            for (l in (options + tracked).distinct().sortedBy { label(it) }) {
                groups.add(ConfigGroup(label(l), listOf(
                    ConfigField("filters.$l.min_score", L(R.string.settings_min_score), FieldKind.Number("score", 0.05, 0.0, 1.0), defaultNumber = 0.5),
                    ConfigField("filters.$l.threshold", L(R.string.settings_threshold_score), FieldKind.Number("score", 0.05, 0.0, 1.0), defaultNumber = 0.7),
                    ConfigField("filters.$l.min_area", L(R.string.settings_min_area), FieldKind.Number("px", 100.0, 0.0, 1_000_000.0), defaultNumber = 0.0),
                )) { values -> (values["track"] as? ConfigValue.Labels)?.value?.contains(l) == true })
            }
            groups
        }
        "motion" -> FormSpec(L(R.string.settings_motion_detection), L(R.string.settings_motion_intro)) { _ ->
            listOf(
                ConfigGroup(fields = listOf(ConfigField("enabled", L(R.string.settings_motion_detection), FieldKind.Toggle, defaultBool = true))),
                ConfigGroup(L(R.string.settings_sensitivity), listOf(
                    ConfigField("threshold", L(R.string.settings_threshold), FieldKind.Number("", 5.0, 1.0, 255.0),
                        footer = L(R.string.settings_threshold_footer), defaultNumber = 30.0),
                    ConfigField("contour_area", L(R.string.settings_contour_area), FieldKind.Number("", 5.0, 1.0, 200.0), defaultNumber = 10.0),
                    ConfigField("improve_contrast", L(R.string.settings_improve_contrast), FieldKind.Toggle, defaultBool = true),
                )),
            )
        }
        "review" -> FormSpec(L(R.string.settings_review), L(R.string.settings_review_intro)) { config ->
            val options = objectLabels(config)
            val alerts = mutableListOf(
                ConfigField("alerts.enabled", L(R.string.settings_alerts), FieldKind.Toggle),
                ConfigField("alerts.labels", L(R.string.settings_alerts_for), FieldKind.Labels(options, label), shownWhen = "alerts.enabled"),
            )
            val detections = mutableListOf(ConfigField("detections.enabled", L(R.string.settings_detections), FieldKind.Toggle))
            val zones = zones(config, camera)
            if (zones.isNotEmpty()) {
                alerts.add(ConfigField("alerts.required_zones", L(R.string.settings_only_in_zones), FieldKind.Labels(zones, zoneName),
                    footer = L(R.string.settings_zones_footer), shownWhen = "alerts.enabled"))
                detections.add(ConfigField("detections.required_zones", L(R.string.settings_only_in_zones), FieldKind.Labels(zones, zoneName),
                    shownWhen = "detections.enabled"))
            }
            listOf(ConfigGroup(L(R.string.settings_alerts), alerts), ConfigGroup(L(R.string.settings_detections), detections))
        }
        "audio" -> FormSpec(L(R.string.settings_audio_detection), L(R.string.settings_audio_intro)) { config ->
            listOf(
                ConfigGroup(fields = listOf(ConfigField("enabled", L(R.string.settings_audio_detection), FieldKind.Toggle))),
                ConfigGroup(fields = listOf(
                    ConfigField("listen", L(R.string.settings_listen_for), FieldKind.Labels(audioLabels(config), label)),
                    ConfigField("min_volume", L(R.string.settings_min_volume), FieldKind.Number("", 100.0, 100.0, 5000.0), defaultNumber = 500.0),
                )),
            )
        }
        "ui" -> FormSpec(L(R.string.settings_display)) { _ ->
            listOf(ConfigGroup(fields = listOf(
                ConfigField("dashboard", L(R.string.settings_on_dashboard), FieldKind.Toggle, footer = L(R.string.settings_on_dashboard_footer)),
            )))
        }
        "notifications" -> FormSpec(L(R.string.settings_notifications), L(R.string.settings_notifications_intro)) { _ ->
            listOf(ConfigGroup(fields = listOf(
                ConfigField("enabled", L(R.string.settings_notifications), FieldKind.Toggle),
                ConfigField("cooldown", L(R.string.settings_cooldown), FieldKind.Number("s", 10.0, 0.0, 3600.0)),
            )))
        }
        // Enrichments: one switch each; semantic search and face recognition load a model at start.
        "semantic_search", "face_recognition", "lpr" -> {
            val title = enrichmentTitle(section)
            FormSpec(title) { _ ->
                listOf(ConfigGroup(fields = listOf(ConfigField("enabled", title, FieldKind.Toggle, restart = section != "lpr"))))
            }
        }
        else -> FormSpec(Format.prettyName(section)) { _ -> emptyList() }
    }

    fun enrichmentTitle(section: String): String = when (section) {
        "semantic_search" -> L(R.string.settings_semantic_search)
        "face_recognition" -> L(R.string.settings_face_recognition)
        else -> L(R.string.settings_lpr)
    }
}

/** The value in the config for a field, or its default. */
private fun valueOf(raw: JsonElement?, field: ConfigField): ConfigValue = when (field.kind) {
    FieldKind.Toggle -> ConfigValue.B(raw.bool() ?: field.defaultBool ?: false)
    is FieldKind.Number -> ConfigValue.N(raw.dbl() ?: field.defaultNumber ?: 0.0)
    is FieldKind.Labels -> ConfigValue.Labels((raw.strings() ?: emptyList()).sorted())
}

/** A section of the config as a form, for all cameras or one. */
@Composable
fun ConfigFormScreen(route: ConfigSectionRoute) {
    val conn = AppModel.connection(route.serverId)
    val camera = route.camera
    val spec = remember(route.section, camera) { ServerSections.spec(route.section, camera) }
    var config by remember { mutableStateOf<JsonObject?>(null) }
    var groups by remember { mutableStateOf<List<ConfigGroup>>(emptyList()) }
    var original by remember { mutableStateOf<Map<String, ConfigValue>>(emptyMap()) }
    val values = remember { mutableStateMapOf<String, ConfigValue>() }
    var saving by remember { mutableStateOf(false) }
    var savingRestart by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isAdmin = conn?.isAdmin ?: true

    fun sectionNode(c: JsonObject?): JsonElement? =
        if (camera != null) c["cameras"][camera][route.section] else c[route.section]

    suspend fun load() {
        val client = conn?.client ?: return
        val c = runCatching { client.rawConfig() }.getOrNull()
        if (c == null) { errorMessage = L(R.string.settings_could_not_load); return }
        val built = spec.groups(c)
        val node = sectionNode(c)
        val v = built.flatMap { it.fields }.associate { it.path to valueOf(node.at(it.path), it) }
        config = c
        groups = built
        original = v
        values.clear(); values.putAll(v)
    }
    LaunchedEffect(route) { load() }

    val shown = groups.filter { it.visible?.invoke(values) ?: true }
    fun shown(field: ConfigField) = field.shownWhen?.let { (values[it] as? ConfigValue.B)?.value == true } ?: true
    val fields = shown.flatMap { it.fields }
    val changed = fields.filter { values[it.path] != original[it.path] }
    val needsRestart = changed.any { it.restart }

    // For a global form: cameras whose value differs from the saved global one, so a global change
    // does not reach them. (Frigate's /api/config is resolved: every camera carries every section.)
    val overriding: List<String> = if (camera != null) emptyList() else config["cameras"].obj()?.mapNotNull { (name, raw) ->
        val node = raw[route.section] ?: return@mapNotNull null
        val differs = fields.any { f ->
            val v = node.at(f.path)
            v != null && v !is JsonNull && valueOf(v, f) != original[f.path]
        }
        if (differs) conn?.camera(name)?.displayName ?: Format.prettyName(name) else null
    }?.sorted() ?: emptyList()

    fun save(restart: Boolean) {
        val client = conn?.client ?: return
        saving = true; savingRestart = restart
        val node = mutableMapOf<String, Any?>()
        for (f in changed) values[f.path]?.let { node.setNested(f.path, it.json) }
        val data: Map<String, Any?> = if (camera != null) mapOf("cameras" to mapOf(camera to mapOf(route.section to node)))
            else mapOf(route.section to node)
        val restartNeeded = needsRestart
        scope.launch {
            try {
                client.setConfig(data, restartNeeded, if (restartNeeded) null else ConfigTopics.topic(route.section, camera))
                if (restart) restartAndWait(conn)
                load()
                conn.refresh(reloadConfig = true)
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                saving = false
            }
        }
    }

    val cameraName = camera?.let { conn?.camera(it)?.displayName ?: Format.prettyName(it) }
    val title = when {
        cameraName == null -> spec.title
        // As the iOS app: "Recording · Garden" for a camera's recording settings.
        route.section == "record" -> stringResource(R.string.settings_recording_camera, cameraName)
        else -> "${spec.title} · $cameraName"
    }
    ScreenScaffold(
        title,
        bottomBar = {
            AnimatedVisibility(changed.isNotEmpty(), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                    SaveBar(changed.size, needsRestart, saving, savingRestart,
                        onUndo = { values.clear(); values.putAll(original) }, onSave = ::save,
                        modifier = Modifier.widthIn(max = ReadableWidth).padding(horizontal = 12.dp, vertical = 12.dp))
                }
            }
        },
    ) { inner ->
        SettingsPage(inner) {
            spec.intro?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (overriding.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(L(R.string.settings_overriding_cameras, overriding.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (config == null) {
                LoadingBox()
            } else {
                for (group in shown) {
                    group.title?.let { SectionLabel(it, Modifier.padding(top = 10.dp)) }
                    SectionCard {
                        group.fields.forEachIndexed { i, field ->
                            Revealed(shown(field)) {
                                Column {
                                    if (i > 0) RowDivider()
                                    FieldRow(field, values[field.path], values[field.path] != original[field.path], isAdmin) { values[field.path] = it }
                                }
                            }
                        }
                    }
                    group.fields.filter { it.footer != null }.forEach { Revealed(shown(it)) { Footnote(it.footer!!) } }
                }
                if (route.section == "ui" && camera != null) CameraPlaceCard(route.serverId, camera)
            }
        }
    }
    errorMessage?.let { MessageDialog(stringResource(R.string.not_saved), it) { errorMessage = null } }
}

@Composable
private fun FieldRow(field: ConfigField, value: ConfigValue?, changed: Boolean, enabled: Boolean, set: (ConfigValue) -> Unit) {
    when (val kind = field.kind) {
        FieldKind.Toggle -> ToggleRow(field.title, (value as? ConfigValue.B)?.value ?: false, { set(ConfigValue.B(it)) },
            enabled = enabled, highlighted = changed)
        is FieldKind.Number -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(field.title, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
            NumberStepper((value as? ConfigValue.N)?.value ?: 0.0, kind.unit, kind.step, kind.min, kind.max, changed, enabled) { set(ConfigValue.N(it)) }
        }
        is FieldKind.Labels -> {
            val selected = (value as? ConfigValue.Labels)?.value ?: emptyList()
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(field.title, style = MaterialTheme.typography.bodyLarge,
                    color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (option in (kind.options + selected).distinct().sortedBy { kind.display(it) }) {
                        val on = option in selected
                        FilterChip(
                            selected = on, enabled = enabled,
                            onClick = { set(ConfigValue.Labels((if (on) selected - option else selected + option).sorted())) },
                            label = { Text(kind.display(option), maxLines = 1, softWrap = false) },
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Pieces

/** The bar at the bottom while a form has unsaved changes. */
@Composable
fun SaveBar(
    count: Int,
    restart: Boolean,
    saving: Boolean,
    savingRestart: Boolean,
    onUndo: () -> Unit,
    onSave: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Lq(R.plurals.settings_n_changes, count, count), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(if (restart) R.string.settings_needs_restart else R.string.settings_applies_at_once),
                style = MaterialTheme.typography.bodySmall,
                color = if (restart) nl.wiebenieuwenhuis.tender.ui.theme.Palette.status.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val buttonMod = Modifier.heightIn(min = 40.dp)
            if (restart) {
                // Three choices do not fit side by side on a phone: the one that restarts gets its own row.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onUndo, buttonMod.weight(1f), enabled = !saving) { Text(stringResource(R.string.settings_undo), maxLines = 1) }
                    ProgressButton(stringResource(R.string.settings_save_only), saving && !savingRestart, { onSave(false) },
                        buttonMod.weight(1f), enabled = !saving, filled = false)
                }
                ProgressButton(stringResource(R.string.settings_save_and_restart), saving && savingRestart, { onSave(true) },
                    buttonMod.fillMaxWidth(), enabled = !saving)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onUndo, buttonMod.weight(1f), enabled = !saving) { Text(stringResource(R.string.settings_undo), maxLines = 1) }
                    ProgressButton(stringResource(R.string.save), saving, { onSave(false) }, buttonMod.weight(1f))
                }
            }
        }
    }
}

/** − value +, for days, seconds and other numbers. */
@Composable
fun NumberStepper(
    value: Double,
    unit: String,
    step: Double = 1.0,
    min: Double = 0.0,
    max: Double = Double.MAX_VALUE,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    set: (Double) -> Unit,
) {
    // Steps of 0.05 add up to 0.30000000000000004 otherwise.
    fun round(v: Double) = Math.round(v * 10000) / 10000.0
    Row(
        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).dimmed(!enabled),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { set(round(maxOf(min, value - step))) }, enabled = enabled && value > min, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Remove, stringResource(R.string.settings_less))
        }
        Text(
            stepperLabel(value, unit), Modifier.widthIn(min = 64.dp),
            style = MaterialTheme.typography.bodyMedium.merge(MonoDigits), fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = { set(round(minOf(max, value + step))) }, enabled = enabled && value < max, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Add, stringResource(R.string.settings_more))
        }
    }
}

private fun stepperLabel(value: Double, unit: String): String {
    val n = if (value == Math.rint(value)) value.toLong().toString() else String.format(Locale.getDefault(), "%.2f", value)
    return when (unit) {
        "d" -> SettingsFormat.days(value)
        // Scores are 0…1 in the config, shown as a percentage.
        "score" -> "${(value * 100).roundToInt()} %"
        "" -> n
        else -> "$n $unit"
    }
}

/** The rows that open the config forms (snapshots, detection, …), under Recording. */
@Composable
fun SectionLinks(serverId: String, camera: String?, config: JsonObject?) {
    val nav = LocalNav.current
    fun on(section: String): String =
        config[section]["enabled"].bool()?.let { L(if (it) R.string.settings_on else R.string.settings_off) } ?: ""
    val rows = listOf(
        Triple(Icons.Outlined.Photo, "snapshots", R.string.settings_snapshots),
        Triple(Icons.Outlined.CenterFocusStrong, "detect", R.string.settings_object_detection),
        Triple(Icons.AutoMirrored.Outlined.Label, "objects", R.string.settings_objects),
        Triple(Icons.Outlined.Waves, "motion", R.string.settings_motion_detection),
        Triple(Icons.Outlined.Visibility, "review", R.string.settings_review),
        Triple(Icons.Outlined.Hearing, "audio", R.string.settings_audio_detection),
        Triple(Icons.Outlined.Notifications, "notifications", R.string.settings_notifications),
    )
    for ((icon, section, title) in rows) {
        RowDivider()
        val value = if (section == "objects" || section == "review") "" else on(section)
        SettingsRow(icon, stringResource(title), value) { nav.navigate(ConfigSectionRoute(serverId, section, camera)) }
    }
}

/** Enrichments live in separate top-level sections; one screen with a form per section. */
@Composable
fun EnrichmentsScreen(route: EnrichmentsRoute) {
    val nav = LocalNav.current
    ScreenScaffold(stringResource(R.string.settings_enrichments)) { inner ->
        SettingsPage(inner) {
            Text(stringResource(R.string.settings_enrichments_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionCard {
                listOf(Icons.Outlined.Search to "semantic_search", Icons.Outlined.Face to "face_recognition", Icons.Outlined.Pin to "lpr")
                    .forEachIndexed { i, (icon, section) ->
                        if (i > 0) RowDivider()
                        SettingsRow(icon, ServerSections.enrichmentTitle(section)) { nav.navigate(ConfigSectionRoute(route.serverId, section)) }
                    }
            }
        }
    }
}

/** Icon for the enrichments row on the server screen. */
val EnrichmentsIcon = Icons.Outlined.AutoAwesome

/**
 * Where a camera stands in the server's order ("4th of 6"), opening the screen to drag them;
 * Frigate keeps the order as a number per camera (ui.order), which says little on its own.
 */
@Composable
fun CameraPlaceCard(serverId: String, camera: String) {
    val nav = LocalNav.current
    val cams = orderedCameras(serverId)
    val place = cams.indexOfFirst { it.name == camera }.takeIf { it >= 0 }?.plus(1)
    SectionLabel(stringResource(R.string.settings_order), Modifier.padding(top = 10.dp))
    SectionCard {
        SettingsRow(Icons.Outlined.SwapVert, stringResource(R.string.settings_place),
            place?.let { stringResource(R.string.settings_place_of, it, cams.size) } ?: "") { nav.navigate(CameraOrderRoute(serverId)) }
    }
    Footnote(stringResource(R.string.settings_order_footer))
}

/** The server's cameras that run (not off in the config), in Frigate's UI order. */
fun orderedCameras(serverId: String) = (AppModel.connection(serverId)?.config?.cameras?.values ?: emptyList())
    .filter { it.enabledInConfig != false }
    .sortedWith(compareBy({ it.uiOrder ?: 0 }, { it.name }))

/** A field that shows only while its toggle is on: it folds open and shut. */
@Composable
private fun Revealed(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { content() }
}
