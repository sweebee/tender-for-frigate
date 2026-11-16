package nl.wiebenieuwenhuis.tender.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.CameraConfig
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.ReviewItem
import nl.wiebenieuwenhuis.tender.api.Severity
import nl.wiebenieuwenhuis.tender.api.nowSeconds
import nl.wiebenieuwenhuis.tender.api.toDate
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.ItemCache
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.PlaybackRoute
import nl.wiebenieuwenhuis.tender.ui.ServerSettingsRoute
import nl.wiebenieuwenhuis.tender.ui.components.LabelIcon
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.RemoteImage
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.components.VideoSurface
import nl.wiebenieuwenhuis.tender.ui.components.load
import nl.wiebenieuwenhuis.tender.ui.components.rememberVideoPlayer
import nl.wiebenieuwenhuis.tender.ui.theme.Palette

// MARK: - Camera switches

/**
 * Frigate's switches for a camera, in its menus: detection and recording (while the camera
 * runs), pausing notifications, and switching the camera off or on (Frigate 0.16+).
 */
@Composable
fun CameraSwitchItems(conn: ServerConnection, camera: CameraConfig, close: () -> Unit) {
    val on = conn.isCameraEnabled(camera)
    HorizontalDivider()
    Text("Frigate", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (on) {
        SwitchMenuItem(stringResource(R.string.detection), conn.isDetectEnabled(camera), conn.isSwitchPending(camera, "detect")) {
            conn.setDetectEnabled(camera, it)
        }
        if (conn.canSwitchRecording(camera)) SwitchMenuItem(stringResource(R.string.recordings), conn.isRecordEnabled(camera),
            conn.isSwitchPending(camera, "recordings")) { conn.setRecordEnabled(camera, it) }
    }
    if (on && conn.hasNotifications(camera)) {
        val until = conn.notificationsPaused(camera)
        if (until != null) {
            DropdownMenuItem(text = { Text(stringResource(R.string.resume_notifications, Format.time(until))) },
                leadingIcon = { Icon(Icons.Outlined.Notifications, null) }, onClick = { conn.pauseNotifications(camera, 0); close() })
        } else {
            var open by remember { mutableStateOf(false) }
            DropdownMenuItem(text = { Text(stringResource(R.string.pause_notifications)) },
                leadingIcon = { Icon(Icons.Outlined.NotificationsOff, null) },
                trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }, onClick = { open = true })
            DropdownMenu(open, { open = false }) {
                listOf(30 to R.string.minutes_30, 60 to R.string.hour_1, 480 to R.string.hours_8, 1440 to R.string.hours_24).forEach { (m, t) ->
                    DropdownMenuItem(text = { Text(stringResource(t)) }, onClick = { open = false; conn.pauseNotifications(camera, m); close() })
                }
            }
        }
    }
    if (camera.canSwitchOff) DropdownMenuItem(
        text = { Text(stringResource(if (on) R.string.switch_camera_off else R.string.switch_camera_on)) },
        leadingIcon = { if (conn.isSwitchPending(camera, "enabled")) SmallSpinner() else Icon(Icons.Outlined.PowerSettingsNew, null) },
        onClick = { conn.setCameraEnabled(camera, !on); close() },
    )
}

/** A switch in a menu; it shows a spinner until Frigate confirms the change over its websocket. */
@Composable
private fun SwitchMenuItem(title: String, checked: Boolean, pending: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(title) },
        trailingIcon = {
            if (pending) SmallSpinner(Modifier.padding(horizontal = 14.dp))
            else Switch(checked, onCheckedChange = onChange, Modifier.height(24.dp))
        },
        onClick = { if (!pending) onChange(!checked) },
        enabled = !pending,
    )
}

// MARK: - Profiles

/**
 * The active Frigate profile as a chip in the Live header; a tap lists the profiles to switch
 * to, per server when several have them. Hidden when no server in view has profiles.
 */
@Composable
fun ProfileSwitcher() {
    val servers = AppModel.scopedConnections.filter { it.isOnline && it.profiles.isNotEmpty() }
    if (servers.isEmpty()) return
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val active = servers.any { it.activeProfile != null }
    val titles = servers.mapNotNull { it.activeProfileTitle }.toSet()
    val label = when {
        titles.isEmpty() -> stringResource(R.string.no_profile)
        titles.size == 1 -> titles.first()
        else -> stringResource(R.string.several)
    }
    fun pick(conn: ServerConnection, name: String?) {
        open = false
        switching = true
        scope.launch {
            try {
                conn.setProfile(name)
                // Frigate applies it to every camera, which takes a few seconds.
                for (i in 0 until 10) {
                    delay(1000)
                    if (runCatching { conn.client?.profiles()?.second }.getOrNull() == name) break
                }
                conn.refresh(reloadConfig = true)
            } catch (e: Exception) { error = e.message } finally { switching = false }
        }
    }
    Box {
        AssistChip(
            onClick = { open = true }, enabled = !switching,
            label = { Text(label, maxLines = 1) },
            leadingIcon = { if (switching) SmallSpinner(size = 16.dp) else Icon(Icons.Outlined.PeopleAlt, null, Modifier.size(16.dp)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
            colors = if (active) AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.primary,
                leadingIconContentColor = MaterialTheme.colorScheme.primary) else AssistChipDefaults.assistChipColors(),
        )
        DropdownMenu(open, { open = false }) {
            for (conn in servers) {
                Text(if (servers.size > 1) conn.server.name else stringResource(R.string.profile),
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownMenuItem(text = { Text(stringResource(R.string.no_profile)) }, onClick = { pick(conn, null) },
                    trailingIcon = { if (conn.activeProfile == null) Icon(Icons.Filled.Check, null) })
                for (p in conn.profiles) DropdownMenuItem(text = { Text(p.title) }, onClick = { pick(conn, p.name) },
                    trailingIcon = { if (conn.activeProfile == p.name) Icon(Icons.Filled.Check, null) })
            }
        }
    }
    error?.let { MessageDialog(stringResource(R.string.something_went_wrong), it) { error = null } }
}

// MARK: - Problems

/** What is wrong with a server, as Frigate's status bar shows it; tap for the whole list. */
@Composable
fun ServerProblemsBanner(conn: ServerConnection) {
    val problems = conn.problems
    if (problems.isEmpty()) return
    val nav = LocalNav.current
    val status = Palette.status
    val isError = problems.first().isError
    val tint = if (isError) MaterialTheme.colorScheme.error else status.warning
    var showing by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).clickable { showing = true }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, null, tint = tint)
        Spacer(Modifier.width(10.dp))
        Text(problems.first().text, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
        if (problems.size > 1) Text(stringResource(R.string.n_more, problems.size - 1), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showing) ModalBottomSheet(onDismissRequest = { showing = false }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(conn.server.name, style = MaterialTheme.typography.titleLarge)
            for (p in conn.problems) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (p.isError) Icons.Filled.Error else Icons.Filled.Warning, null, tint = if (p.isError) MaterialTheme.colorScheme.error else status.warning)
                Spacer(Modifier.width(12.dp))
                Text(p.text, style = MaterialTheme.typography.bodyLarge)
            }
            if (conn.problems.isEmpty()) Text(stringResource(R.string.no_problems_anymore))
            TextButton(onClick = { showing = false; nav.navigate(nl.wiebenieuwenhuis.tender.ui.settings.SystemStatusRoute(conn.id)) }) {
                Text(stringResource(R.string.system_status_and_logs))
            }
        }
    }
}

// MARK: - Recent alerts

private data class AlertEntry(val serverId: String, val item: ReviewItem)

/** Row of recent unreviewed alerts at the top of Live, like Frigate's live dashboard. */
@Composable
fun RecentAlertsStrip() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val entries = remember { mutableStateListOf<AlertEntry>() }
    val conns = AppModel.scopedConnections.filter { it.isOnline }
    val key = conns.joinToString { it.id }
    LaunchedEffect(key) {
        while (isActive) {
            val all = mutableListOf<AlertEntry>()
            for (c in conns) {
                val client = c.client ?: continue
                val items = runCatching { client.reviews(severity = Severity.Alert, reviewed = false, after = nowSeconds() - 86400, limit = 10) }.getOrNull() ?: continue
                all += items.map { AlertEntry(c.id, it) }
            }
            val fresh = all.filter { AppModel.reviewedIds[it.item.id] != true }.sortedByDescending { it.item.startTime }.take(10)
            entries.clear(); entries.addAll(fresh)
            delay(30_000)
        }
    }
    val actions = remember { nl.wiebenieuwenhuis.tender.ui.review.ReviewActions(scope) }
    actions.onDeleted = { e -> entries.removeAll { it.item.id == e.item.id } }
    val shown = entries.filter { AppModel.reviewedIds[it.item.id] != true }
    if (shown.isEmpty()) return
    nl.wiebenieuwenhuis.tender.ui.review.ReviewActionsHost(actions, Modifier.fillMaxWidth().height(84.dp)) {
        LazyRow(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.serverId + it.item.id }) { e ->
                ReviewMenuBox(e.serverId, e.item, actions, onClick = {
                    // Opens the recordings at the alert and marks it reviewed, as Frigate's dashboard does.
                    ItemCache.put(e.item)
                    nav.navigate(PlaybackRoute(e.serverId, e.item.camera, startOfDay(e.item.startTime), e.item.id))
                    val conn = AppModel.connection(e.serverId) ?: return@ReviewMenuBox
                    val client = conn.client ?: return@ReviewMenuBox
                    if (e.item.hasBeenReviewed) return@ReviewMenuBox
                    scope.launch {
                        if (runCatching { client.markViewed(listOf(e.item.id)) }.isFailure) return@launch
                        conn.noteReviewed(listOf(e.item))
                        delay(600)
                        AppModel.reviewedIds[e.item.id] = true
                        conn.refresh()
                    }
                }) { AlertPreviewTile(e.serverId, e.item) }
            }
        }
    }
}

@Composable
fun AlertPreviewTile(serverId: String, item: ReviewItem, modifier: Modifier = Modifier) {
    val client = AppModel.connection(serverId)?.client
    Box(modifier.size(150.dp, 84.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))) {
        RemoteImage(client?.thumbnailUrl(item), Modifier.fillMaxSize())
        if (AppModel.playAlertPreviews && !item.inProgress && client != null) LoopingVideo(client.reviewPreviewUrl(item), Modifier.fillMaxSize())
        Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) { item.labels.take(3).forEach { LabelIcon(it, size = 16.dp) } }
        Text(
            if (item.inProgress) "LIVE" else nl.wiebenieuwenhuis.tender.ui.components.relativeTime(item.startTime),
            Modifier.align(Alignment.BottomStart).padding(5.dp)
                .background(if (item.inProgress) Palette.status.recording else Color.Black.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}

/** Muted video that loops, shown only once it has a frame, so the picture below stays until then. */
@Composable
fun LoopingVideo(url: String, modifier: Modifier = Modifier) {
    val player = rememberVideoPlayer()
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(url) {
        player.volume = 0f
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { ready = true }
        })
        player.load(url)
    }
    VideoSurface(player, modifier.alpha(if (ready) 1f else 0f), fill = true)
}
