package nl.wiebenieuwenhuis.tender.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateEvent
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.TimelineEntry
import nl.wiebenieuwenhuis.tender.api.toDate
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ConnectionStatus
import nl.wiebenieuwenhuis.tender.ui.EventRoute
import nl.wiebenieuwenhuis.tender.ui.ItemCache
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.ObjectRoute
import nl.wiebenieuwenhuis.tender.ui.PlaybackRoute
import nl.wiebenieuwenhuis.tender.ui.components.EmptyState
import nl.wiebenieuwenhuis.tender.ui.components.LabelTag
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.MonoDigits
import nl.wiebenieuwenhuis.tender.ui.components.RemoteImage
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.ServerBadge
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.components.StatusDot
import nl.wiebenieuwenhuis.tender.ui.components.isWideLayout
import nl.wiebenieuwenhuis.tender.ui.exports.FileShare
import nl.wiebenieuwenhuis.tender.ui.theme.Palette

/**
 * A tracked object from Explore: the review item it belongs to when there is one (video, every
 * object's snapshot, the actions), which takes this screen's place; the object screen otherwise.
 */
@Composable
fun ObjectScreen(route: ObjectRoute) {
    val nav = LocalNav.current
    val conn = AppModel.connection(route.serverId)
    val client = conn?.client
    var event by remember { mutableStateOf(ItemCache.events[route.eventId]) }
    var resolved by rememberSaveable { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val offline = conn == null || conn.status is ConnectionStatus.Offline

    LaunchedEffect(route, client, offline) {
        if (client == null) { if (offline) resolved = true; return@LaunchedEffect }
        if (!resolved) {
            val review = runCatching { client.reviewForEvent(route.eventId) }.getOrNull()
            if (review != null) {
                ItemCache.put(review)
                nav.navigate(EventRoute(route.serverId, review.id, focusEventId = route.eventId)) {
                    popUpTo<ObjectRoute> { inclusive = true }
                }
                return@LaunchedEffect
            }
        }
        if (event == null) {
            runCatching { client.event(route.eventId) }
                .onSuccess { event = ItemCache.put(it) }
                .onFailure { problem = it.message ?: it.toString() }
        }
        resolved = true
    }

    val e = event
    if (resolved && e != null) {
        ObjectDetail(route.serverId, e)
    } else {
        ScreenScaffold("") { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (resolved) EmptyState(Icons.Outlined.WarningAmber, stringResource(R.string.explore_could_not_load_object),
                    problem ?: conn?.let { (it.status as? ConnectionStatus.Offline)?.message })
                else LoadingBox(Modifier.padding(top = 80.dp))
            }
        }
    }
}

/** Details of one tracked object: its snapshot, scores, zones and lifecycle, as Frigate's "Tracked object details". */
@Composable
private fun ObjectDetail(serverId: String, event: FrigateEvent) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conn = AppModel.connection(serverId)
    val client = conn?.client
    val camera = conn?.camera(event.camera)
    val aspect = camera?.aspectRatio ?: (16f / 9f)
    var timeline by remember { mutableStateOf<List<TimelineEntry>>(emptyList()) }
    var sharing by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(event.id, client) {
        if (client != null) timeline = runCatching { client.timeline(event.id) }.getOrDefault(emptyList())
    }

    fun shareSnapshot() {
        val c = client ?: return
        if (sharing) return
        sharing = true
        scope.launch {
            try {
                val file = FileShare.cacheFile("${event.camera}_${event.id}.jpg")
                c.download(c.snapshotUrl(event), file)
                FileShare.share(context, file, "image/jpeg")
            } catch (e: Exception) {
                errorMessage = e.message ?: e.toString()
            } finally {
                sharing = false
            }
        }
    }

    /** Opens the review item the object belongs to; without one, the recording of that moment. */
    fun openVideo() {
        val c = client ?: return
        if (opening) return
        opening = true
        scope.launch {
            try {
                val review = runCatching { c.reviewForEvent(event.id) }.getOrNull()
                if (review != null) {
                    ItemCache.put(review)
                    nav.navigate(EventRoute(serverId, review.id, focusEventId = event.id))
                } else {
                    nav.navigate(PlaybackRoute(serverId, event.camera, startOfDay((event.startTime * 1000).toLong()),
                        showTimeline = true, startTime = event.startTime))
                }
            } finally {
                opening = false
            }
        }
    }

    val image: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.background(Color.Black)) {
            RemoteImage(
                if (event.hasSnapshot == true) client?.snapshotUrl(event) else client?.thumbnailUrl(event),
                Modifier.fillMaxSize().zoomable(), contentScale = ContentScale.Fit,
            )
        }
    }
    val actions: @Composable () -> Unit = {
        Button(
            onClick = { openVideo() }, enabled = !opening && client != null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (opening) SmallSpinner(size = 18.dp) else Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.explore_watch_video), maxLines = 1)
        }
    }

    // No title: the info block under the picture already names the object.
    ScreenScaffold(
        "",
        actions = {
            if (event.hasSnapshot == true) {
                IconButton(onClick = { shareSnapshot() }, enabled = client != null) {
                    if (sharing) SmallSpinner() else Icon(Icons.Outlined.Share, stringResource(R.string.explore_share_snapshot))
                }
            }
        },
    ) { pad ->
        // The picture stays put; only the details scroll.
        WideDetailLayout(
            aspect = aspect, modifier = Modifier.padding(pad), edgeToEdge = !isWideLayout(),
            media = image,
            details = {
                ObjectInfo(serverId, event)
                ObjectTiles(event)
                ObjectDescriptionSection(serverId, event)
            },
            extra = { underMedia ->
                Lifecycle(timeline, if (underMedia) Modifier.padding(top = 16.dp) else Modifier.padding(horizontal = 16.dp).padding(top = 16.dp))
            },
            actions = actions,
        )
    }
    errorMessage?.let { MessageDialog(stringResource(R.string.explore_something_wrong), it) { errorMessage = null } }
}

@Composable
private fun ObjectInfo(serverId: String, event: FrigateEvent) {
    val conn = AppModel.connection(serverId)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            LabelTag(event.label)
            event.subLabel?.takeIf { it.isNotEmpty() }?.let { SmallTag(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (AppModel.orderedConnections.size > 1 && conn != null) { ServerBadge(conn.server); Spacer(Modifier.width(8.dp)) }
            Text(conn?.camera(event.camera)?.displayName ?: Format.prettyName(event.camera),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(timeText(event), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SmallTag(text: String) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

private fun timeText(event: FrigateEvent): String {
    val start = event.startTime.toDate()
    val day = Format.dayTitle(start)
    val s = Format.time(start, seconds = true)
    val end = event.endTime ?: return L(R.string.explore_time_in_progress, day, s)
    return L(R.string.explore_time_span, day, s, Format.time(end.toDate(), seconds = true))
}

@Composable
private fun ObjectTiles(event: FrigateEvent) {
    val zones = event.zones.map { Format.prettyName(it) }
    val duration = event.endTime?.let { it - event.startTime }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoTile(stringResource(R.string.explore_tile_score), event.bestScore?.let { "${Math.round(it * 100)}%" } ?: "–")
        InfoTile(stringResource(R.string.explore_tile_zones), if (zones.isEmpty()) stringResource(R.string.explore_none) else zones.joinToString(", "))
        val plate = event.recognizedPlate
        if (plate != null) {
            InfoTile(stringResource(R.string.explore_tile_plate), plate)
        } else {
            InfoTile(stringResource(R.string.explore_tile_duration), duration?.let {
                if (it > 3600) stringResource(R.string.more_than_hour) else Format.shortDuration(it)
            } ?: stringResource(R.string.explore_live))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.InfoTile(title: String, value: String) {
    Surface(Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MonoDigits)
        }
    }
}

/** What happened to the object, as Frigate's "Object lifecycle". */
@Composable
private fun Lifecycle(timeline: List<TimelineEntry>, modifier: Modifier = Modifier) {
    if (timeline.isEmpty()) return
    SectionCard(modifier) {
        SectionLabel(stringResource(R.string.explore_lifecycle), Modifier.padding(horizontal = 10.dp).padding(top = 12.dp, bottom = 6.dp))
        for (entry in timeline) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusDot(dotColor(entry), size = 8.dp)
                Text(lifecycleText(entry), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(Format.time(entry.timestamp, seconds = true), style = MaterialTheme.typography.bodySmall.merge(MonoDigits),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun dotColor(e: TimelineEntry): Color = when (e.classType) {
    "visible", "heard" -> Palette.labelColor(e.label ?: "")
    "entered_zone" -> MaterialTheme.colorScheme.primary
    "gone" -> Palette.status.offline
    else -> LocalContentColor.current.copy(alpha = 0.35f)
}

/** "Person entered Driveway", as Frigate's lifecycle lines. */
fun lifecycleText(e: TimelineEntry): String {
    val label = Format.labelName(e.label ?: "object")
    return when (e.classType) {
        "visible" -> L(R.string.explore_tl_detected, label)
        "entered_zone" -> {
            val z = e.zones.joinToString(", ") { Format.prettyName(it) }
            if (z.isEmpty()) L(R.string.explore_tl_entered_zone, label) else L(R.string.explore_tl_entered, label, z)
        }
        "active" -> L(R.string.explore_tl_active, label)
        "stationary" -> L(R.string.explore_tl_stationary, label)
        "attribute" -> L(R.string.explore_tl_attribute, label, e.attribute?.let { Format.labelName(it) } ?: "attribute")
        "gone" -> L(R.string.explore_tl_gone, label)
        "heard" -> L(R.string.explore_tl_heard, label)
        "external" -> L(R.string.explore_tl_external, label)
        else -> "$label: ${e.classType.replace('_', ' ')}"
    }
}
