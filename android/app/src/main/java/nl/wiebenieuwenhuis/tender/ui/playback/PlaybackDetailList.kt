package nl.wiebenieuwenhuis.tender.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.FrigateEvent
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.ReviewItem
import nl.wiebenieuwenhuis.tender.api.Severity
import nl.wiebenieuwenhuis.tender.api.TimelineEntry
import nl.wiebenieuwenhuis.tender.ui.components.LabelIcon
import nl.wiebenieuwenhuis.tender.ui.components.LabelTag
import nl.wiebenieuwenhuis.tender.ui.components.MonoDigits
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.theme.Palette

/** The objects of one event and their lifecycle, loaded when its card opens. */
@Immutable
data class EventDetailData(val objects: List<FrigateEvent>, val entries: List<TimelineEntry>) {
    fun entries(o: FrigateEvent) = entries.filter { it.sourceId == o.id }

    /** The last lifecycle row at or before `time` (detection clock), across all objects. */
    fun entryIndex(time: Double): Int? = entries.indexOfLast { it.timestamp <= time + 0.25 }.takeIf { it >= 0 }
}

/** A lifecycle row in words: "Person entered Driveway". */
fun TimelineEntry.describe(): String {
    val l = Format.labelName(label ?: "object")
    return when (classType) {
        "visible" -> L(R.string.playback_life_detected, l)
        "entered_zone" -> {
            val z = zones.joinToString(", ") { Format.prettyName(it) }
            if (z.isEmpty()) L(R.string.playback_life_entered_zone, l) else L(R.string.playback_life_entered, l, z)
        }
        "active" -> L(R.string.playback_life_active, l)
        "stationary" -> L(R.string.playback_life_stationary, l)
        "attribute" -> L(R.string.playback_life_attribute, l, attribute?.let { Format.labelName(it) } ?: L(R.string.playback_life_attribute_word))
        "gone" -> L(R.string.playback_life_gone, l)
        "heard" -> L(R.string.playback_life_heard, l)
        "external" -> L(R.string.playback_life_external, l)
        else -> "$l: ${classType.replace('_', ' ')}"
    }
}

/**
 * Playback's "Detail", like Frigate's: the day's events as cards, the one that plays opened up
 * with its objects and their lifecycle. The lifecycle row at the playhead is lit; a tap on a row
 * plays from there, a tap on another event opens it and plays it.
 * `wallTime`: the playhead in recording time; lifecycle times plus `annotationOffset` are on that clock.
 */
@Composable
fun PlaybackDetailList(
    reviews: List<ReviewItem>,
    playingId: String?,
    wallTime: () -> Double,
    annotationOffset: Double,
    client: FrigateClient?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** The long-press menu of a card, drawn on that card: the item, whether it is open, and how to close it. */
    menu: (@Composable (ReviewItem, Boolean, () -> Unit) -> Unit)? = null,
    onJump: (Double) -> Unit,
) {
    var openId by remember { mutableStateOf<String?>(null) }
    /** The card asked to open while its objects load; it opens once they are there. */
    var pendingId by remember { mutableStateOf<String?>(null) }
    val details = remember { mutableStateMapOf<String, EventDetailData>() }
    val loading = remember { mutableStateMapOf<String, Boolean>() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    suspend fun load(id: String) {
        if (details.containsKey(id) || loading.containsKey(id) || client == null) return
        val r = reviews.firstOrNull { it.id == id } ?: return
        loading[id] = true
        try {
            val ids = r.detections.take(10)
            val (events, entries) = coroutineScope {
                val ev = async { runCatching { client.events(ids) }.getOrDefault(emptyList()) }
                val tl = ids.map { oid -> async { runCatching { client.timeline(oid) }.getOrDefault(emptyList()) } }
                ev.await() to tl.awaitAll().flatten()
            }
            details[id] = EventDetailData(events.sortedBy { it.startTime }, entries.sortedBy { it.timestamp })
        } finally {
            loading.remove(id)
        }
    }

    fun open(id: String, animated: Boolean) {
        val index = reviews.indexOfFirst { it.id == id }
        if (details.containsKey(id)) {
            pendingId = null
            openId = id
            if (index >= 0) scope.launch { if (animated) list.animateScrollToItem(index) else list.scrollToItem(index) }
            return
        }
        pendingId = id
        scope.launch {
            load(id)
            if (pendingId != id) return@launch // another card was asked for meanwhile
            pendingId = null
            openId = id
            if (index >= 0) { if (animated) list.animateScrollToItem(index) else list.scrollToItem(index) }
        }
    }

    // The event that starts playing opens by itself, as in Frigate; its neighbours load too.
    var lastPlaying by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playingId) {
        val id = playingId ?: return@LaunchedEffect
        if (id != openId) open(id, animated = lastPlaying != null)
        lastPlaying = id
        val i = reviews.indexOfFirst { it.id == id }
        if (i >= 0) for (n in reviews.subList(maxOf(0, i - 2), minOf(reviews.size, i + 3))) if (n.id != id) load(n.id)
    }

    LazyColumn(
        modifier, state = list,
        contentPadding = PaddingValues(start = if (compact) 0.dp else 16.dp, end = if (compact) 0.dp else 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(reviews, key = { it.id }) { r ->
            val open = openId == r.id
            val data = details[r.id]
            DetailCard(
                r, open, pendingId == r.id, playingId == r.id, data,
                highlight = if (open && data != null) data.entryIndex(wallTime() - annotationOffset) else null,
                compact = compact,
                onHeader = {
                    if (openId == r.id) openId = null
                    else {
                        onJump(r.startTime + annotationOffset - 1)
                        open(r.id, animated = true)
                    }
                },
                menu = menu?.let { m -> { open, close -> m(r, open, close) } },
                onEntry = { onJump(it + annotationOffset - 1) },
            )
        }
    }
}

@Composable
private fun DetailCard(
    review: ReviewItem, open: Boolean, loading: Boolean, playing: Boolean, data: EventDetailData?, highlight: Int?, compact: Boolean,
    onHeader: () -> Unit, menu: (@Composable (Boolean, () -> Unit) -> Unit)?, onEntry: (Double) -> Unit,
) {
    val s = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(s.surfaceContainer, shape)
            .border(if (open || playing) 1.5.dp else 1.dp, if (open || playing) s.primary else s.outlineVariant, shape),
    ) {
        Box {
        menu?.invoke(menuOpen) { menuOpen = false }
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onHeader, onLongClick = if (menu != null) ({ menuOpen = true }) else null)
                .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(if (review.severity == Severity.Alert) Palette.status.destructive else Palette.status.warning, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Format.time(review.startTime, seconds = true), fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.SemiBold,
                        style = MonoDigits, maxLines = 1)
                    for (l in review.labels) LabelIcon(l, size = if (compact) 18.dp else 20.dp)
                }
                val n = review.detections.size
                val parts = listOfNotNull(
                    pluralStringResource(R.plurals.playback_objects, n, n),
                    review.endTime?.let { Format.shortDuration(it - review.startTime) },
                )
                Text(parts.joinToString(" · "), fontSize = 12.sp, color = s.onSurfaceVariant, maxLines = 1)
            }
            if (loading) SmallSpinner(size = 16.dp)
            else Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = s.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        }
        if (open) {
            if (data != null) {
                Column(Modifier.padding(start = if (compact) 10.dp else 12.dp, end = if (compact) 10.dp else 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (o in data.objects) {
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(Modifier.width(2.dp).fillMaxHeight().background(s.outlineVariant))
                            Column(Modifier.padding(start = 10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 4.dp)) {
                                    LabelTag(o.label, fontSize = 12)
                                    o.bestScore?.let { Text("${Math.round(it * 100)}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = s.onSurfaceVariant) }
                                    o.subLabel?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                                }
                                for (e in data.entries(o)) {
                                    val lit = highlight?.let { data.entries[it] == e } ?: false
                                    val color = if (lit) s.primary else s.onSurface
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 30.dp).clickable { onEntry(e.timestamp) },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(e.describe(), Modifier.weight(1f), fontSize = 13.sp, color = color, maxLines = 2)
                                        Spacer(Modifier.width(6.dp))
                                        Text(Format.time(e.timestamp, seconds = true), fontSize = 12.sp, style = MonoDigits,
                                            color = if (lit) s.primary else s.onSurfaceVariant, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // About the height of one object with a few rows, so the card hardly grows once they arrive.
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { SmallSpinner() }
            }
        }
    }
}
