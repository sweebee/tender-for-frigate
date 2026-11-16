package nl.wiebenieuwenhuis.tender.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.CameraConfig
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateStats
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.bool
import nl.wiebenieuwenhuis.tender.api.dbl
import nl.wiebenieuwenhuis.tender.api.get
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConfig
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.CameraWizardRoute
import nl.wiebenieuwenhuis.tender.ui.ConfigEditorRoute
import nl.wiebenieuwenhuis.tender.ui.ConnectRoute
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.ServerSettingsRoute
import nl.wiebenieuwenhuis.tender.ui.WebInterfaceRoute
import nl.wiebenieuwenhuis.tender.ui.components.ConfirmDialog
import nl.wiebenieuwenhuis.tender.ui.components.EmptyState
import nl.wiebenieuwenhuis.tender.ui.components.ErrorBanner
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.MonoDigits
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.StatusDot
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import kotlin.math.roundToInt

// Settings of a Frigate server itself, after the settings pages of Frigate 0.18's web interface:
// a server overview, per-camera switches and the recording retention. Changes are sent as Frigate's
// own pages send them (PUT /api/config/set) and apply without a restart where Frigate allows it.

// MARK: - Routes of this package

@Serializable data class CameraSettingsRoute(val serverId: String, val camera: String)
/** A config section as a form ("record", "snapshots", "semantic_search", …), global or for one camera. */
@Serializable data class ConfigSectionRoute(val serverId: String, val section: String, val camera: String? = null)
@Serializable data class EnrichmentsRoute(val serverId: String)
@Serializable data class ProfilesRoute(val serverId: String)
@Serializable data class ProfileEditRoute(val serverId: String, val profile: String, val title: String, val active: Boolean)
@Serializable data class UsersRoute(val serverId: String)
@Serializable data class ZonesMasksRoute(val serverId: String, val camera: String)
@Serializable data class MqttRoute(val serverId: String)
@Serializable data class CameraOrderRoute(val serverId: String)
@Serializable data class SystemStatusRoute(val serverId: String)

fun NavGraphBuilder.settingsDestinations() {
    composable<CameraSettingsRoute> { CameraSettingsScreen(it.toRoute()) }
    composable<ConfigSectionRoute> { ConfigFormScreen(it.toRoute()) }
    composable<EnrichmentsRoute> { EnrichmentsScreen(it.toRoute()) }
    composable<ProfilesRoute> { ProfilesScreen(it.toRoute()) }
    composable<ProfileEditRoute> { ProfileEditScreen(it.toRoute()) }
    composable<UsersRoute> { UsersScreen(it.toRoute()) }
    composable<ZonesMasksRoute> { ZonesMasksScreen(it.toRoute()) }
    composable<MqttRoute> { MqttScreen(it.toRoute()) }
    composable<CameraOrderRoute> { CameraOrderScreen(it.toRoute()) }
    composable<SystemStatusRoute> { SystemStatusScreen(it.toRoute()) }
}

// MARK: - Server

/** A server's settings: status, cameras, global configuration and maintenance. */
@Composable
fun ServerSettingsScreen(route: ServerSettingsRoute) {
    val nav = LocalNav.current
    val serverId = route.serverId
    val conn = AppModel.connection(serverId)
    var config by remember { mutableStateOf<JsonObject?>(conn?.config?.raw) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var confirmRestart by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Removed in the connection screen: nothing left to show here.
    LaunchedEffect(conn == null) { if (conn == null) nav.popBackStack() }

    suspend fun load() {
        val c = conn ?: return
        val client = c.client ?: return
        try {
            coroutineScope {
                val cfg = async { client.rawConfig() }
                val role = async { client.role() }
                config = cfg.await()
                c.role = role.await()
            }
            loadError = null
        } catch (e: Exception) {
            loadError = e.message
        }
        c.refresh()
    }
    LaunchedEffect(conn?.client) { load() }

    val isAdmin = conn?.isAdmin ?: true
    // Login is on (auth.enabled; on unless the config turns it off). Without it Frigate has no users.
    val authEnabled = config["auth"]["enabled"].bool() ?: true

    ScreenScaffold(conn?.server?.name ?: stringResource(R.string.server)) { inner ->
        SettingsPage(inner, onRefresh = { load() }) {
            loadError?.let { ErrorBanner(it) { scope.launch { load() } } }
            if (conn == null) {
                EmptyState(Icons.Outlined.Dns, stringResource(R.string.settings_server_unavailable))
                return@SettingsPage
            }
            StatusCard(conn)
            // The connection is the app's own setting for this server, and must be reachable when it is offline.
            SectionCard {
                SettingsRow(Icons.Outlined.Link, stringResource(R.string.settings_connection), connectionHost(conn.server)) {
                    nav.navigate(ConnectRoute(serverId))
                }
            }
            if (!isAdmin) Footnote(stringResource(R.string.settings_viewer_notice))
            if (Format.versionAtLeast(conn.version, 0, 18)) {
                SectionLabel(stringResource(R.string.settings_profile), Modifier.padding(top = 10.dp))
                SectionCard {
                    SettingsRow(Icons.Outlined.SwitchAccount, stringResource(R.string.settings_profiles),
                        conn.activeProfileTitle ?: stringResource(if (conn.profiles.isEmpty()) R.string.settings_none else R.string.settings_none_active)) {
                        nav.navigate(ProfilesRoute(serverId))
                    }
                }
            }
            CameraSection(conn, isAdmin)

            SectionLabel(stringResource(R.string.settings_global_configuration), Modifier.padding(top = 10.dp))
            SectionCard {
                SettingsRow(Icons.Outlined.FiberManualRecord, stringResource(R.string.settings_record), retentionSummary(config),
                    tint = Palette.status.recording) { nav.navigate(ConfigSectionRoute(serverId, "record")) }
                SectionLinks(serverId, null, config)
            }

            SectionLabel(stringResource(R.string.settings_enrichments), Modifier.padding(top = 10.dp))
            SectionCard {
                SettingsRow(EnrichmentsIcon, stringResource(R.string.settings_enrichments), enrichmentSummary(config)) {
                    nav.navigate(EnrichmentsRoute(serverId))
                }
            }

            SectionLabel(stringResource(R.string.settings_system), Modifier.padding(top = 10.dp))
            SectionCard {
                if (isAdmin && authEnabled) {
                    SettingsRow(Icons.Outlined.AccountCircle, stringResource(R.string.settings_users)) { nav.navigate(UsersRoute(serverId)) }
                    RowDivider()
                }
                SettingsRow(Icons.Outlined.Speed, stringResource(R.string.settings_system_status)) { nav.navigate(SystemStatusRoute(serverId)) }
                RowDivider()
                SettingsRow(Icons.Outlined.SettingsInputAntenna, "MQTT",
                    stringResource(if (config["mqtt"]["enabled"].bool() == true) R.string.settings_on else R.string.settings_off)) {
                    nav.navigate(MqttRoute(serverId))
                }
                if (isAdmin) {
                    RowDivider()
                    SettingsRow(Icons.Outlined.Description, stringResource(R.string.settings_configuration), "YAML") {
                        nav.navigate(ConfigEditorRoute(serverId))
                    }
                }
                if (conn.client != null) {
                    RowDivider()
                    SettingsRow(Icons.Outlined.Public, stringResource(R.string.settings_open_web_interface),
                        stringResource(R.string.settings_all_settings), chevron = false) { nav.navigate(WebInterfaceRoute(serverId)) }
                }
                if (isAdmin) {
                    RowDivider()
                    SettingsRow(null, stringResource(if (restarting) R.string.settings_restarting else R.string.settings_restart_frigate),
                        chevron = false, pending = restarting, enabled = conn.client != null,
                        titleColor = MaterialTheme.colorScheme.error) { confirmRestart = true }
                }
            }
        }
    }

    if (confirmRestart) ConfirmDialog(
        title = stringResource(R.string.settings_restart_question),
        message = stringResource(R.string.settings_restart_message),
        confirmText = stringResource(R.string.settings_restart),
        onConfirm = {
            val c = conn
            if (c != null) {
                restarting = true
                scope.launch {
                    try { restartAndWait(c); load() } catch (e: Exception) { errorMessage = e.message } finally { restarting = false }
                }
            }
        },
        onDismiss = { confirmRestart = false },
    )
    errorMessage?.let { MessageDialog(stringResource(R.string.settings_something_wrong), it) { errorMessage = null } }
}

private fun connectionHost(server: ServerConfig): String =
    ServerConfig.normalizedUrl(server.url)?.let { u ->
        runCatching { java.net.URI(u) }.getOrNull()?.let { (it.host ?: "") + (if (it.port > 0) ":${it.port}" else "") }
    } ?: server.url

/** "Search, Faces" for the enrichments that are on. */
private fun enrichmentSummary(config: JsonObject?): String {
    val names = listOf("semantic_search" to R.string.settings_search, "face_recognition" to R.string.settings_faces, "lpr" to R.string.settings_plates)
        .filter { config[it.first]["enabled"].bool() == true }.map { L(it.second) }
    return if (names.isEmpty()) L(R.string.settings_off) else names.joinToString(", ")
}

/** "30 days" for the longest kept recordings. */
private fun retentionSummary(config: JsonObject?): String {
    val record = config["record"] ?: return ""
    if (record["enabled"].bool() == false) return L(R.string.settings_off)
    val days = listOf("continuous.days", "motion.days", "alerts.retain.days", "detections.retain.days").mapNotNull { record.at(it).dbl() }.maxOrNull()
    return days?.let { SettingsFormat.days(it) } ?: ""
}

// MARK: Status

@Composable
private fun StatusCard(conn: ServerConnection) {
    val stats = conn.stats
    SectionCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(if (conn.isOnline) Palette.status.online else Palette.status.offline, size = 8.dp)
                Text(
                    stringResource(if (!conn.isOnline) R.string.offline else if (conn.usingRemote) R.string.settings_connected_remote else R.string.settings_connected_local),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(versionLine(conn, stats), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val disk = stats?.recordingStorage
            val total = disk?.total
            val used = disk?.used
            if (total != null && total > 0 && used != null) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row {
                        Text(stringResource(R.string.settings_storage), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${SettingsFormat.size(used)} / ${SettingsFormat.size(total)}",
                            style = MaterialTheme.typography.labelMedium.merge(MonoDigits), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val fraction = (used / total).toFloat().coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = if (fraction > 0.9f) Palette.status.warning else MaterialTheme.colorScheme.primary,
                        drawStopIndicator = {},
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                stats?.detectors?.values?.firstNotNullOfOrNull { it }?.let { StatTile(stringResource(R.string.settings_detector), "${it.roundToInt()} ms") }
                stats?.cpuPercent?.let { StatTile("CPU", "${it.roundToInt()} %") }
                StatTile(stringResource(R.string.settings_cameras), "${conn.config?.cameras?.size ?: 0}")
            }
        }
    }
}

@Composable
private fun RowScope.StatTile(title: String, value: String) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleSmall.merge(MonoDigits), maxLines = 1)
    }
}

/** "Frigate 0.18.2 · online for 3 d 4 h · 0.19.0 available". */
fun versionLine(conn: ServerConnection, stats: FrigateStats?): String {
    val current = conn.version?.split("-")?.first()
    val parts = mutableListOf(current?.let { "Frigate $it" } ?: "Frigate")
    stats?.uptime?.let { parts.add(L(R.string.settings_online_for, SettingsFormat.uptime(it))) }
    updateAvailable(conn, stats)?.let { parts.add(L(R.string.settings_version_available, it)) }
    return parts.joinToString(" · ")
}

/** The newer Frigate version, when Frigate knows one. */
fun updateAvailable(conn: ServerConnection, stats: FrigateStats?): String? {
    val latest = stats?.latestVersion?.takeIf { it.isNotEmpty() && it != "disabled" } ?: return null
    val current = conn.version?.split("-")?.first() ?: return null
    return latest.takeIf { SettingsFormat.compareVersions(it, current) > 0 }
}

// MARK: Cameras

@Composable
private fun CameraSection(conn: ServerConnection, isAdmin: Boolean) {
    val nav = LocalNav.current
    val all = (conn.config?.cameras?.values ?: emptyList()).sortedWith(compareBy({ it.uiOrder ?: 0 }, { it.name }))
    val cams = all.filter { it.enabledInConfig != false }
    // Off in the config file: Frigate lists them apart, under their own heading.
    val disabled = all.filter { it.enabledInConfig == false }.sortedBy { it.displayName.lowercase() }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(stringResource(R.string.settings_cameras), Modifier.weight(1f))
        if (isAdmin && cams.size > 1) {
            IconButton(onClick = { nav.navigate(CameraOrderRoute(conn.id)) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.SwapVert, stringResource(R.string.settings_camera_order))
            }
        }
        if (isAdmin && Format.versionAtLeast(conn.version, 0, 17)) {
            IconButton(onClick = { nav.navigate(CameraWizardRoute(conn.id)) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Add, stringResource(R.string.settings_add_camera))
            }
        }
    }
    if (all.isEmpty()) return
    SectionCard {
        cams.forEachIndexed { i, cam ->
            if (i > 0) RowDivider()
            val on = conn.isCameraEnabled(cam)
            SettingsRow(if (on) Icons.Outlined.Videocam else Icons.Outlined.VideocamOff, cam.displayName, cameraSummary(conn, cam),
                tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) {
                nav.navigate(CameraSettingsRoute(conn.id, cam.name))
            }
        }
        if (disabled.isNotEmpty()) {
            if (cams.isNotEmpty()) RowDivider()
            Text(stringResource(R.string.settings_disabled_in_configuration).uppercase(),
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            disabled.forEachIndexed { i, cam ->
                if (i > 0) RowDivider()
                Column(Modifier.dimmed(true)) {
                    SettingsRow(Icons.Outlined.VideocamOff, cam.displayName, stringResource(R.string.settings_disabled),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) { nav.navigate(CameraSettingsRoute(conn.id, cam.name)) }
                }
            }
        }
    }
}

private fun cameraSummary(conn: ServerConnection, cam: CameraConfig): String {
    if (!conn.isCameraEnabled(cam)) return L(R.string.settings_off)
    val off = mutableListOf<String>()
    if (!conn.isDetectEnabled(cam)) off.add(L(R.string.settings_detection_off))
    if (conn.isRecordSwitchedOff(cam)) off.add(L(R.string.settings_recording_off))
    return off.joinToString(", ")
}
