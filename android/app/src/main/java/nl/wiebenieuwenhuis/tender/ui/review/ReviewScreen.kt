package nl.wiebenieuwenhuis.tender.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.nowSeconds
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.EventRoute
import nl.wiebenieuwenhuis.tender.ui.ItemCache
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.PlaybackRoute
import nl.wiebenieuwenhuis.tender.ui.components.ConfirmDialog
import nl.wiebenieuwenhuis.tender.ui.components.EmptyState
import nl.wiebenieuwenhuis.tender.ui.components.ErrorBanner
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.ProgressButton
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.playback.MotionReviewPane
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/** One line of the list: a day's heading or an item. */
private sealed class ReviewLine {
    data class Day(val start: Long) : ReviewLine()
    data class Item(val entry: ReviewEntry) : ReviewLine()
}

/** The Review tab: alerts and detections (and Motion), as Frigate's Review page. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen() {
    val vm: ReviewModel = viewModel()
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val wide = nl.wiebenieuwenhuis.tender.ui.components.isWideLayout()
    var pickingDay by remember { mutableStateOf(false) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    // A deep link (frigate://review?view=motion) or another screen asks for a segment.
    LaunchedEffect(AppModel.pendingLink) {
        val link = AppModel.pendingLink ?: return@LaunchedEffect
        if (!link.startsWith("review:")) return@LaunchedEffect
        AppModel.pendingLink = null
        vm.endSelecting()
        vm.kind = when (link.removePrefix("review:")) {
            "motion" -> ReviewKind.Motion
            "detection", "detections" -> ReviewKind.Detection
            else -> ReviewKind.Alert
        }
    }
    LaunchedEffect(vm.filterKey) { vm.reload() }
    // Keep up with reviews done elsewhere while this tab is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            if (vm.kind != ReviewKind.Motion) vm.reload(silent = true)
        }
    }
    BackHandler(enabled = vm.selecting) { vm.endSelecting() }

    val shown = vm.shown
    val hasUnreviewed = shown.any { !it.item.isReviewed }
    val selectedCount = vm.selectedEntries.size

    // The title bar slides away with the list and comes back when scrolling up; the kinds and
    // filters are the list's first item, so they scroll away with it (as on iOS).
    val barState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(barState)
    val scrolls = !vm.selecting && vm.kind != ReviewKind.Motion
    LaunchedEffect(scrolls) { if (!scrolls) barState.heightOffset = 0f }

    // The progress pill of the long-press actions floats over the whole screen, as on the item screen.
    ReviewActionsHost(vm.actions, Modifier.fillMaxSize()) {
    ScreenScaffold(
        title = if (vm.selecting) pluralStringResource(R.plurals.review_n_selected, selectedCount, selectedCount) else stringResource(R.string.review_title),
        showsBack = vm.selecting,
        onBack = { vm.endSelecting() },
        scrollBehavior = if (scrolls) scrollBehavior else null,
        actions = {
            if (vm.kind != ReviewKind.Motion) {
                if (vm.selecting) {
                    TextButton(onClick = { vm.selectAll() }) { Text(stringResource(R.string.review_select_all), maxLines = 1, softWrap = false) }
                } else {
                    ProgressButton(stringResource(R.string.review_mark_all_reviewed), busy = vm.markingAll, onClick = { vm.markAllReviewed() },
                        enabled = hasUnreviewed, filled = false)
                    if (shown.isNotEmpty()) IconButton(onClick = { vm.startSelecting() }) {
                        Icon(Icons.Outlined.Checklist, stringResource(R.string.review_select))
                    }
                }
            }
        },
        bottomBar = {
            if (vm.selecting && vm.kind != ReviewKind.Motion) {
                BottomAppBar {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProgressButton(stringResource(R.string.review_mark_reviewed), busy = vm.bulkMarking,
                            onClick = { vm.markSelectedReviewed() }, modifier = Modifier.weight(1f),
                            enabled = vm.selectedEntries.any { !it.item.isReviewed } && !vm.bulkDeleting)
                        // Frigate lets only admins delete review items.
                        if (vm.selectedEntries.all { AppModel.connection(it.serverId)?.isAdmin == true }) {
                            ProgressButton(stringResource(R.string.review_delete), busy = vm.bulkDeleting,
                                onClick = { confirmBulkDelete = true }, modifier = Modifier.weight(1f), destructive = true,
                                enabled = vm.canDeleteSelection && !vm.bulkMarking)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (vm.kind == ReviewKind.Motion) {
                // Motion fills the screen below the header: previews and a timeline.
                Column(Modifier.fillMaxSize()) {
                    Header(vm, wide, onPickDay = { pickingDay = true })
                    MotionReviewPane(Modifier.weight(1f).fillMaxWidth(), cameraFilter = vm.cameraFilter.toSet(),
                        start = vm.range.bounds.first, end = vm.range.bounds.second)
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { scope.launch { refreshing = true; try { vm.reload(silent = true) } finally { refreshing = false } } },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    ReviewList(vm, shown, wide,
                        header = { Header(vm, wide, onPickDay = { pickingDay = true }) },
                        onOpen = { entry ->
                            if (vm.selecting) vm.toggle(entry) else {
                                // As in Frigate's web app: the camera's recordings from just before the item,
                                // which then counts as reviewed. The item's own screen is in the long-press menu.
                                val item = entry.item
                                nav.navigate(PlaybackRoute(entry.serverId, item.camera, startOfDay(item.startTime), reviewId = item.id))
                                if (!item.isReviewed) vm.markReviewed(listOf(entry))
                            }
                        },
                        onDetails = { entry ->
                            ItemCache.put(entry.item)
                            nav.navigate(EventRoute(entry.serverId, entry.item.id))
                        },
                    )
                }
            }
        }
    }
    }

    if (pickingDay) DayPicker((vm.range as? ReviewRange.Day)?.start, onPick = { vm.range = ReviewRange.Day(it); pickingDay = false }, onDismiss = { pickingDay = false })
    if (confirmBulkDelete) {
        val n = vm.selectedEntries.size
        ConfirmDialog(
            title = pluralStringResource(R.plurals.review_delete_selected_title, n, n),
            message = vm.selectedEntries.singleOrNull()?.let { deleteMessage(it.item) },
            confirmText = stringResource(R.string.review_delete),
            onConfirm = { vm.deleteSelected() },
            onDismiss = { confirmBulkDelete = false },
        )
    }
}

/** Private routes of the review feature (the item screen's route, EventRoute, is shared). */
fun NavGraphBuilder.reviewDestinations() {}

// MARK: Header

@Composable
private fun Header(vm: ReviewModel, wide: Boolean, onPickDay: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (wide) {
            // Wide screens: the kinds on the left and the filters on the right, on one line.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                KindBar(vm, Modifier.widthIn(max = 560.dp).weight(1f))
                Filters(vm, onPickDay, Modifier.weight(1f, fill = false), padded = false)
            }
        } else {
            KindBar(vm, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Filters(vm, onPickDay, Modifier.fillMaxWidth(), padded = true)
        }
    }
}

@Composable
private fun KindBar(vm: ReviewModel, modifier: Modifier) {
    val conns = vm.connections
    // Unreviewed items of the last day in the loaded list: the summary behind the counts lags behind a brand-new item.
    fun listed(k: ReviewKind): Int {
        if (k != vm.kind) return 0
        val dayAgo = nowSeconds() - 86400
        return vm.shown.count { !it.item.isReviewed && it.item.startTime > dayAgo }
    }
    val kinds = ReviewKind.entries
    SingleChoiceSegmentedButtonRow(modifier) {
        kinds.forEachIndexed { i, k ->
            val title = when (k) {
                ReviewKind.Alert -> countTitle(stringResource(R.string.review_alerts), maxOf(conns.sumOf { it.unreviewedAlerts }, listed(k)))
                ReviewKind.Detection -> countTitle(stringResource(R.string.review_detections), maxOf(conns.sumOf { it.unreviewedDetections }, listed(k)))
                ReviewKind.Motion -> stringResource(R.string.review_motion)
            }
            SegmentedButton(
                selected = vm.kind == k,
                onClick = { if (vm.kind != k) { vm.endSelecting(); vm.kind = k } },
                shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                icon = {},
                label = { Text(title, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun countTitle(title: String, n: Int): String = if (n > 0) stringResource(R.string.review_count_title, title, n) else title

/** A chip that opens a menu, with a chevron. */
@Composable
private fun MenuChip(title: String, active: Boolean, enabled: Boolean = true, menu: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active, onClick = { open = true }, enabled = enabled,
            label = { Text(title, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(open, onDismissRequest = { open = false }) { menu { open = false } }
    }
}

/** A menu item with a check mark in front when on. */
@Composable
private fun CheckItem(title: String, on: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, maxLines = 1) },
        leadingIcon = { if (on) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
        onClick = onClick,
    )
}

/** A server's name over its cameras or zones in a menu, with several servers. */
@Composable
private fun MenuHeading(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Filters(vm: ReviewModel, onPickDay: () -> Unit, modifier: Modifier, padded: Boolean) {
    val conns = vm.connections
    val several = conns.size > 1
    // Scrolls edge to edge on a phone instead of being cut off at the page margin.
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = if (padded) 16.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        // First, so the way back to the default view needs no scrolling and stays put.
        if (vm.filtersActive) {
            FilterChip(
                selected = false, onClick = { vm.clearFilters() },
                label = { Text(stringResource(R.string.explore_clear_filters), maxLines = 1, softWrap = false) },
                leadingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) },
            )
        }
        val camTitle = if (vm.cameraFilter.isEmpty()) stringResource(R.string.review_cameras)
        else vm.cameraFilter.singleOrNull()?.let { name -> cameraName(conns.firstOrNull { it.camera(name) != null }, name) }
            ?: pluralStringResource(R.plurals.review_n_cameras, vm.cameraFilter.size, vm.cameraFilter.size)
        MenuChip(camTitle, vm.cameraFilter.isNotEmpty()) { close ->
            CheckItem(stringResource(R.string.review_all_cameras), vm.cameraFilter.isEmpty()) { vm.cameraFilter = emptySet(); close() }
            // With several servers, cameras are grouped under their server's name.
            for (conn in conns) {
                if (several) { HorizontalDivider(); MenuHeading(conn.server.name) }
                for (cam in conn.cameras) {
                    CheckItem(cam.displayName, cam.name in vm.cameraFilter) {
                        vm.cameraFilter = if (cam.name in vm.cameraFilter) vm.cameraFilter - cam.name else vm.cameraFilter + cam.name
                    }
                }
            }
        }

        // Motion has no review items to filter: only its cameras and period.
        if (vm.kind != ReviewKind.Motion) {
            val labels = (vm.entries.flatMap { it.item.labels } + vm.labelFilter).distinct().sortedBy { Format.labelName(it) }
            MenuChip(
                if (vm.labelFilter.isEmpty()) stringResource(R.string.review_labels) else vm.labelFilter.map { Format.labelName(it) }.sorted().joinToString(", "),
                vm.labelFilter.isNotEmpty(),
            ) { close ->
                CheckItem(stringResource(R.string.review_all_labels), vm.labelFilter.isEmpty()) { vm.labelFilter = emptySet(); close() }
                for (l in labels) CheckItem(Format.labelName(l), l in vm.labelFilter) {
                    vm.labelFilter = if (l in vm.labelFilter) vm.labelFilter - l else vm.labelFilter + l
                }
            }

            // The zones configured on the cameras in view, under their server's name with several servers.
            val zonesByServer = conns.map { it to it.zones(vm.cameraFilter) }.filter { it.second.isNotEmpty() }
            if (zonesByServer.isNotEmpty()) {
                MenuChip(
                    if (vm.zoneFilter.isEmpty()) stringResource(R.string.review_zones) else vm.zoneFilter.map { Format.prettyName(it) }.sorted().joinToString(", "),
                    vm.zoneFilter.isNotEmpty(),
                ) { close ->
                    CheckItem(stringResource(R.string.review_all_zones), vm.zoneFilter.isEmpty()) { vm.zoneFilter = emptySet(); close() }
                    for ((conn, zones) in zonesByServer) {
                        if (several) { HorizontalDivider(); MenuHeading(conn.server.name) }
                        for (z in zones) {
                            CheckItem(Format.prettyName(z), z in vm.zoneFilter) { vm.zoneFilter = if (z in vm.zoneFilter) vm.zoneFilter - z else vm.zoneFilter + z }
                        }
                    }
                }
            }

            FilterChip(
                selected = vm.unreviewedOnly, onClick = { vm.unreviewedOnly = !vm.unreviewedOnly },
                label = { Text(stringResource(R.string.review_unreviewed), maxLines = 1, softWrap = false) },
                leadingIcon = if (vm.unreviewedOnly) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
            )
        }

        MenuChip(vm.range.title, vm.range != ReviewRange.Last24Hours) { close ->
            val today = startOfDay(nowSeconds())
            val yesterday = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
            CheckItem(stringResource(R.string.review_last_24_hours), vm.range == ReviewRange.Last24Hours) { vm.range = ReviewRange.Last24Hours; close() }
            CheckItem(stringResource(R.string.today), vm.range == ReviewRange.Day(today)) { vm.range = ReviewRange.Day(today); close() }
            CheckItem(stringResource(R.string.yesterday), vm.range == ReviewRange.Day(yesterday)) { vm.range = ReviewRange.Day(yesterday); close() }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.review_pick_date)) },
                leadingIcon = { Icon(Icons.Outlined.CalendarMonth, null) },
                onClick = { close(); onPickDay() },
            )
        }
    }
}

/** A day up to today, opened on `day` (local midnight in epoch ms) or today; returns its local midnight in epoch ms. */
@Composable
private fun DayPicker(day: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    // The picker works in UTC midnights: the same calendar day as the user's own.
    fun utcMidnight(localMs: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = localMs }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
    val todayUtc = remember { utcMidnight(System.currentTimeMillis()) }
    val initial = remember(day) { day?.let { minOf(utcMidnight(it), todayUtc) } ?: todayUtc }
    val state = rememberDatePickerState(initialSelectedDateMillis = initial, selectableDates = object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
    })
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val utc = state.selectedDateMillis ?: return@TextButton
                // The picker's day is UTC midnight; Review wants the user's own midnight of that day.
                val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
                val local = Calendar.getInstance().apply {
                    clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
                }
                onPick(local.timeInMillis)
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state) }
}

// MARK: List

@Composable
private fun ReviewList(
    vm: ReviewModel, shown: List<ReviewEntry>, wide: Boolean, header: @Composable () -> Unit,
    onOpen: (ReviewEntry) -> Unit, onDetails: (ReviewEntry) -> Unit,
) {
    val all = AppModel.scopedConnections
    // A problem while there are items shows at the top, under the filters.
    val banner: @Composable () -> Unit = {
        vm.loadError?.takeIf { shown.isNotEmpty() }?.let {
            ErrorBanner(it, Modifier.padding(vertical = 4.dp)) { vm.loadError = null }
        }
    }
    when {
        all.isNotEmpty() && all.none { it.isOnline } -> Scrollable(header) {
            EmptyState(Icons.Outlined.WifiOff, stringResource(R.string.review_no_servers_online), stringResource(R.string.review_no_servers_online_message))
        }
        vm.loading && shown.isEmpty() -> Scrollable(header) { LoadingBox() }
        vm.loadError != null && shown.isEmpty() -> Scrollable(header) {
            EmptyState(Icons.Outlined.WarningAmber, stringResource(R.string.review_could_not_load), vm.loadError)
        }
        shown.isEmpty() -> Scrollable(header) {
            EmptyState(
                Icons.Outlined.TaskAlt,
                stringResource(if (vm.unreviewedOnly) R.string.review_all_caught_up else R.string.review_nothing_here),
                if (!vm.unreviewedOnly) null
                else stringResource(if (vm.kind == ReviewKind.Alert) R.string.review_no_unreviewed_alerts else R.string.review_no_unreviewed_detections),
            )
        }
        else -> Items(vm, shown, wide, header, banner, onOpen, onDetails)
    }
}

/** Empty states under the header, in a scrolling list, so pull to refresh works on them too. */
@Composable
private fun Scrollable(header: @Composable () -> Unit, content: @Composable () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") { header() }
        item { content() }
    }
}

@Composable
private fun Items(
    vm: ReviewModel, shown: List<ReviewEntry>, wide: Boolean, header: @Composable () -> Unit, banner: @Composable () -> Unit,
    onOpen: (ReviewEntry) -> Unit, onDetails: (ReviewEntry) -> Unit,
) {
    val lines = remember(shown) {
        buildList {
            var day: Long? = null
            for (e in shown) {
                val d = startOfDay(e.item.startTime)
                if (d != day) { add(ReviewLine.Day(d)); day = d }
                add(ReviewLine.Item(e))
            }
        }
    }
    val showServer = AppModel.orderedConnections.size > 1
    val scope = rememberCoroutineScope()
    /** Lazy items before the first line: the header and the banner. */
    val lead = 2

    if (wide) {
        // Tablets: large pictures in a grid, like Frigate's review page, with the timeline beside it.
        val grid = rememberLazyGridState()
        val visibleKeys by remember(grid) {
            derivedStateOf { grid.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet() }
        }
        Row(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                GridCells.Adaptive(280.dp), Modifier.weight(1f).fillMaxHeight(), state = grid,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The header brings its own margins (its filters scroll to the screen edge).
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed(16.dp)) { header() } }
                item(key = "banner", span = { GridItemSpan(maxLineSpan) }) { banner() }
                for (line in lines) when (line) {
                    is ReviewLine.Day -> item(key = "day-${line.start}", span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel(Format.dayTitle(Date(line.start)), Modifier.padding(top = 6.dp))
                    }
                    is ReviewLine.Item -> item(key = line.entry.key) {
                        EntryItem(vm, line.entry, tile = true, showServer, onOpen, onDetails)
                    }
                }
                item(key = "more", span = { GridItemSpan(maxLineSpan) }) { LoadMore(vm) }
            }
            ReviewTimeline(shown, visibleKeys, onJump = { entry ->
                val i = lines.indexOfFirst { it is ReviewLine.Item && it.entry.key == entry.key }
                if (i >= 0) scope.launch { grid.animateScrollToItem(i + lead) }
            }, modifier = Modifier.width(76.dp).fillMaxHeight().padding(top = 8.dp, bottom = 16.dp, end = 12.dp))
        }
    } else {
        val list = rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The header brings its own margins (its filters scroll to the screen edge).
            item(key = "header") { Box(Modifier.bleed(16.dp)) { header() } }
            item(key = "banner") { banner() }
            for (line in lines) when (line) {
                is ReviewLine.Day -> item(key = "day-${line.start}") {
                    SectionLabel(Format.dayTitle(Date(line.start)), Modifier.padding(top = 6.dp))
                }
                is ReviewLine.Item -> item(key = line.entry.key) {
                    EntryItem(vm, line.entry, tile = false, showServer, onOpen, onDetails)
                }
            }
            item(key = "more") { LoadMore(vm) }
        }
    }
}

/** A row or tile with its long-press menu. */
@Composable
private fun EntryItem(vm: ReviewModel, entry: ReviewEntry, tile: Boolean, showServer: Boolean, onOpen: (ReviewEntry) -> Unit, onDetails: (ReviewEntry) -> Unit) {
    val conn = AppModel.connection(entry.serverId) ?: return
    var menu by remember { mutableStateOf(false) }
    val deleting = vm.actions.deleting[entry.item.id] == true
    val busy = vm.marking[entry.key] == true || deleting
    val selected = vm.selected[entry.key] == true
    Box {
        val mod = Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { onOpen(entry) },
                onLongClick = { if (vm.selecting) vm.toggle(entry) else menu = true },
            )
            .alpha(if (deleting) 0.5f else 1f)
        if (tile) ReviewTile(entry, conn, showServer, busy, vm.selecting, selected, mod)
        else ReviewRow(entry, conn, showServer, busy, vm.selecting, selected, mod)
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.review_event_details)) },
                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                onClick = { menu = false; onDetails(entry) },
            )
            if (!entry.item.isReviewed) DropdownMenuItem(
                text = { Text(stringResource(R.string.review_mark_reviewed)) },
                leadingIcon = { Icon(Icons.Outlined.CheckCircle, null) },
                onClick = { menu = false; vm.markReviewed(listOf(entry)) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.review_select)) },
                leadingIcon = { Icon(Icons.Outlined.Checklist, null) },
                onClick = { menu = false; vm.startSelecting(entry) },
            )
            HorizontalDivider()
            ReviewItemMenuItems(entry, vm.actions) { menu = false }
        }
    }
}

@Composable
private fun LoadMore(vm: ReviewModel) {
    if (!vm.canLoadMore) return
    Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
        if (vm.loadingMore) SmallSpinner(size = 22.dp)
        else TextButton(onClick = { vm.loadMore() }) { Text(stringResource(R.string.review_load_more)) }
    }
}

/** Widens the content by `h` on both sides: a full-width item in a list with side padding. */
private fun Modifier.bleed(h: Dp) = layout { measurable, constraints ->
    val px = h.roundToPx()
    val placeable = measurable.measure(constraints.copy(
        minWidth = constraints.minWidth + 2 * px,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * px else constraints.maxWidth,
    ))
    layout(placeable.width - 2 * px, placeable.height) { placeable.place(-px, 0) }
}
