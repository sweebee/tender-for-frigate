package nl.wiebenieuwenhuis.tender.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A server and the cameras to look at there (null: all). */
data class DaySource(val client: FrigateClient, val cameras: List<String>?)

/** What is available on each day, keyed by "yyyy-MM-dd". */
data class DayAvailability(
    val recordings: Set<String> = emptySet(),
    val alerts: Map<String, Int> = emptyMap(),
    val detections: Map<String, Int> = emptyMap(),
) {
    fun merge(o: DayAvailability) = DayAvailability(
        recordings + o.recordings,
        (alerts.keys + o.alerts.keys).associateWith { (alerts[it] ?: 0) + (o.alerts[it] ?: 0) },
        (detections.keys + o.detections.keys).associateWith { (detections[it] ?: 0) + (o.detections[it] ?: 0) },
    )

    companion object {
        fun key(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

        /** Last result per set of sources, so the calendar opens filled in; it refreshes behind it. */
        private val cache = HashMap<String, DayAvailability>()

        private fun cacheKey(sources: List<DaySource>) =
            sources.map { it.client.baseUrl.toString() + "|" + (it.cameras ?: emptyList()).sorted().joinToString(",") }.sorted().joinToString(";")

        fun cached(sources: List<DaySource>): DayAvailability? = synchronized(cache) { cache[cacheKey(sources)] }

        /** Loads recording days and event counts for the given cameras on each server. */
        suspend fun load(sources: List<DaySource>): DayAvailability {
            val parts = coroutineScope {
                sources.map { s ->
                    async {
                        val rec = async { runCatching { s.client.recordingDays(s.cameras) }.getOrNull() }
                        val rev = async { runCatching { s.client.reviewDays(s.cameras) }.getOrNull() }
                        val counts = rev.await() ?: emptyMap()
                        DayAvailability(
                            rec.await() ?: emptySet(),
                            counts.mapValues { it.value.totalAlert },
                            counts.mapValues { it.value.totalDetection },
                        )
                    }
                }.awaitAll()
            }
            val all = parts.fold(DayAvailability()) { a, b -> a.merge(b) }
            synchronized(cache) { cache[cacheKey(sources)] = all }
            return all
        }
    }
}

private fun startOfDay(d: Date): Date = Calendar.getInstance().apply {
    time = d
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.time

/** Month calendar that marks days with recordings and events. `onPick`: local midnight of the day. */
@Composable
fun RecordingCalendarSheet(day: Date, sources: List<DaySource>, onPick: (Date) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var month by remember {
        mutableStateOf(Calendar.getInstance().apply { time = day; set(Calendar.DAY_OF_MONTH, 1) }.let { startOfDay(it.time) })
    }
    var availability by remember { mutableStateOf(DayAvailability.cached(sources) ?: DayAvailability()) }
    var loading by remember { mutableStateOf(DayAvailability.cached(sources) == null) }
    LaunchedEffect(Unit) {
        availability = DayAvailability.load(sources)
        loading = false
    }
    fun select(d: Date) { onPick(startOfDay(d)); onDismiss() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), maxLines = 1) }
                Text(stringResource(R.string.playback_pick_a_day), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
                TextButton(onClick = { select(Date()) }) { Text(stringResource(R.string.today), maxLines = 1) }
            }
            val cal = Calendar.getInstance().apply { time = month }
            val now = Calendar.getInstance()
            val isCurrentMonth = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) && cal.get(Calendar.MONTH) == now.get(Calendar.MONTH)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = Calendar.getInstance().apply { time = month; add(Calendar.MONTH, -1) }.time }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.playback_previous_month))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(monthTitle(month), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (loading) { Spacer(Modifier.width(8.dp)); SmallSpinner(size = 14.dp) }
                }
                IconButton(onClick = { month = Calendar.getInstance().apply { time = month; add(Calendar.MONTH, 1) }.time }, enabled = !isCurrentMonth) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.playback_next_month))
                }
            }
            // Weekdays, Monday first.
            Row {
                val fmt = SimpleDateFormat("EEEEE", Locale.getDefault())
                val c = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }
                repeat(7) {
                    Text(fmt.format(c.time).uppercase(), Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    c.add(Calendar.DAY_OF_MONTH, 1)
                }
            }
            val days = daysInMonth(month)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (week in days.chunked(7)) {
                    Row {
                        for (i in 0 until 7) {
                            val d = week.getOrNull(i)
                            Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                                if (d != null) DayCell(d, day, availability, loading) { select(d) }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                val sec = MaterialTheme.colorScheme.onSurfaceVariant
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).border(1.dp, sec, CircleShape))
                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.playback_recordings), fontSize = 12.sp, color = sec, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(Palette.status.warning, CircleShape))
                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.playback_alerts), fontSize = 12.sp, color = sec, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(sec, CircleShape))
                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.playback_detections), fontSize = 12.sp, color = sec, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: Date, selectedDay: Date, a: DayAvailability, loading: Boolean, onClick: () -> Unit) {
    val key = DayAvailability.key(date)
    val hasRecordings = key in a.recordings
    val alerts = a.alerts[key] ?: 0
    val detections = a.detections[key] ?: 0
    val selected = DayAvailability.key(selectedDay) == key
    val isToday = DayAvailability.key(Date()) == key
    val future = date.after(Date())
    val enabled = !future && (hasRecordings || alerts + detections > 0 || loading)
    val s = MaterialTheme.colorScheme
    val description = buildList {
        add(DateFormat.getDateInstance(DateFormat.FULL).format(date))
        add(L(if (hasRecordings) R.string.playback_a11y_recordings else R.string.playback_a11y_no_recordings))
        if (alerts > 0) add(L(R.string.playback_a11y_alerts, alerts))
        if (detections > 0) add(L(R.string.playback_a11y_detections, detections))
    }.joinToString(", ")
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val bg = Modifier.size(38.dp).let {
            when {
                selected -> it.background(s.onSurface, CircleShape)
                hasRecordings -> it.background(s.surfaceContainerHigh, CircleShape).border(1.dp, s.outlineVariant, CircleShape)
                else -> it
            }
        }
        Box(bg, contentAlignment = Alignment.Center) {
            Text(
                Calendar.getInstance().apply { time = date }.get(Calendar.DAY_OF_MONTH).toString(),
                fontSize = 17.sp, fontWeight = if (selected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    selected -> s.surface
                    isToday -> s.primary
                    hasRecordings -> s.onSurface
                    else -> s.onSurfaceVariant.copy(alpha = 0.45f)
                },
            )
        }
        Row(Modifier.height(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (alerts > 0) Box(Modifier.size(5.dp).background(Palette.status.warning, CircleShape))
            if (detections > 0) Box(Modifier.size(5.dp).background(s.onSurfaceVariant, CircleShape))
        }
    }
}

private fun monthTitle(month: Date): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMMyyyy")
    return SimpleDateFormat(pattern, Locale.getDefault()).format(month).replaceFirstChar { it.uppercase() }
}

/** Dates of the month padded with nulls so the first lands on its weekday (Monday first). */
private fun daysInMonth(month: Date): List<Date?> {
    val c = Calendar.getInstance().apply { time = month; set(Calendar.DAY_OF_MONTH, 1) }
    val leading = (c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
    val count = c.getActualMaximum(Calendar.DAY_OF_MONTH)
    val out = MutableList<Date?>(leading) { null }
    repeat(count) {
        out.add(startOfDay(c.time))
        c.add(Calendar.DAY_OF_MONTH, 1)
    }
    return out
}
