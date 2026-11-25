package nl.wiebenieuwenhuis.tender.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.components.LucideIcon
import nl.wiebenieuwenhuis.tender.ui.components.LucideIcons
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.ProgressButton
import nl.wiebenieuwenhuis.tender.ui.components.RowDivider
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.ServerBadge
import nl.wiebenieuwenhuis.tender.ui.components.SwitchRow
import nl.wiebenieuwenhuis.tender.ui.components.readableWidth

/**
 * What the editor works on: a new group (`name` null), or an existing one by name, which is one
 * group in the app whichever servers have it. `serverIds`: the servers it may change (admin).
 */
data class GroupTarget(val name: String?, val serverIds: List<String>)

/**
 * Adding or editing a camera group, as Frigate's group editor in Live: a name, an icon and the
 * cameras in it, from all your servers at once. Frigate keeps groups per server, so on saving
 * every server with cameras chosen gets a group of this name and icon with its own cameras, and
 * a server left without cameras loses it. Saved at once (no restart).
 */
@Composable
fun CameraGroupEditor(target: GroupTarget, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val conns = target.serverIds.mapNotNull { AppModel.connection(it) }.filter { it.config != null }
    var name by remember { mutableStateOf(target.name ?: "") }
    var icon by remember { mutableStateOf(target.name?.let { n -> conns.firstNotNullOfOrNull { it.config?.cameraGroups?.get(n)?.icon } } ?: "LuHouse") }
    val chosen = remember {
        mutableStateMapOf<String, Set<String>>().apply {
            target.name?.let { n -> conns.forEach { c -> c.config?.cameraGroups?.get(n)?.let { put(c.id, it.cameras.toSet()) } } }
        }
    }
    // Every camera per server in the order of the list: the group's order, then the others in
    // Frigate's order. The chosen ones are saved in this order.
    val order = remember {
        mutableStateMapOf<String, List<String>>().apply {
            conns.forEach { c ->
                val all = c.config?.orderedCameras?.map { it.name } ?: emptyList()
                val members = target.name?.let { c.config?.cameraGroups?.get(it)?.cameras } ?: emptyList()
                put(c.id, members.filter { it in all }.distinct() + all.filter { it !in members })
            }
        }
    }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeight = 56.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickingIcon by remember { mutableStateOf(false) }
    val nameFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (target.name == null) runCatching { nameFocus.requestFocus() } }
    val trimmed = name.trim()
    val chosenCount = chosen.values.sumOf { it.size }
    // As Frigate's form allows: a name without a dot, not "default", not taken by another group.
    val nameProblem = when {
        trimmed.isEmpty() -> null
        trimmed.contains('.') -> stringResource(R.string.group_name_period)
        trimmed.lowercase() == "default" -> stringResource(R.string.group_name_reserved)
        trimmed != target.name && conns.any { it.config?.cameraGroups?.containsKey(trimmed) == true } -> stringResource(R.string.group_name_taken)
        else -> null
    }
    val canSave = trimmed.isNotEmpty() && nameProblem == null && chosenCount > 0 && !saving

    fun save() = scope.launch {
        saving = true
        try {
            for (conn in conns) {
                val client = conn.client ?: continue
                val existing = conn.config?.cameraGroups ?: emptyMap()
                val had = target.name?.let { existing[it] }
                val set = chosen[conn.id] ?: emptySet()
                val all = order[conn.id] ?: emptyList()
                // The order of the list (Tender shows the group in it), then any member the app
                // does not list, kept as it was.
                val members = all.filter { it in set } + (had?.cameras ?: set.sorted()).filter { it in set && it !in all }
                val groups = mutableMapOf<String, Any?>()
                if (members.isNotEmpty()) {
                    // A group new on this server goes last, as in Frigate; an existing one keeps its place.
                    val order = had?.order ?: ((existing.values.mapNotNull { it.order }.maxOrNull() ?: existing.size) + 1)
                    groups[trimmed] = mapOf("order" to order, "icon" to icon, "cameras" to members)
                }
                // Renamed, or no cameras left here: the old key goes, as Frigate's editor does it.
                val old = target.name
                if (old != null && had != null && (old != trimmed || members.isEmpty())) groups[old] = ""
                if (groups.isEmpty()) continue
                try {
                    client.setConfig(mapOf("camera_groups" to groups), requiresRestart = false, updateTopic = null)
                    conn.refresh(reloadConfig = true)
                } catch (e: Exception) {
                    error = "${conn.server.name}: ${e.message}"; return@launch
                }
            }
            onDone(trimmed)
            onDismiss()
        } finally { saving = false }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(stringResource(if (target.name == null) R.string.new_group else R.string.edit_group)) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) } },
                actions = { ProgressButton(stringResource(R.string.save), saving, { save() }, Modifier.padding(end = 8.dp), enabled = canSave) },
            )
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.readableWidth(560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().focusRequester(nameFocus), label = { Text(stringResource(R.string.name)) }, singleLine = true,
                        isError = nameProblem != null, supportingText = nameProblem?.let { { Text(it) } })
                    SectionLabel(stringResource(R.string.icon), Modifier.padding(top = 10.dp))
                    SectionCard {
                        Row(Modifier.fillMaxWidth().clickable { pickingIcon = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center) { LucideIcon(icon, size = 22.dp, tint = MaterialTheme.colorScheme.primary) }
                            Spacer(Modifier.width(12.dp))
                            Text(LucideIcons.words(icon).replaceFirstChar { it.uppercase() }, Modifier.weight(1f))
                            Text(stringResource(R.string.change), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    for (conn in conns) {
                        if (conns.size > 1) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ServerBadge(conn.server); Spacer(Modifier.width(8.dp)); SectionLabel(conn.server.name)
                        } else SectionLabel(stringResource(R.string.cameras), Modifier.padding(top = 10.dp))
                        SectionCard {
                            val byName = conn.config?.orderedCameras?.associateBy { it.name } ?: emptyMap()
                            val names = order[conn.id] ?: emptyList()
                            Column {
                                names.forEachIndexed { i, camName ->
                                    val cam = byName[camName] ?: return@forEachIndexed
                                    key(conn.id, camName) {
                                        val dragKey = "${conn.id}/$camName"
                                        val isDragging = dragging == dragKey
                                        if (i > 0 && !isDragging) RowDivider()
                                        val set = chosen[conn.id] ?: emptySet()
                                        Row(
                                            Modifier.fillMaxWidth().height(rowHeight).zIndex(if (isDragging) 1f else 0f)
                                                .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                                                .background(if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // Drag the row up or down by its handle; it takes the place of the rows it passes.
                                            Box(
                                                Modifier.size(48.dp).pointerInput(dragKey) {
                                                    detectDragGestures(
                                                        onDragStart = { dragging = dragKey; dragOffset = 0f },
                                                        onDragEnd = { dragging = null; dragOffset = 0f },
                                                        onDragCancel = { dragging = null; dragOffset = 0f },
                                                    ) { change, amount ->
                                                        change.consume()
                                                        dragOffset += amount.y
                                                        val list = order[conn.id]?.toMutableList() ?: return@detectDragGestures
                                                        val at = list.indexOf(camName)
                                                        if (dragOffset > rowPx / 2 && at in 0 until list.lastIndex) {
                                                            list.add(at + 1, list.removeAt(at)); order[conn.id] = list; dragOffset -= rowPx
                                                        } else if (dragOffset < -rowPx / 2 && at > 0) {
                                                            list.add(at - 1, list.removeAt(at)); order[conn.id] = list; dragOffset += rowPx
                                                        }
                                                    }
                                                },
                                                contentAlignment = Alignment.Center,
                                            ) { Icon(Icons.Outlined.DragHandle, stringResource(R.string.settings_drag_to_reorder), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            SwitchRow(cam.displayName, cam.name in set, { on -> chosen[conn.id] = if (on) set + cam.name else set - cam.name },
                                                Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (pickingIcon) LucideIconPicker(icon, onDismiss = { pickingIcon = false }) { icon = it; pickingIcon = false }
    error?.let { MessageDialog(L(R.string.not_saved), it) { error = null } }
}

/** All of Frigate's group icons, searchable by name as in Frigate's icon picker. */
@Composable
fun LucideIconPicker(selection: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.icon)) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) } })
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_icons)) }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.clear_search)) } })
                val q = query.trim().lowercase()
                val compact = q.replace(" ", "")
                val list = if (q.isEmpty()) LucideIcons.names else LucideIcons.names.filter { it.lowercase().contains(compact) || LucideIcons.words(it).contains(q) }
                if (list.isEmpty()) Text(stringResource(R.string.no_icons_found), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyVerticalGrid(GridCells.Adaptive(52.dp), Modifier.fillMaxSize().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it }) { n ->
                        val on = n == selection
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer)
                                .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                .clickable { onPick(n) },
                            contentAlignment = Alignment.Center,
                        ) { LucideIcon(n, size = 22.dp, tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                    }
                }
            }
        }
    }
}
