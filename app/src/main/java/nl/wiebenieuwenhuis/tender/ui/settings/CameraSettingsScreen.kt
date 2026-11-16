package nl.wiebenieuwenhuis.tender.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.deleteCamera
import nl.wiebenieuwenhuis.tender.api.get
import nl.wiebenieuwenhuis.tender.api.obj
import nl.wiebenieuwenhuis.tender.api.str
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.api.CameraConfig
import nl.wiebenieuwenhuis.tender.ui.CameraStreamsRoute
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.components.ErrorBanner
import nl.wiebenieuwenhuis.tender.ui.components.ProgressButton
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.theme.Palette

/** One camera's switches (as in its menus), its configuration sections, and disabling or deleting it. */
@Composable
fun CameraSettingsScreen(route: CameraSettingsRoute) {
    val nav = LocalNav.current
    val camera = route.camera
    val conn = AppModel.connection(route.serverId)
    val cam = conn?.camera(camera)
    val isAdmin = conn?.isAdmin ?: true
    /** Saved but not applied until Frigate restarts (its /api/config shows the old value till then). */
    var savedType by remember { mutableStateOf<String?>(null) }
    var savedEnabled by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var askRestart by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val offInConfig = savedEnabled?.let { !it } ?: (cam?.enabledInConfig == false)
    val cameraType = savedType ?: if (cam?.type == "lpr") "lpr" else "generic"

    fun restart() {
        val c = conn ?: return
        restarting = true; errorMessage = null
        scope.launch {
            try { restartAndWait(c); savedType = null; savedEnabled = null }
            catch (e: Exception) { errorMessage = e.message }
            finally { restarting = false }
        }
    }

    /** Normal or dedicated LPR camera, as Frigate's camera type; applies after a restart. */
    fun setType(type: String) {
        val client = conn?.client ?: return
        if (type == cameraType) return
        busy = "type"; errorMessage = null
        scope.launch {
            try {
                // "" removes the key, so the camera is "generic" again.
                client.setConfig(mapOf("cameras" to mapOf(camera to mapOf("type" to if (type == "lpr") "lpr" else ""))), true, null)
                savedType = type
                askRestart = true
            } catch (e: Exception) { errorMessage = e.message } finally { busy = null }
        }
    }

    fun setEnabledInConfig(on: Boolean) {
        val c = conn ?: return
        val client = c.client ?: return
        val cc = cam ?: return
        busy = "enabled"; errorMessage = null
        scope.launch {
            try {
                if (on) {
                    client.setConfig(mapOf("cameras" to mapOf(camera to mapOf("enabled" to true))), true, null)
                    savedEnabled = true
                    askRestart = true
                } else {
                    // Stop it now, as Frigate does, then keep it off in the config.
                    if (c.isCameraEnabled(cc)) c.setCameraEnabled(cc, false)
                    client.setConfig(mapOf("cameras" to mapOf(camera to mapOf("enabled" to false))), false, null)
                    savedEnabled = false
                    c.refresh(reloadConfig = true)
                }
            } catch (e: Exception) { errorMessage = e.message } finally { busy = null }
        }
    }

    ScreenScaffold(cam?.displayName ?: Format.prettyName(camera)) { inner ->
        SettingsPage(inner) {
            errorMessage?.let { ErrorBanner(it) }
            if (cam != null && (cam.enabledInConfig == false || savedEnabled == true)) {
                DisabledCard(savedEnabled == true, isAdmin, restarting, busy == "enabled", busy != null,
                    onEnable = { setEnabledInConfig(true) }, onRestart = { restart() })
            }
            if (restarting && savedEnabled != true) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallSpinner()
                    Text(stringResource(R.string.settings_restarting), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (conn != null && cam != null && cam.enabledInConfig != false && savedEnabled != true) {
                val on = conn.isCameraEnabled(cam)
                SectionCard {
                    if (cam.canSwitchOff) {
                        ToggleRow(stringResource(R.string.settings_camera), on, { conn.setCameraEnabled(cam, it) },
                            pending = conn.isSwitchPending(cam, "enabled"), enabled = isAdmin)
                    }
                    if (on) {
                        if (cam.canSwitchOff) RowDivider()
                        ToggleRow(stringResource(R.string.settings_object_detection), conn.isDetectEnabled(cam), { conn.setDetectEnabled(cam, it) },
                            pending = conn.isSwitchPending(cam, "detect"), enabled = isAdmin)
                        if (conn.canSwitchRecording(cam)) {
                            RowDivider()
                            ToggleRow(stringResource(R.string.settings_record), conn.isRecordEnabled(cam), { conn.setRecordEnabled(cam, it) },
                                pending = conn.isSwitchPending(cam, "recordings"), enabled = isAdmin)
                        }
                    }
                }
                if (on && conn.hasNotifications(cam)) SectionCard { NotificationsRow(conn, cam) }
                Footnote(stringResource(R.string.settings_switches_footer))
            }
            if (conn != null && cam != null) {
                SectionLabel(stringResource(R.string.settings_configuration), Modifier.padding(top = 10.dp))
                SectionCard {
                    // Frigate 0.17+ gives admins the real stream addresses (api/config/raw_paths).
                    if (isAdmin && Format.versionAtLeast(conn.version, 0, 17)) {
                        SettingsRow(Icons.Outlined.SettingsInputAntenna, stringResource(R.string.settings_streams)) {
                            nav.navigate(CameraStreamsRoute(route.serverId, camera))
                        }
                        RowDivider()
                    }
                    if (isAdmin && (conn.config?.platesOnServer == true || cam.type == "lpr")) {
                        ChoiceRow(
                            stringResource(R.string.settings_type),
                            listOf("generic" to stringResource(R.string.settings_type_normal), "lpr" to stringResource(R.string.settings_type_lpr)),
                            cameraType, { setType(it) }, icon = Icons.Outlined.DirectionsCar, pending = busy == "type", enabled = busy == null,
                        )
                        RowDivider()
                    }
                    SettingsRow(Icons.Outlined.FiberManualRecord, stringResource(R.string.settings_record), tint = Palette.status.recording) {
                        nav.navigate(ConfigSectionRoute(route.serverId, "record", camera))
                    }
                    SectionLinks(route.serverId, camera, null)
                    RowDivider()
                    SettingsRow(Icons.Outlined.CropFree, stringResource(R.string.settings_zones_masks)) {
                        nav.navigate(ZonesMasksRoute(route.serverId, camera))
                    }
                    RowDivider()
                    SettingsRow(Icons.Outlined.GridView, stringResource(R.string.settings_display),
                        if (cam.uiDashboard == false) stringResource(R.string.settings_hidden) else "") {
                        nav.navigate(ConfigSectionRoute(route.serverId, "ui", camera))
                    }
                }
                Footnote(stringResource(R.string.settings_camera_override_footer))
                if (isAdmin) {
                    SectionCard(Modifier.padding(top = 18.dp)) {
                        // As Frigate's "Disabled": stops it now and keeps it off in the config.
                        if (!offInConfig) {
                            SettingsRow(Icons.Outlined.VideocamOff, stringResource(R.string.settings_disable_in_configuration), chevron = false,
                                pending = busy == "enabled", enabled = busy == null) { setEnabledInConfig(false) }
                        }
                        // Frigate 0.18 deletes a camera with its recordings (DELETE /api/cameras/<name>).
                        if (Format.versionAtLeast(conn.version, 0, 18)) {
                            if (!offInConfig) RowDivider()
                            SettingsRow(Icons.Outlined.Delete, stringResource(R.string.settings_delete_camera), chevron = false,
                                tint = MaterialTheme.colorScheme.error, titleColor = MaterialTheme.colorScheme.error) { confirmingDelete = true }
                        }
                    }
                }
            }
        }
    }

    if (askRestart) AlertDialog(
        onDismissRequest = { askRestart = false },
        title = { Text(stringResource(R.string.settings_restart_now_question)) },
        text = { Text(stringResource(R.string.settings_saved_after_restart)) },
        confirmButton = { TextButton(onClick = { askRestart = false; restart() }) { Text(stringResource(R.string.settings_restart)) } },
        dismissButton = { TextButton(onClick = { askRestart = false }) { Text(stringResource(R.string.settings_later)) } },
    )
    if (confirmingDelete && conn != null) {
        DeleteCameraDialog(conn, camera, cam?.displayName ?: camera,
            onDismiss = { confirmingDelete = false },
            onDeleted = { confirmingDelete = false; nav.popBackStack() })
    }
}

/** Off in the config file: Frigate does not run it; switching it on takes a restart. */
@Composable
private fun DisabledCard(
    enabledAfterRestart: Boolean, isAdmin: Boolean, restarting: Boolean, enabling: Boolean, busy: Boolean,
    onEnable: () -> Unit, onRestart: () -> Unit,
) {
    SectionCard {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val tint = if (enabledAfterRestart) Palette.status.warning else MaterialTheme.colorScheme.onSurfaceVariant
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (enabledAfterRestart) Icons.Outlined.RestartAlt else Icons.Outlined.VideocamOff, null, tint = tint)
                Text(stringResource(if (enabledAfterRestart) R.string.settings_enabled_after_restart else R.string.settings_disabled_in_configuration),
                    style = MaterialTheme.typography.titleSmall, color = tint)
            }
            Text(stringResource(if (enabledAfterRestart) R.string.settings_starts_after_restart else R.string.settings_disabled_explained),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isAdmin) {
                if (enabledAfterRestart) {
                    ProgressButton(stringResource(R.string.settings_restart_frigate), restarting, onRestart)
                } else {
                    ProgressButton(stringResource(R.string.settings_enable), enabling, onEnable, enabled = !busy)
                }
            }
        }
    }
}

/** Frigate's own notifications for the camera: paused for a while, or resumed. */
@Composable
private fun NotificationsRow(conn: ServerConnection, cam: CameraConfig) {
    var open by remember { mutableStateOf(false) }
    val until = conn.notificationsPaused(cam)
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_notifications), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (until != null) Text(stringResource(R.string.settings_paused_until, Format.time(until)),
            style = MaterialTheme.typography.bodyMedium, color = Palette.status.warning)
        Box {
            IconButton(onClick = { open = true }) {
                Icon(if (until == null) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                    stringResource(R.string.settings_pause_notifications), tint = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (until != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_resume_notifications)) },
                        leadingIcon = { Icon(Icons.Outlined.Notifications, null) },
                        onClick = { open = false; conn.pauseNotifications(cam, 0) },
                    )
                } else {
                    Text(stringResource(R.string.settings_pause_notifications), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(30 to R.string.settings_30_minutes, 60 to R.string.settings_1_hour, 480 to R.string.settings_8_hours,
                        1440 to R.string.settings_24_hours).forEach { (m, title) ->
                        DropdownMenuItem(text = { Text(stringResource(title)) }, onClick = { open = false; conn.pauseNotifications(cam, m) })
                    }
                }
            }
        }
    }
}

/**
 * Deleting a camera, as Frigate's own dialog: everything of it goes, exports only when asked.
 * Its go2rtc streams that no other camera uses are removed as well (Frigate leaves them).
 */
@Composable
fun DeleteCameraDialog(conn: ServerConnection, camera: String, title: String, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    var deleteExports by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun delete() {
        val client = conn.client ?: return
        deleting = true; errorMessage = null
        scope.launch {
            // The go2rtc streams this camera reads and no other camera does, to clean up afterwards.
            val orphans = runCatching {
                val cfg = client.rawConfig()
                val cams = cfg["cameras"].obj() ?: emptyMap()
                fun streams(c: kotlinx.serialization.json.JsonElement?): Set<String> {
                    val out = mutableSetOf<String>()
                    c["ffmpeg"]["inputs"].let { it as? kotlinx.serialization.json.JsonArray }?.forEach { input ->
                        val path = input["path"].str() ?: return@forEach
                        val prefix = "rtsp://127.0.0.1:8554/"
                        if (path.startsWith(prefix)) out.add(path.removePrefix(prefix).substringBefore("?"))
                    }
                    c["live"]["streams"].obj()?.values?.forEach { v -> v.str()?.let { out.add(it) } }
                    return out
                }
                val mine = streams(cams[camera])
                val others = cams.filterKeys { it != camera }.values.flatMap { streams(it) }.toSet()
                val defined = cfg["go2rtc"]["streams"].obj()?.keys ?: emptySet()
                (mine - others).intersect(defined)
            }.getOrDefault(emptySet())
            try {
                client.deleteCamera(camera, deleteExports)
            } catch (e: Exception) {
                errorMessage = e.message; deleting = false
                return@launch
            }
            if (orphans.isNotEmpty()) {
                runCatching { client.setConfig(mapOf("go2rtc" to mapOf("streams" to orphans.associateWith { "" })), false, null) }
            }
            conn.refresh(reloadConfig = true)
            deleting = false
            onDeleted()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !deleting, dismissOnClickOutside = !deleting),
        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.settings_delete_camera_question, title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_delete_camera_message))
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !deleting) { deleteExports = !deleteExports },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_delete_exports_too), Modifier.weight(1f))
                    Switch(deleteExports, { deleteExports = it }, enabled = !deleting,
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.error))
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            ProgressButton(stringResource(R.string.settings_delete_for_good), deleting, { delete() }, destructive = true)
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !deleting) { Text(stringResource(R.string.cancel)) } },
    )
}

