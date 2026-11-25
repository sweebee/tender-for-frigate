package nl.wiebenieuwenhuis.tender.ui.live

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.CameraConfig
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.nowSeconds
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ConnectionStatus
import nl.wiebenieuwenhuis.tender.app.Prefs
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.CameraRoute
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.PlaybackRoute
import nl.wiebenieuwenhuis.tender.ui.components.Chip
import nl.wiebenieuwenhuis.tender.ui.components.ConfirmDialog
import nl.wiebenieuwenhuis.tender.ui.components.DestructiveMenuItem
import nl.wiebenieuwenhuis.tender.ui.components.EmptyState
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.LucideIcon
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.ProgressButton
import nl.wiebenieuwenhuis.tender.ui.components.ServerBadge
import nl.wiebenieuwenhuis.tender.ui.components.onLongPress
import java.util.Calendar

/** The "Now active" chip: cameras where an object moves, across all servers. Frigate group names cannot hold a period, so it never clashes with one. */
private const val NOW_ACTIVE = ".active"

/** Columns on wide screens. */
private val ColumnChoices = listOf(2, 3, 4)

fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun startOfDay(epochSeconds: Double): Long = Calendar.getInstance().apply {
    timeInMillis = (epochSeconds * 1000).toLong()
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
fun LiveScreen() {
    val scope = rememberCoroutineScope()
    // Kept across sections and app launches.
    val group = AppModel.liveGroup
    fun setGroup(g: String?) = AppModel.updateLiveGroup(g)
    // When each camera ("<server id>|<camera>") last had a moving object, so it stays a while (as Birdseye does).
    val lastActive = remember { mutableStateMapOf<String, Double>() }
    var clock by remember { mutableStateOf(nowSeconds()) }
    var editingGroup by remember { mutableStateOf<GroupTarget?>(null) }
    var deletingGroup by remember { mutableStateOf<GroupTarget?>(null) }
    var busyGroup by remember { mutableStateOf<String?>(null) }
    var groupError by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val wide = nl.wiebenieuwenhuis.tender.ui.components.isWideLayout()
    var wideColumns by remember { mutableIntStateOf(Prefs.prefs.getInt("liveWideColumns", 3).takeIf { it in ColumnChoices } ?: 3) }
    val large = AppModel.largeTiles
    val columns = if (wide) wideColumns else if (large) 1 else 2

    val conns = AppModel.scopedConnections
    val movingNow: Set<String> = conns.flatMap { c -> c.activity.filter { it.value.tracking.isNotEmpty() }.keys.map { "${c.id}|$it" } }.toSet()
    LaunchedEffect(movingNow) { val now = nowSeconds(); movingNow.forEach { lastActive[it] = now } }
    // Lets a quiet camera leave "Now active" once its time is up.
    LaunchedEffect(Unit) { while (isActive) { delay(5000); clock = nowSeconds() } }
    // Keep object badges, status and counts fresh while the tab is visible.
    LaunchedEffect(Unit) { while (isActive) { delay(10_000); AppModel.refreshAll() } }

    fun isActive(conn: ServerConnection, cam: String): Boolean {
        val key = "${conn.id}|$cam"
        if (key in movingNow) return true
        val linger = conn.config?.inactivityThreshold ?: 30.0
        return lastActive[key]?.let { clock - it < linger } ?: false
    }
    fun cameras(conn: ServerConnection): List<CameraConfig> = when (group) {
        null -> conn.cameras
        NOW_ACTIVE -> conn.cameras.filter { isActive(conn, it.name) }
        // In the group's order, as set in the group editor.
        else -> conn.config?.cameraGroups?.get(group)?.cameras?.let { m ->
            val byName = conn.cameras.associateBy { it.name }
            m.distinct().mapNotNull { byName[it] }
        } ?: emptyList()
    }
    val groupNames = conns.flatMap { c -> c.config?.orderedGroups?.map { it.first } ?: emptyList() }.distinct()
    // Servers where this user may change camera groups (admins, as in Frigate).
    val groupAdmins = conns.filter { it.isOnline && it.config != null && it.isAdmin }
    // The group shown was removed (here or in Frigate): show all cameras again. Only once every server has its
    // config, so one still connecting doesn't drop it.
    val allLoaded = conns.isNotEmpty() && conns.all { it.config != null }
    LaunchedEffect(groupNames, allLoaded) {
        if (allLoaded && group != null && group != NOW_ACTIVE && group !in groupNames) setGroup(null)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_live)) },
            actions = {
                ProfileSwitcher()
                if (wide) {
                    SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 8.dp)) {
                        ColumnChoices.forEachIndexed { i, n ->
                            SegmentedButton(wideColumns == n, { wideColumns = n; Prefs.prefs.edit().putInt("liveWideColumns", n).apply() },
                                SegmentedButtonDefaults.itemShape(i, ColumnChoices.size), icon = {}) { Text("$n") }
                        }
                    }
                } else {
                    IconButton(onClick = { AppModel.updateLargeTiles(!large) }) {
                        Icon(if (large) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda,
                            stringResource(if (large) R.string.small_tiles else R.string.large_tiles))
                    }
                }
            },
        )
    }) { pad ->
        PullToRefreshBox(refreshing, onRefresh = {
            scope.launch { refreshing = true; AppModel.refreshAll(reloadConfig = true); refreshing = false }
        }, Modifier.padding(pad).fillMaxSize()) {
            LazyVerticalGrid(
                GridCells.Fixed(columns), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (AppModel.showRecentAlerts) item(span = { GridItemSpan(maxLineSpan) }, key = "alerts") { RecentAlertsStrip() }
                if (conns.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "groups") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        item { Chip(stringResource(R.string.all), group == null, { setGroup(null) }) }
                        item {
                            Chip(stringResource(R.string.now_active), group == NOW_ACTIVE, { setGroup(NOW_ACTIVE) },
                                icon = { Icon(Icons.AutoMirrored.Filled.DirectionsRun, null, Modifier.size(16.dp)) })
                        }
                        items(groupNames, key = { it }) { name ->
                            GroupChip(name, selected = group == name, busy = busyGroup == name, icon = groupIcon(conns, name),
                                canEdit = groupAdmins.any { it.config?.cameraGroups?.containsKey(name) == true },
                                onClick = { setGroup(name) },
                                onEdit = { editingGroup = GroupTarget(name, groupAdmins.map { it.id }) },
                                onDelete = { deletingGroup = GroupTarget(name, groupAdmins.map { it.id }) })
                        }
                        if (groupAdmins.isNotEmpty()) item {
                            IconButton(onClick = { editingGroup = GroupTarget(null, groupAdmins.map { it.id }) }) {
                                Icon(Icons.Filled.Add, stringResource(R.string.new_group), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                val shown = if (group == null) conns else conns.filter { cameras(it).isNotEmpty() }
                if (conns.size > 1 && shown.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        if (group == NOW_ACTIVE) EmptyState(Icons.AutoMirrored.Filled.DirectionsRun, stringResource(R.string.nothing_moving), stringResource(R.string.nothing_moving_info))
                        else EmptyState(Icons.Outlined.VideocamOff, stringResource(R.string.no_cameras), stringResource(R.string.group_has_no_cameras))
                    }
                }
                for (conn in if (conns.size == 1) conns else shown) {
                    if (conns.size > 1) item(span = { GridItemSpan(maxLineSpan) }, key = "h-${conn.id}") {
                        Row(Modifier.padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            ServerBadge(conn.server, 20.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(conn.server.name.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text(conn.metaLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    val status = conn.status
                    when {
                        conn.config == null && status is ConnectionStatus.Offline ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "o-${conn.id}") { OfflineCard(conn, status.message) }
                        conn.config == null ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "l-${conn.id}") { LoadingBox() }
                        else -> {
                            // Only when something is wrong; nothing at all when Frigate is fine.
                            if (conn.problems.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "p-${conn.id}") { ServerProblemsBanner(conn) }
                            val cams = cameras(conn)
                            if (cams.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "e-${conn.id}") {
                                if (group == NOW_ACTIVE && conns.size == 1)
                                    EmptyState(Icons.AutoMirrored.Filled.DirectionsRun, stringResource(R.string.nothing_moving), stringResource(R.string.nothing_moving_info))
                                else EmptyState(Icons.Outlined.VideocamOff, stringResource(R.string.no_cameras),
                                    if (group == null) null else stringResource(R.string.group_has_no_cameras_on, conn.server.name))
                            }
                            if (AppModel.tileWholePicture && columns > 1) {
                                // Frigate's dashboard shapes, in bands of rows so the grid stays lazy.
                                val cells = placeTiles(cams.map { it.tileSpan }, columns)
                                for (band in tileBands(cells)) {
                                    val top = cells[band.first()].row
                                    item(span = { GridItemSpan(maxLineSpan) }, key = "b-${conn.id}|${cams[band.first()].name}") {
                                        TileBand(columns, 10.dp, 12.dp, band.map { cells[it].copy(row = cells[it].row - top) }) {
                                            for (i in band) TileWithMenu(conn, cams[i], wholePicture = false, fill = true)
                                        }
                                    }
                                }
                            } else {
                                items(cams, key = { "${conn.id}|${it.name}" }) { cam -> TileWithMenu(conn, cam, wholePicture = large && !wide) }
                            }
                        }
                    }
                }
            }
        }
    }

    editingGroup?.let { t ->
        CameraGroupEditor(t, onDismiss = { editingGroup = null }) { newName ->
            if (t.name != null && group == t.name) setGroup(newName)
        }
    }
    deletingGroup?.let { t ->
        ConfirmDialog(
            title = stringResource(R.string.delete_named, Format.prettyName(t.name ?: "")),
            message = stringResource(R.string.delete_group_message),
            confirmText = stringResource(R.string.delete),
            onConfirm = {
                val name = t.name ?: return@ConfirmDialog
                scope.launch {
                    busyGroup = name
                    try {
                        for (id in t.serverIds) {
                            val conn = AppModel.connection(id) ?: continue
                            val client = conn.client ?: continue
                            if (conn.config?.cameraGroups?.containsKey(name) != true) continue
                            try {
                                client.setConfig(mapOf("camera_groups" to mapOf(name to "")), false, null)
                                conn.refresh(reloadConfig = true)
                            } catch (e: Exception) {
                                groupError = "${conn.server.name}: ${e.message}"; return@launch
                            }
                        }
                    } finally { busyGroup = null }
                }
            },
            onDismiss = { deletingGroup = null },
        )
    }
    groupError?.let { MessageDialog(stringResource(R.string.not_saved), it) { groupError = null } }
}

private fun groupIcon(conns: List<ServerConnection>, name: String): String? =
    conns.firstNotNullOfOrNull { it.config?.cameraGroups?.get(name)?.icon }

@Composable
private fun GroupChip(name: String, selected: Boolean, busy: Boolean, icon: String?, canEdit: Boolean,
                      onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Chip(Format.prettyName(name), selected, onClick = onClick, busy = busy,
            modifier = Modifier.onLongPress(canEdit) { menu = true },
            icon = icon?.let { { LucideIcon(it, size = 16.dp) } })
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = { menu = false; onEdit() })
            DestructiveMenuItem(stringResource(R.string.delete), Icons.Outlined.DeleteOutline) { menu = false; onDelete() }
        }
    }
}

/** A tile that opens the camera; a long press offers live, playback and Frigate's switches. */
@Composable
private fun TileWithMenu(conn: ServerConnection, cam: CameraConfig, wholePicture: Boolean, fill: Boolean = false) {
    val nav = LocalNav.current
    var menu by remember { mutableStateOf(false) }
    Box {
        CameraTile(conn, cam, Modifier.combinedClickable(
            onClick = { nav.navigate(CameraRoute(conn.id, cam.name)) },
            onLongClick = { menu = true },
        ), wholePicture = wholePicture, fill = fill)
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.open_live)) }, onClick = { menu = false; nav.navigate(CameraRoute(conn.id, cam.name)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.playback)) }, onClick = {
                menu = false; nav.navigate(PlaybackRoute(conn.id, cam.name, startOfToday()))
            })
            CameraSwitchItems(conn, cam) { menu = false }
        }
    }
}

@Composable
private fun OfflineCard(conn: ServerConnection, message: String) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.server_is_offline, conn.server.name), style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProgressButton(stringResource(R.string.try_again), busy, { scope.launch { busy = true; conn.connect(); busy = false } }, filled = false)
        }
    }
}
