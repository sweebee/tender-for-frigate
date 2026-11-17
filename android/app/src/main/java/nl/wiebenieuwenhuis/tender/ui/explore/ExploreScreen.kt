package nl.wiebenieuwenhuis.tender.ui.explore

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateEvent
import nl.wiebenieuwenhuis.tender.api.SearchSource
import nl.wiebenieuwenhuis.tender.api.toDate
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.ItemCache
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.ObjectRoute
import nl.wiebenieuwenhuis.tender.ui.components.EmptyState
import nl.wiebenieuwenhuis.tender.ui.components.LabelIcon
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.MonoDigits
import nl.wiebenieuwenhuis.tender.ui.components.OverlayChip
import nl.wiebenieuwenhuis.tender.ui.components.RemoteImage
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.ServerBadge
import nl.wiebenieuwenhuis.tender.ui.components.relativeTime
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Explore opened on "Find similar" from an object's screen: the semantic search for objects that
 * look like `similarTo` on that server. Put the event in [ItemCache] before navigating.
 */
@Serializable data class ExploreSearchRoute(val serverId: String, val similarTo: String)

/** The Explore tab: tracked objects per label, search, plates. */
@Composable
fun ExploreScreen() = ExploreContent(similar = null, showsBack = false)

@Composable
private fun ExploreSearchScreen(route: ExploreSearchRoute) {
    var event by remember { mutableStateOf(ItemCache.events[route.similarTo]) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(route) {
        if (event != null) return@LaunchedEffect
        val client = AppModel.connection(route.serverId)?.client ?: return@LaunchedEffect
        runCatching { client.event(route.similarTo) }.onSuccess { event = ItemCache.put(it) }.onFailure { failed = it.message }
    }
    val e = event
    if (e == null) {
        ScreenScaffold(stringResource(R.string.tab_explore)) { pad ->
            Box(Modifier.padding(pad)) {
                if (failed != null) EmptyState(Icons.Outlined.WarningAmber, stringResource(R.string.explore_could_not_load), failed)
                else LoadingBox()
            }
        }
    } else {
        ExploreContent(ExploreEntry(route.serverId, e), showsBack = true)
    }
}

fun NavGraphBuilder.exploreDestinations() {
    composable<ExploreSearchRoute> { ExploreSearchScreen(it.toRoute()) }
}

// MARK: - Screen

@Composable
private fun ExploreContent(similar: ExploreEntry?, showsBack: Boolean) {
    val vm: ExploreViewModel = viewModel()
    remember(vm) { vm.start(similar); true }
    val connectionIds = vm.connections.map { it.id }
    val loadKey = vm.loadKey()
    LaunchedEffect(loadKey) { vm.load(loadKey) }
    LaunchedEffect(connectionIds) { vm.loadOptions() }

    ScreenScaffold(
        stringResource(R.string.tab_explore), showsBack = showsBack,
        actions = {
            IconButton(onClick = { ExplorePrefs.toggleGrid() }) {
                if (ExplorePrefs.gridByDefault) Icon(Icons.Outlined.ViewAgenda, stringResource(R.string.explore_per_label))
                else Icon(Icons.Outlined.Apps, stringResource(R.string.explore_all_in_grid))
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Search and filters stay put; only the results scroll.
            ExploreHeader(vm)
            PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                ExploreResults(vm)
            }
        }
    }
}

// MARK: - Search and filters

@Composable
private fun ExploreHeader(vm: ExploreViewModel) {
    val connections = vm.connections
    val canSearchText = connections.any { it.config?.semanticSearch == true }
    // Plate search: a server recognizes plates now and has found some.
    val canSearchPlates = vm.knownPlates.isNotEmpty() && connections.any { it.config?.recognizesPlates == true }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val filter = vm.filter

    fun submit() {
        val q = vm.searchText.trim()
        // A new text search ends a similarity search, as in Frigate.
        vm.filter = if (canSearchText) vm.filter.copy(query = q, similar = if (q.isNotEmpty()) null else vm.filter.similar)
        else vm.filter.copy(plate = q)
        focus.clearFocus()
    }

    // Plates only: search a moment after typing stops.
    LaunchedEffect(vm.searchText, canSearchText) {
        if (canSearchText) return@LaunchedEffect
        delay(500)
        val q = vm.searchText.trim()
        if (vm.filter.plate != q) vm.filter = vm.filter.copy(plate = q)
    }
    // "Clear filters" also clears the search text.
    LaunchedEffect(filter.query, filter.plate) {
        if (focused) return@LaunchedEffect
        if (canSearchText && filter.query.isEmpty() && vm.searchText.isNotEmpty()) vm.searchText = ""
        if (!canSearchText && filter.plate.isEmpty() && vm.searchText.isNotEmpty()) vm.searchText = ""
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canSearchText || canSearchPlates) {
            OutlinedTextField(
                value = vm.searchText,
                onValueChange = { vm.searchText = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).onFocusChanged { focused = it.isFocused },
                placeholder = { Text(stringResource(if (canSearchText) R.string.explore_search else R.string.explore_search_plate), maxLines = 1) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (vm.searchText.isNotEmpty()) IconButton(onClick = {
                        vm.searchText = ""
                        vm.filter = if (canSearchText) vm.filter.copy(query = "") else vm.filter.copy(plate = "")
                    }) { Icon(Icons.Filled.Cancel, stringResource(R.string.explore_clear_search)) }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = if (canSearchText) KeyboardCapitalization.Sentences else KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false, imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )
            // Plates that contain what is typed, loosely ("hk008" finds "HK-008-X").
            val q = PlateSearch.normalized(vm.searchText)
            val suggestions = if (!canSearchPlates || !focused || q.isEmpty()) emptyList()
            else vm.knownPlates.filter { PlateSearch.normalized(it).contains(q) && it != vm.searchText && it != filter.plate }.take(8)
            if (suggestions.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (plate in suggestions) {
                        FilterChip(
                            selected = false,
                            onClick = {
                                // A suggested plate is the filter; with text search it becomes a chip and the box is free again.
                                vm.filter = vm.filter.copy(plate = plate)
                                vm.searchText = if (canSearchText) "" else plate
                                focus.clearFocus()
                            },
                            label = { Text(plate, maxLines = 1, softWrap = false) },
                            leadingIcon = { Icon(Icons.Outlined.Pin, null, Modifier.size(16.dp)) },
                        )
                    }
                }
            }
        }
        FilterRow(vm, canSearchText)
    }
}

@Composable
private fun FilterRow(vm: ExploreViewModel, canSearchText: Boolean) {
    val filter = vm.filter
    val connections = vm.connections
    var pickingDay by remember { mutableStateOf(false) }
    val cameraOptions = connections.flatMap { it.cameras }.distinctBy { it.name }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        // First, so the way back to the default view needs no scrolling and stays put.
        if (filter.isActive) {
            OutlinedIconButton(onClick = { vm.filter = ExploreFilter() }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.Close, stringResource(R.string.explore_clear_filters), Modifier.size(16.dp))
            }
        }
        val similar = filter.similar
        if (similar != null) {
            // Frigate's "Similarity search active", with a way out.
            RemovableChip(stringResource(R.string.explore_similar_to, Format.labelName(similar.event.label)), Icons.Outlined.FilterNone,
                stringResource(R.string.explore_clear_similar)) { vm.filter = vm.filter.copy(similar = null) }
        } else if (filter.query.isNotEmpty()) {
            val title = when {
                filter.sources.size == 1 && SearchSource.Thumbnail in filter.sources -> stringResource(R.string.explore_source_thumbnail)
                filter.sources.size == 1 -> stringResource(R.string.explore_source_description)
                else -> stringResource(R.string.explore_source_both)
            }
            MenuChip(title, active = filter.sources.size == 1) { _ ->
                for ((source, name) in listOf(SearchSource.Thumbnail to R.string.explore_source_thumbnail, SearchSource.Description to R.string.explore_source_description)) {
                    OptionItem(stringResource(name), source in vm.filter.sources) {
                        // One of the two stays on: searching nowhere finds nothing.
                        val s = vm.filter.sources
                        vm.filter = vm.filter.copy(sources = if (source in s) (if (s.size > 1) s - source else s) else s + source)
                    }
                }
            }
        }
        // With text search the plate is a filter of its own, as a tag in Frigate's search box.
        if (canSearchText && filter.plate.isNotEmpty()) {
            RemovableChip(filter.plate, Icons.Outlined.Pin, stringResource(R.string.explore_clear_plate)) { vm.filter = vm.filter.copy(plate = "") }
        }

        val cameraTitle = when {
            filter.cameras.isEmpty() -> stringResource(R.string.explore_cameras)
            filter.cameras.size == 1 -> cameraOptions.firstOrNull { it.name in filter.cameras }?.displayName
                ?: pluralStringResource(R.plurals.explore_n_cameras, 1, 1)
            else -> pluralStringResource(R.plurals.explore_n_cameras, filter.cameras.size, filter.cameras.size)
        }
        MenuChip(cameraTitle, active = filter.cameras.isNotEmpty()) { close ->
            OptionItem(stringResource(R.string.explore_all_cameras), vm.filter.cameras.isEmpty()) { vm.filter = vm.filter.copy(cameras = emptySet()); close() }
            // With several servers, cameras are grouped under their server's name.
            for (conn in connections) {
                if (connections.size > 1) MenuHeader(conn.server.name)
                for (cam in conn.cameras) {
                    OptionItem(cam.displayName, cam.name in vm.filter.cameras) { vm.filter = vm.filter.copy(cameras = vm.filter.cameras.toggled(cam.name)) }
                }
            }
        }
        val labelOptions = vm.labelOptions()
        MenuChip(
            if (filter.labels.isEmpty()) stringResource(R.string.explore_labels) else filter.labels.map { Format.labelName(it) }.sorted().joinToString(", "),
            active = filter.labels.isNotEmpty(),
        ) { close ->
            OptionItem(stringResource(R.string.explore_all_labels), vm.filter.labels.isEmpty()) { vm.filter = vm.filter.copy(labels = emptySet()); close() }
            for (l in labelOptions) OptionItem(Format.labelName(l), l in vm.filter.labels) { vm.filter = vm.filter.copy(labels = vm.filter.labels.toggled(l)) }
        }
        if (vm.zoneOptions().isNotEmpty()) {
            MenuChip(
                if (filter.zones.isEmpty()) stringResource(R.string.explore_zones) else filter.zones.map { Format.prettyName(it) }.sorted().joinToString(", "),
                active = filter.zones.isNotEmpty(),
            ) { close ->
                OptionItem(stringResource(R.string.explore_all_zones), vm.filter.zones.isEmpty()) { vm.filter = vm.filter.copy(zones = emptySet()); close() }
                // With several servers, zones are grouped under their server's name.
                for (conn in connections) {
                    val zones = conn.zones(vm.filter.cameras)
                    if (zones.isEmpty()) continue
                    if (connections.size > 1) MenuHeader(conn.server.name)
                    for (z in zones) OptionItem(Format.prettyName(z), z in vm.filter.zones) { vm.filter = vm.filter.copy(zones = vm.filter.zones.toggled(z)) }
                }
            }
        }
        val subOptions = vm.subLabelOptions()
        if (subOptions.isNotEmpty()) {
            MenuChip(
                if (filter.subLabels.isEmpty()) stringResource(R.string.explore_sub_labels) else filter.subLabels.sorted().joinToString(", "),
                active = filter.subLabels.isNotEmpty(),
            ) { close ->
                OptionItem(stringResource(R.string.explore_all_sub_labels), vm.filter.subLabels.isEmpty()) { vm.filter = vm.filter.copy(subLabels = emptySet()); close() }
                for (s in subOptions) OptionItem(s, s in vm.filter.subLabels) { vm.filter = vm.filter.copy(subLabels = vm.filter.subLabels.toggled(s)) }
            }
        }
        val today = startOfDay(System.currentTimeMillis())
        val yesterday = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        MenuChip(filter.day?.let { Format.dayTitle(Date(it)) } ?: stringResource(R.string.explore_all_dates), active = filter.day != null) { close ->
            OptionItem(stringResource(R.string.explore_all_dates), vm.filter.day == null) { vm.filter = vm.filter.copy(day = null); close() }
            OptionItem(stringResource(R.string.today), vm.filter.day == today) { vm.filter = vm.filter.copy(day = today); close() }
            OptionItem(stringResource(R.string.yesterday), vm.filter.day == yesterday) { vm.filter = vm.filter.copy(day = yesterday); close() }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.explore_pick_date)) },
                leadingIcon = { Icon(Icons.Outlined.CalendarMonth, null) },
                onClick = { close(); pickingDay = true },
            )
        }
    }
    if (pickingDay) DayPickerDialog(filter.day, onPick = { vm.filter = vm.filter.copy(day = it) }, onDismiss = { pickingDay = false })
}

private fun Set<String>.toggled(v: String): Set<String> = if (v in this) this - v else this + v

/** Local midnight of the day `millis` falls on. */
fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** A filter chip that opens a menu; `content` gets a way to close it (multi-select menus stay open). */
@Composable
internal fun MenuChip(title: String, active: Boolean, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active, onClick = { open = true },
            label = { Text(title, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(open, { open = false }) { content { open = false } }
    }
}

/** A chip for an active filter; tapping it removes the filter. */
@Composable
private fun RemovableChip(title: String, icon: ImageVector, clearLabel: String, onClear: () -> Unit) {
    FilterChip(
        selected = true, onClick = onClear,
        label = { Text(title, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
        trailingIcon = { Icon(Icons.Filled.Close, clearLabel, Modifier.size(16.dp)) },
    )
}

/** A menu item with a checkmark when chosen. */
@Composable
internal fun OptionItem(title: String, on: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, maxLines = 1) },
        leadingIcon = { if (on) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
        onClick = onClick,
    )
}

/** A heading inside a menu (a server's name). */
@Composable
internal fun MenuHeader(title: String) {
    HorizontalDivider()
    Text(
        title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), maxLines = 1,
    )
}

/** A calendar for picking one day, up to today. */
@Composable
private fun DayPickerDialog(day: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    // The picker works in UTC midnights; the filter in local ones.
    fun toUtc(local: Long): Long {
        val l = Calendar.getInstance().apply { timeInMillis = local }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
    fun toLocal(utc: Long): Long {
        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
        return Calendar.getInstance().apply {
            clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
    val now = remember { System.currentTimeMillis() }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = toUtc(day ?: startOfDay(now)),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= now
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPick(toLocal(it)) }; onDismiss() }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state) }
}

// MARK: - Results

@Composable
private fun ExploreResults(vm: ExploreViewModel) {
    val nav = LocalNav.current
    val showsGrid = vm.showsGrid
    val empty = if (showsGrid) vm.grid.isEmpty() else vm.rows.isEmpty()
    val open: (ExploreEntry) -> Unit = { e ->
        ItemCache.put(e.event)
        nav.navigate(ObjectRoute(e.serverId, e.event.id))
    }
    when {
        vm.connections.isEmpty() || empty -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                when {
                    vm.connections.isEmpty() -> EmptyState(Icons.Outlined.WifiOff, stringResource(R.string.explore_no_servers),
                        stringResource(R.string.explore_no_servers_message))
                    vm.loading -> LoadingBox(Modifier.padding(top = 60.dp))
                    vm.loadError != null -> EmptyState(Icons.Outlined.WarningAmber, stringResource(R.string.explore_could_not_load), vm.loadError)
                    else -> EmptyState(Icons.Outlined.Search, stringResource(R.string.explore_nothing_found), stringResource(R.string.explore_nothing_found_message))
                }
            }
        }
        // A new filter starts at the top; coming back from an object keeps the place.
        showsGrid -> key(vm.filterKey()) { ResultGrid(vm, open) }
        else -> key(vm.filterKey()) { LabelRows(vm, open) }
    }
}

@Composable
private fun ResultGrid(vm: ExploreViewModel, open: (ExploreEntry) -> Unit) {
    val state = rememberLazyGridState()
    val similarity = vm.filter.similar != null
    val isSearch = vm.filter.isSearch
    // Near the end: the next page.
    val nearEnd by remember {
        derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 6 }
    }
    LaunchedEffect(state) {
        snapshotFlow { nearEnd to vm.grid.size }.collect { (near, size) -> if (near && size > 0) vm.loadMore() }
    }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp), state = state, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(vm.grid, key = { it.key }) { entry ->
            ObjectTile(entry, showLabel = true, match = if (isSearch) searchMatch(entry.event, similarity) else null,
                modifier = Modifier.aspectRatio(1f).clickable { open(entry) })
        }
        if (vm.loading && vm.grid.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) { SmallSpinner() }
            }
        }
    }
}

@Composable
private fun LabelRows(vm: ExploreViewModel, open: (ExploreEntry) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Tiles in a label row: 128 on phones; on tablets the row's ten tiles fill the width.
        val side: Dp = if (maxWidth >= 600.dp) maxOf(128.dp, (maxWidth - 32.dp - 72.dp) / 10) else 128.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(vm.rows, key = { it.label }) { row ->
                Column(Modifier.padding(top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.filter = vm.filter.copy(labels = setOf(row.label)) }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(Format.labelName(row.label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        row.count?.let {
                            Text(pluralStringResource(R.plurals.explore_n_tracked_objects, it, it), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(row.entries, key = { it.key }) { entry ->
                            ObjectTile(entry, showLabel = false, modifier = Modifier.size(side).clickable { open(entry) })
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Tile

/** "15 nov, 13:14" in the user's language and clock. */
private fun tileWhen(epoch: Double): String {
    val skeleton = if (Format.uses12Hour) "dMMMhmma" else "dMMMHHmm"
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), Locale.getDefault()).format(epoch.toDate())
}

/** Square thumbnail of a tracked object with its age, like the tiles on Frigate's Explore page. */
@Composable
fun ObjectTile(entry: ExploreEntry, showLabel: Boolean, modifier: Modifier = Modifier, match: SearchMatch? = null) {
    val conn = AppModel.connection(entry.serverId)
    val event: FrigateEvent = entry.event
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        RemoteImage(conn?.client?.thumbnailUrl(event), Modifier.matchParentSize())
        // Object icons sit at the top left of a picture, everywhere.
        if (showLabel) {
            Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                LabelIcon(event.label)
                event.bestScore?.let { OverlayChip("${Math.round(it * 100)}%") }
            }
        }
        if (match != null) {
            val desc = if (match.byDescription) stringResource(R.string.explore_matched_description, match.percent)
            else stringResource(R.string.explore_matched_thumbnail, match.percent)
            OverlayChip(
                "${match.percent}%", Modifier.align(Alignment.TopEnd).padding(6.dp).semantics { contentDescription = desc },
                icon = if (match.byDescription) Icons.AutoMirrored.Filled.Notes else Icons.Filled.Image,
            )
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (showLabel) {
                if (AppModel.orderedConnections.size > 1 && conn != null) {
                    ServerBadge(conn.server, size = 14.dp); Spacer(Modifier.width(4.dp))
                }
                Text(conn?.camera(event.camera)?.displayName ?: Format.prettyName(event.camera), color = Color.White,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            // The time as well, as Frigate shows it: "5 min ago" alone is hard to find back.
            Column(horizontalAlignment = Alignment.End) {
                Text(relativeTime(event.startTime), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(tileWhen(event.startTime), color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, style = MonoDigits)
            }
        }
    }
}
