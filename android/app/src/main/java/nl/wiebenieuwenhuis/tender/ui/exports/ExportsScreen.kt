package nl.wiebenieuwenhuis.tender.ui.exports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateException
import nl.wiebenieuwenhuis.tender.api.toDate
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.ExportPlayerRoute
import nl.wiebenieuwenhuis.tender.ui.ExportsRoute
import nl.wiebenieuwenhuis.tender.ui.ItemCache
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.components.ConfirmDialog
import nl.wiebenieuwenhuis.tender.ui.components.DestructiveMenuItem
import nl.wiebenieuwenhuis.tender.ui.components.EmptyState
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.RemoteImage
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.ServerBadge
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.components.TextPromptDialog

/** Exports registers no private routes: a case is an [ExportsRoute] with a server and case id. */
fun NavGraphBuilder.exportsDestinations() {}

/** Frigate's Exports: clips kept on the servers, with their cases as folders (Frigate 0.18). */
@Composable
fun ExportsScreen(route: ExportsRoute, showsBack: Boolean = true) {
    val nav = LocalNav.current
    val vm: ExportsViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val caseKey = if (route.serverId != null && route.caseId != null) "${route.serverId}/${route.caseId}" else null
    val cases = vm.cases
    val currentCase = caseKey?.let { k -> cases.firstOrNull { it.key == k } ?: ExportsState.knownCases[k] }
    val knownCaseKeys = cases.map { it.key }.toSet()
    val visible = vm.entries.filter { AppModel.deletedExports[it.export.id] != true }.filter { e ->
        val caseId = ExportsState.caseId(e)
        if (caseKey != null) e.serverId == route.serverId && caseId == route.caseId
        // Outside a case (or in one this app does not know): the cases show as folders above.
        else caseId == null || "${e.serverId}/$caseId" !in knownCaseKeys
    }

    var renaming by remember { mutableStateOf<ExportEntry?>(null) }
    var deleting by remember { mutableStateOf<ExportEntry?>(null) }
    var creatingCaseOn by remember { mutableStateOf<String?>(null) }
    var creatingCaseFor by remember { mutableStateOf<ExportEntry?>(null) }
    var renamingCase by remember { mutableStateOf<ExportCaseEntry?>(null) }
    var deletingCase by remember { mutableStateOf<ExportCaseEntry?>(null) }

    // While an export is being made, look again until it is done.
    LaunchedEffect(Unit) {
        while (isActive) {
            runCatching { vm.load() }
            delay(if (vm.entries.any { it.export.inProgress }) 3_000 else 30_000)
        }
    }

    fun reload() { AppModel.scope.launch { runCatching { vm.load() } } }

    fun move(entry: ExportEntry, caseId: String?) {
        val client = AppModel.connection(entry.serverId)?.client ?: return
        // Shown in its new place at once; the server follows.
        ExportsState.movedExports[entry.key] = caseId ?: ""
        AppModel.scope.launch {
            try { client.moveExports(listOf(entry.export.id), caseId) } catch (e: Exception) {
                ExportsState.movedExports.remove(entry.key)
                vm.errorMessage = e.message ?: e.toString()
            }
            runCatching { vm.load() }
        }
    }

    fun createCase(serverId: String, name: String, forExport: ExportEntry?) {
        val client = AppModel.connection(serverId)?.client ?: return
        vm.caseWork++
        AppModel.scope.launch {
            try {
                val created = client.createExportCase(name)
                // There at once; the full reload of all exports takes a while.
                val entry = ExportCaseEntry(serverId, created)
                ExportsState.addedCases.add(entry)
                ExportsState.knownCases[entry.key] = entry
                if (forExport != null) move(forExport, created.id) else runCatching { vm.load() }
            } catch (e: Exception) {
                vm.errorMessage = e.message ?: e.toString()
            } finally {
                vm.caseWork--
            }
        }
    }

    fun renameCase(c: ExportCaseEntry, name: String) {
        val client = AppModel.connection(c.serverId)?.client ?: return
        ExportsState.renamedCases[c.key] = name
        vm.caseWork++
        AppModel.scope.launch {
            try { client.renameExportCase(c.exportCase.id, name) } catch (e: Exception) {
                ExportsState.renamedCases.remove(c.key)
                vm.errorMessage = e.message ?: e.toString()
            } finally {
                vm.caseWork--
            }
            runCatching { vm.load() }
        }
    }

    fun deleteCase(c: ExportCaseEntry, withExports: Boolean) {
        val client = AppModel.connection(c.serverId)?.client ?: return
        val inside = vm.exportsIn(c)
        // Made here earlier: it is no longer new either, or it would come back.
        val added = ExportsState.addedCases.firstOrNull { it.key == c.key }
        // Gone at once, here and in the other exports screens; its exports go with it or show
        // outside a case. Inside the case that is gone: back to all exports.
        ExportsState.deletedCases[c.key] = true
        ExportsState.addedCases.removeAll { it.key == c.key }
        if (withExports) inside.forEach { AppModel.deletedExports[it.export.id] = true }
        else inside.forEach { ExportsState.movedExports[it.key] = "" }
        if (caseKey != null) nav.popBackStack()
        vm.caseWork++
        AppModel.scope.launch {
            try {
                client.deleteExportCase(c.exportCase.id, deletingExports = withExports)
            } catch (e: FrigateException.Http) {
                // Already gone (deleted elsewhere, or twice): that is what was asked.
                if (e.code != 404) undoDelete(c, added, inside, withExports, vm, e)
            } catch (e: Exception) {
                undoDelete(c, added, inside, withExports, vm, e)
            } finally {
                vm.caseWork--
            }
            runCatching { vm.load() }
        }
    }

    ScreenScaffold(
        title = currentCase?.let { it.exportCase.name.ifEmpty { stringResource(R.string.exports_case) } } ?: stringResource(R.string.exports),
        showsBack = caseKey != null || showsBack,
        actions = {
            when {
                // A case is made, renamed or deleted on the server: the spinner takes the button's place.
                vm.caseWork > 0 -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { SmallSpinner() }
                currentCase != null -> CaseMenuButton(currentCase, onRename = { renamingCase = it }, onDelete = { deletingCase = it })
                vm.caseServers.size == 1 -> IconButton(onClick = { creatingCaseOn = vm.caseServers.first().id; creatingCaseFor = null }) {
                    Icon(Icons.Outlined.CreateNewFolder, stringResource(R.string.exports_new_case))
                }
                vm.caseServers.size > 1 -> {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.CreateNewFolder, stringResource(R.string.exports_new_case)) }
                        DropdownMenu(open, { open = false }) {
                            Text(stringResource(R.string.exports_new_case_on), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            for (c in vm.caseServers) DropdownMenuItem(
                                text = { Text(c.server.name, maxLines = 1) }, leadingIcon = { ServerBadge(c.server) },
                                onClick = { open = false; creatingCaseOn = c.id; creatingCaseFor = null },
                            )
                        }
                    }
                }
            }
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.refreshing = true; scope.launch { runCatching { vm.load() }; vm.refreshing = false } },
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 600.dp
                LazyVerticalGrid(
                    GridCells.Adaptive(if (wide) 280.dp else 160.dp), Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (vm.loading && vm.entries.isEmpty()) {
                        fullWidth { LoadingBox(Modifier.padding(top = 60.dp)) }
                        return@LazyVerticalGrid
                    }
                    if (caseKey == null && cases.isNotEmpty()) {
                        fullWidth { SectionLabel(stringResource(R.string.exports_cases)) }
                        items(cases, key = { "case/" + it.key }) { c ->
                            ExportCaseTile(
                                c, vm.exportsIn(c), serverName = if (vm.caseServers.size > 1) AppModel.connection(c.serverId)?.server?.name else null,
                                onOpen = { nav.navigate(ExportsRoute(c.serverId, c.exportCase.id)) },
                                onRename = { renamingCase = it }, onDelete = { deletingCase = it },
                            )
                        }
                        if (visible.isNotEmpty()) fullWidth { SectionLabel(stringResource(R.string.exports_not_in_case), Modifier.padding(top = 8.dp)) }
                    }
                    if (visible.isEmpty() && (caseKey != null || cases.isEmpty())) {
                        fullWidth {
                            if (caseKey == null) EmptyState(Icons.Outlined.MovieFilter, stringResource(R.string.exports_none), stringResource(R.string.exports_none_message))
                            else EmptyState(Icons.Outlined.Folder, stringResource(R.string.exports_none_in_case), stringResource(R.string.exports_none_in_case_message))
                        }
                    } else {
                        items(visible, key = { it.key }) { entry ->
                            ExportTileWithMenu(
                                entry, cases,
                                onOpen = { ItemCache.put(entry.export); nav.navigate(ExportPlayerRoute(entry.serverId, entry.export.id)) },
                                onRename = { renaming = it },
                                onMove = { e, caseId -> move(e, caseId) },
                                onNewCase = { creatingCaseFor = it; creatingCaseOn = it.serverId },
                                onDelete = { deleting = it },
                            )
                        }
                    }
                }
            }
        }
    }

    renaming?.let { entry ->
        TextPromptDialog(
            title = stringResource(R.string.exports_rename_export), initial = entry.export.name, confirmText = stringResource(R.string.rename),
            label = stringResource(R.string.exports_name),
            onConfirm = { name ->
                AppModel.scope.launch {
                    try { ExportActions.rename(entry, name); vm.load() } catch (e: Exception) { vm.errorMessage = e.message ?: e.toString() }
                }
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.exports_delete_title), message = stringResource(R.string.exports_delete_message),
            confirmText = stringResource(R.string.delete),
            onConfirm = {
                AppModel.scope.launch {
                    try { ExportActions.delete(entry); vm.load() } catch (e: Exception) { vm.errorMessage = e.message ?: e.toString() }
                }
            },
            onDismiss = { deleting = null },
        )
    }
    creatingCaseOn?.let { serverId ->
        val forExport = creatingCaseFor
        TextPromptDialog(
            title = stringResource(R.string.exports_new_case), initial = "", confirmText = stringResource(R.string.exports_create),
            label = stringResource(R.string.exports_case_name),
            message = forExport?.let { stringResource(R.string.exports_goes_into_it, it.export.name.ifEmpty { stringResource(R.string.exports_the_export) }) },
            onConfirm = { name -> createCase(serverId, name, forExport) },
            onDismiss = { creatingCaseOn = null; creatingCaseFor = null },
        )
    }
    renamingCase?.let { c ->
        TextPromptDialog(
            title = stringResource(R.string.exports_rename_case), initial = c.exportCase.name, confirmText = stringResource(R.string.rename),
            label = stringResource(R.string.exports_case_name),
            onConfirm = { name -> renameCase(c, name) },
            onDismiss = { renamingCase = null },
        )
    }
    deletingCase?.let { c ->
        val n = vm.exportsIn(c).size
        AlertDialog(
            onDismissRequest = { deletingCase = null },
            title = { Text(stringResource(R.string.exports_delete_case_title)) },
            text = {
                Text(if (n == 0) stringResource(R.string.exports_case_empty) else pluralStringResource(R.plurals.exports_case_keeps, n, n))
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (n > 0) {
                        TextButton(onClick = { deletingCase = null; deleteCase(c, false) }) {
                            Text(stringResource(R.string.exports_delete_case_only), maxLines = 1)
                        }
                    }
                    TextButton(onClick = { deletingCase = null; deleteCase(c, n > 0) }) {
                        Text(stringResource(if (n > 0) R.string.exports_delete_case_and_exports else R.string.exports_delete_case),
                            color = MaterialTheme.colorScheme.error, maxLines = 1)
                    }
                    TextButton(onClick = { deletingCase = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
    vm.errorMessage?.let { MessageDialog(stringResource(R.string.exports_something_wrong), it) { vm.errorMessage = null } }
}

private fun undoDelete(c: ExportCaseEntry, added: ExportCaseEntry?, inside: List<ExportEntry>, withExports: Boolean, vm: ExportsViewModel, e: Exception) {
    ExportsState.deletedCases.remove(c.key)
    if (added != null) ExportsState.addedCases.add(added)
    if (withExports) inside.forEach { AppModel.deletedExports.remove(it.export.id) }
    else inside.forEach { ExportsState.movedExports.remove(it.key) }
    vm.errorMessage = e.message ?: e.toString()
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun CaseMenuButton(c: ExportCaseEntry, onRename: (ExportCaseEntry) -> Unit, onDelete: (ExportCaseEntry) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.exports_more_options)) }
        DropdownMenu(open, { open = false }) { CaseMenuItems(c, { open = false }, onRename, onDelete) }
    }
}

@Composable
private fun CaseMenuItems(c: ExportCaseEntry, close: () -> Unit, onRename: (ExportCaseEntry) -> Unit, onDelete: (ExportCaseEntry) -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.exports_rename_case)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
        onClick = { close(); onRename(c) },
    )
    HorizontalDivider()
    DestructiveMenuItem(stringResource(R.string.exports_delete_case), Icons.Outlined.Delete) { close(); onDelete(c) }
}

// MARK: - Tiles

/** A picture area of a tile: 16:9, rounded, black behind the picture. */
@Composable
private fun TilePicture(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
        content = content,
    )
}

/** An export as a 16:9 picture with its name, camera and date; long-press for its menu. */
@Composable
private fun ExportTileWithMenu(
    entry: ExportEntry,
    cases: List<ExportCaseEntry>,
    onOpen: () -> Unit,
    onRename: (ExportEntry) -> Unit,
    onMove: (ExportEntry, String?) -> Unit,
    onNewCase: (ExportEntry) -> Unit,
    onDelete: (ExportEntry) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var moveMenu by remember { mutableStateOf(false) }
    val conn = AppModel.connection(entry.serverId)
    val export = entry.export
    val busy = AppModel.busyExports[export.id] == true
    val caseId = ExportsState.caseId(entry)
    Box {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .combinedClickable(enabled = !busy, onClick = { if (!export.inProgress) onOpen() }, onLongClick = { menu = true }),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TilePicture {
                if (export.inProgress) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                        Text(stringResource(R.string.exports_exporting), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    RemoteImage(conn?.client?.exportThumbnailUrl(export), Modifier.matchParentSize())
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(6.dp).size(24.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(15.dp), tint = Color.White) }
                }
                // Renamed or deleted on the server right now.
                if (busy) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                    }
                }
            }
            Text(export.name.ifEmpty { stringResource(R.string.exports_export) }, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (AppModel.orderedConnections.size > 1 && conn != null) { ServerBadge(conn.server, size = 16.dp); Spacer(Modifier.width(5.dp)) }
                val cam = conn?.camera(export.camera)?.displayName ?: Format.prettyName(export.camera)
                val date = export.date.toDate()
                Text("$cam · ${Format.dayTitle(date)} ${Format.time(date)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = { menu = false; onRename(entry) },
            )
            if (conn?.client?.servesCases == true) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.exports_move_to_case)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) },
                    onClick = { menu = false; moveMenu = true },
                )
            }
            HorizontalDivider()
            DestructiveMenuItem(stringResource(R.string.delete), Icons.Outlined.Delete) { menu = false; onDelete(entry) }
        }
        DropdownMenu(moveMenu, { moveMenu = false }) {
            for (c in cases.filter { it.serverId == entry.serverId && it.exportCase.id != caseId }) {
                DropdownMenuItem(
                    text = { Text(c.exportCase.name.ifEmpty { stringResource(R.string.exports_case) }, maxLines = 1) },
                    leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                    onClick = { moveMenu = false; onMove(entry, c.exportCase.id) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.exports_new_case_ellipsis)) }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                onClick = { moveMenu = false; onNewCase(entry) },
            )
            if (caseId != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.exports_remove_from_case)) }, leadingIcon = { Icon(Icons.Outlined.Outbox, null) },
                    onClick = { moveMenu = false; onMove(entry, null) },
                )
            }
        }
    }
}

/** A case as a folder: the newest export's picture, its name and how many exports it holds; long-press for its menu. */
@Composable
private fun ExportCaseTile(
    entry: ExportCaseEntry,
    exports: List<ExportEntry>,
    serverName: String?,
    onOpen: () -> Unit,
    onRename: (ExportCaseEntry) -> Unit,
    onDelete: (ExportCaseEntry) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val conn = AppModel.connection(entry.serverId)
    Box {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TilePicture {
                exports.firstOrNull { !it.export.inProgress }?.let { first ->
                    RemoteImage(conn?.client?.exportThumbnailUrl(first.export), Modifier.matchParentSize().alpha(0.55f))
                }
                Icon(Icons.Filled.Folder, null, Modifier.align(Alignment.Center).size(34.dp),
                    tint = Color.White.copy(alpha = 0.9f))
            }
            Text(entry.exportCase.name.ifEmpty { stringResource(R.string.exports_case) }, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(pluralStringResource(R.plurals.exports_n_exports, exports.size, exports.size), serverName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        DropdownMenu(menu, { menu = false }) { CaseMenuItems(entry, { menu = false }, onRename, onDelete) }
    }
}
