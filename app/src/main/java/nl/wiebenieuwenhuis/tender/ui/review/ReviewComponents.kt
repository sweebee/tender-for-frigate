package nl.wiebenieuwenhuis.tender.ui.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.Severity
import nl.wiebenieuwenhuis.tender.api.nowSeconds
import nl.wiebenieuwenhuis.tender.api.toDate
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.components.LabelIcon
import nl.wiebenieuwenhuis.tender.ui.components.MonoDigits
import nl.wiebenieuwenhuis.tender.ui.components.RemoteImage
import nl.wiebenieuwenhuis.tender.ui.components.ServerBadge
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** GenAI's verdict on a review item, as Frigate marks it: "Needs review" or "Security concern". Level 0 shows nothing. */
@Composable
fun ThreatChip(level: Int, modifier: Modifier = Modifier, onDark: Boolean = false) {
    if (level <= 0) return
    val concern = level >= 2
    val color = if (concern) Palette.status.destructive else Palette.status.warning
    Row(
        modifier
            .then(if (onDark) Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape) else Modifier)
            .background(color.copy(alpha = 0.16f), CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (concern) Icons.Filled.Warning else Icons.Filled.PersonSearch, null, Modifier.size(12.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(if (concern) R.string.review_security_concern else R.string.review_needs_review),
            color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

/** "LIVE" while the item is still going, otherwise how long it lasted. */
@Composable
private fun DurationBadge(entry: ReviewEntry, fontSize: Int = 10) {
    val item = entry.item
    Text(
        if (item.inProgress) stringResource(R.string.review_live) else Format.reviewDuration(item),
        Modifier
            .background(if (item.inProgress) Palette.status.recording else Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        color = Color.White, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = MonoDigits,
    )
}

/** The dot of an unreviewed item; a spinner while it is being marked or deleted; a check box while selecting. */
@Composable
private fun StatusMark(reviewed: Boolean, busy: Boolean, selecting: Boolean, selected: Boolean, onDark: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    when {
        busy -> SmallSpinner(size = 16.dp)
        selecting -> Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, Modifier.size(22.dp),
            tint = if (selected) accent else if (onDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        !reviewed -> {
            val label = stringResource(R.string.review_unreviewed_dot)
            Box(Modifier.size(10.dp).background(accent, CircleShape)
                .then(if (onDark) Modifier.border(1.dp, Color.Black.copy(alpha = 0.4f), CircleShape) else Modifier)
                .semantics { contentDescription = label })
        }
    }
}

fun cameraName(conn: ServerConnection?, camera: String): String = conn?.camera(camera)?.displayName ?: Format.prettyName(camera)

/** A review item as a row, for phones. */
@Composable
fun ReviewRow(
    entry: ReviewEntry,
    conn: ServerConnection,
    showServer: Boolean,
    busy: Boolean,
    selecting: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val item = entry.item
    val camName = cameraName(conn, item.camera)
    val title = item.metadata?.trimmedTitle
    val reviewed = item.isReviewed
    fun meta(): String = buildList {
        if (showServer && title == null) add(conn.server.name)
        add(Format.time(item.startTime))
        val zones = item.zones.map { Format.prettyName(it) }
        if (zones.isNotEmpty()) add(zones.joinToString(", ").lowercase(Locale.getDefault()))
        if (item.subLabels.isNotEmpty()) add(item.subLabels.joinToString(", "))
    }.joinToString(" · ")

    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(112.dp, 64.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                RemoteImage(conn.client?.thumbnailUrl(item), Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) { DurationBadge(entry) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (title != null) {
                    // GenAI's summary first, as on Frigate's review cards; the camera and time under it.
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val level = item.metadata?.threatLevel
                    if (!reviewed && level != null && level > 0) ThreatChip(level)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        item.labels.take(3).forEach { LabelIcon(it) }
                        if (showServer) ServerBadge(conn.server, 18.dp)
                        Text("$camName · ${meta()}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { item.labels.take(4).forEach { LabelIcon(it) } }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (showServer) ServerBadge(conn.server, 18.dp)
                        Text(camName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(meta(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.padding(end = 4.dp), contentAlignment = Alignment.Center) {
                StatusMark(reviewed, busy, selecting, selected, onDark = false)
            }
        }
    }
}

/** A review item as a large picture, for the grid on tablets. */
@Composable
fun ReviewTile(
    entry: ReviewEntry,
    conn: ServerConnection,
    showServer: Boolean,
    busy: Boolean,
    selecting: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val item = entry.item
    val camName = cameraName(conn, item.camera)
    val title = item.metadata?.trimmedTitle
    val reviewed = item.isReviewed
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Color.Black)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        RemoteImage(conn.client?.thumbnailUrl(item), Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (title == null) 56.dp else 80.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
        Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            item.labels.take(4).forEach { LabelIcon(it, size = 22.dp) }
            val level = item.metadata?.threatLevel
            if (!reviewed && level != null && level > 0) ThreatChip(level, onDark = true)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { StatusMark(reviewed, busy, selecting, selected, onDark = true) }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // GenAI's summary above the camera and time, as on Frigate's review cards.
            if (title != null) Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showServer) ServerBadge(conn.server, 18.dp)
                Text(camName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(Format.time(item.startTime), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                DurationBadge(entry, 11)
            }
        }
    }
}

/**
 * Vertical timeline beside the review grid on tablets, like Frigate's review page: now at the
 * top, every item as a bar as long as it lasted, the part that is in view highlighted. A tap or
 * drag scrolls the grid to the item that started closest to that moment.
 */
@Composable
fun ReviewTimeline(entries: List<ReviewEntry>, visibleKeys: Set<String>, onJump: (ReviewEntry) -> Unit, modifier: Modifier = Modifier) {
    val oldest = entries.minOfOrNull { it.item.startTime } ?: return
    val now = nowSeconds()
    val top = ceil(now / 3600) * 3600
    val bottom = minOf(floor(oldest / 3600) * 3600, top - 3600)
    val span = top - bottom
    val measurer = rememberTextMeasurer()
    val accent = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val recording = Palette.status.recording
    val warning = Palette.status.warning
    var lastJump by remember { mutableStateOf<String?>(null) }

    fun jumpTo(y: Float, h: Float) {
        val t = top - (y.coerceIn(0f, h) / h) * span
        val nearest = entries.minByOrNull { abs(it.item.startTime - t) } ?: return
        if (nearest.key == lastJump) return
        lastJump = nearest.key
        onJump(nearest)
    }

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(entries) {
                    detectTapGestures { o -> lastJump = null; jumpTo(o.y, size.height.toFloat()) }
                }
                .pointerInput(entries) {
                    detectDragGestures(onDragEnd = { lastJump = null }) { change, _ -> jumpTo(change.position.y, size.height.toFloat()) }
                },
        ) {
            val h = size.height
            fun y(t: Double): Float = ((top - t) / span * h).toFloat()

            // The part of the list in view.
            val shown = entries.filter { it.key in visibleKeys }
            if (shown.isNotEmpty()) {
                val newest = shown.maxOf { it.item.endTime ?: now }
                val first = shown.minOf { it.item.startTime }
                val y0 = y(newest) - 4.dp.toPx()
                val y1 = y(first) + 4.dp.toPx()
                val hh = max(10.dp.toPx(), y1 - y0)
                drawRoundRect(accent.copy(alpha = 0.16f), Offset(0f, y0), Size(size.width, hh), CornerRadius(6.dp.toPx()))
                drawRoundRect(accent.copy(alpha = 0.5f), Offset(0f, y0), Size(size.width, hh), CornerRadius(6.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }

            val minutes = span / 60
            // A label every 5, 10, 15 or 30 minutes, or 1 to 24 hours: the finest that keeps labels 36 dp apart.
            val perMinute = h / minutes
            val step = listOf(5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 180.0, 360.0, 720.0, 1440.0)
                .firstOrNull { it * perMinute >= 36.dp.toPx() } ?: 1440.0
            val tickStep = minOf(step, 60.0)
            val barX = size.width - 14.dp.toPx()
            var t = floor(top / (tickStep * 60)) * tickStep * 60
            val cal = Calendar.getInstance()
            while (t >= bottom) {
                val yy = y(t)
                cal.timeInMillis = (t * 1000).toLong()
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                val minute = cal.get(Calendar.MINUTE)
                val labelled = ((hour * 60 + minute).toDouble() % step) == 0.0
                val onHour = minute == 0
                val len = (if (onHour) 12 else if (labelled) 9 else 6).dp.toPx()
                drawLine(tickColor.copy(alpha = if (labelled) 0.8f else 0.4f), Offset(barX - len, yy), Offset(barX - 3.dp.toPx(), yy), 1.dp.toPx())
                if (labelled && yy > 6.dp.toPx() && yy < h - 6.dp.toPx()) {
                    // At midnight the day, otherwise the time; hours stand out from the minutes between them.
                    val text = if (hour == 0 && minute == 0) Format.shortDay(t.toDate()) else String.format(Locale.US, "%02d:%02d", hour, minute)
                    val layout = measurer.measure(text, TextStyle(fontSize = 10.sp, fontWeight = if (onHour) FontWeight.SemiBold else FontWeight.Normal,
                        color = labelColor, fontFeatureSettings = "tnum"))
                    drawText(layout, topLeft = Offset(2.dp.toPx(), yy - layout.size.height / 2f))
                }
                t -= tickStep * 60
            }

            for (e in entries) {
                val item = e.item
                val yTop = y(item.endTime ?: now)
                val yBottom = y(item.startTime)
                val bh = max(4.dp.toPx(), yBottom - yTop)
                val color = if (item.severity == Severity.Alert) recording else warning
                drawRoundRect(color.copy(alpha = if (item.isReviewed) 0.45f else 1f), Offset(barX - 3.dp.toPx(), yBottom - bh),
                    Size(6.dp.toPx(), bh), CornerRadius(3.dp.toPx()))
            }
        }
    }
}
