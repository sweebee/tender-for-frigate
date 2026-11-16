package nl.wiebenieuwenhuis.tender.ui.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.MotionActivity
import nl.wiebenieuwenhuis.tender.api.RecordingSegment
import nl.wiebenieuwenhuis.tender.api.ReviewItem
import nl.wiebenieuwenhuis.tender.api.Severity
import nl.wiebenieuwenhuis.tender.ui.components.MonoDigits
import nl.wiebenieuwenhuis.tender.ui.components.labelIcon
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// MARK: - Shared

/** Colours of a timeline, on a card or over a video. */
internal data class TimelineColors(
    val accent: Color, val text: Color, val textSecondary: Color, val controlBorder: Color, val motion: Color, val onLabel: Color,
)

@Composable
internal fun timelineColors(onVideo: Boolean): TimelineColors {
    val s = MaterialTheme.colorScheme
    return TimelineColors(
        accent = s.primary,
        text = if (onVideo) Color.White else s.onSurface,
        textSecondary = if (onVideo) Color.White.copy(alpha = 0.7f) else s.onSurfaceVariant,
        controlBorder = if (onVideo) Color.White.copy(alpha = 0.25f) else s.outlineVariant,
        motion = Palette.status.motion,
        onLabel = Palette.status.onLabel,
    )
}

/** The label icons the timelines draw, by label. */
@Composable
internal fun rememberLabelPainters(reviews: List<ReviewItem>): Map<String, Painter> {
    val labels = reviews.mapNotNull { it.labels.firstOrNull() }.toSortedSet()
    val out = HashMap<String, Painter>()
    for (l in labels) key(l) { labelIcon(l)?.let { out[l] = rememberVectorPainter(it) } }
    return out
}

/** A label's icon (or its first letter) on its colour, in `box` (left, top, side). */
internal fun DrawScope.drawLabelBox(
    label: String, left: Float, top: Float, side: Float, painters: Map<String, Painter>, colors: TimelineColors,
    measurer: androidx.compose.ui.text.TextMeasurer, alpha: Float = 1f, drawBackground: Boolean = true,
) {
    if (drawBackground) {
        drawRoundRect(Palette.labelColor(label).copy(alpha = alpha), Offset(left, top), Size(side, side), CornerRadius(5.dp.toPx()))
    }
    val p = painters[label]
    val icon = 11.dp.toPx()
    if (p != null) {
        translate(left + (side - icon) / 2, top + (side - icon) / 2) {
            with(p) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(colors.onLabel)) }
        }
    } else {
        val letter = Format.labelName(label).take(1).uppercase()
        val layout = measurer.measure(letter, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.onLabel))
        drawText(layout, topLeft = Offset(left + (side - layout.size.width) / 2, top + (side - layout.size.height) / 2))
    }
}

/**
 * Keeps a timeline moving after the finger lets go, slowing down like a scroll view. A new touch
 * stops it.
 */
internal class TimelineFling(private val scope: CoroutineScope) {
    private var job: Job? = null

    /** `velocity` is in timeline seconds per second. Calls `step` every frame and `end` once where it came to rest. */
    fun start(from: Double, velocity: Double, clamp: (Double) -> Double, step: (Double) -> Unit, end: (Double) -> Unit) {
        cancel()
        job = scope.launch {
            var t = from
            var v = velocity
            val frame = 1.0 / 60
            // A scroll view's normal deceleration: speed drops 0.2 % every millisecond.
            val decay = 0.998.pow(frame * 1000)
            while (abs(v) * frame > 0.0001) {
                delay(16)
                val next = clamp(t + v * frame)
                if (next == t) break // hit the start or end of the range
                t = next
                v *= decay
                step(t)
                if (abs(v) < abs(velocity) * 0.02) break
            }
            end(t)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Speed (dp per second) below which a release just stops. */
        const val MIN_SPEED_DP = 300f
    }
}

/** What a timeline does with a touch: drag, tap, or pinch. */
internal class TimelineGestureHandler {
    var onDown: () -> Unit = {}
    var onDragStart: () -> Unit = {}
    /** Total movement since the drag started, in px. */
    var onDrag: (Offset) -> Unit = {}
    /** Total movement and the release speed, px and px/s. */
    var onDragEnd: (Offset, Offset) -> Unit = { _, _ -> }
    var onTap: (Offset) -> Unit = {}
    var onPinchStart: () -> Unit = {}
    /** Distance between the fingers relative to when they came down. */
    var onPinch: (Float) -> Unit = {}
    var onPinchEnd: () -> Unit = {}
}

/** A drag along one axis scrubs, a tap selects, two fingers pinch; a quick swipe flings. */
internal suspend fun PointerInputScope.timelineGestures(vertical: Boolean, h: () -> TimelineGestureHandler) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        h().onDown()
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var total = Offset.Zero
        var dragging = false
        var pinching = false
        var pinchStart = 0f
        val slop = viewConfiguration.touchSlop
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                val d = (pressed[0].position - pressed[1].position).getDistance()
                if (!pinching) {
                    pinching = true
                    pinchStart = d
                    h().onPinchStart()
                } else if (pinchStart > 0) {
                    h().onPinch(d / pinchStart)
                }
                event.changes.forEach { it.consume() }
                continue
            }
            if (pinching) continue // the finger left after a pinch: no drag
            val c = pressed[0]
            tracker.addPosition(c.uptimeMillis, c.position)
            total += c.positionChange()
            val along = if (vertical) abs(total.y) else abs(total.x)
            if (!dragging && along > slop) {
                dragging = true
                h().onDragStart()
            }
            if (dragging) {
                h().onDrag(total)
                c.consume()
            }
        }
        when {
            pinching -> h().onPinchEnd()
            dragging -> {
                val v = tracker.calculateVelocity()
                h().onDragEnd(total, Offset(v.x, v.y))
            }
            else -> h().onTap(down.position)
        }
    }
}

/** Most motion in buckets starting in `from..<to`; `motion` is sorted by start time. */
internal fun maxMotionStarting(motion: List<MotionActivity>, from: Double, to: Double): Double {
    var lo = 0
    var hi = motion.size
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (motion[mid].startTime < from) lo = mid + 1 else hi = mid
    }
    var best = 0.0
    while (lo < motion.size && motion[lo].startTime < to) {
        best = max(best, motion[lo].motion ?: 0.0)
        lo++
    }
    return best
}

// MARK: - Horizontal

object HorizontalTimelineScale {
    /** Seconds per dp. */
    const val MIN = 0.25
    const val MAX = 30.0
}

/**
 * Horizontal timeline for landscape playback, in the style of NVR apps: the playhead sits in the
 * middle and the day slides underneath. Bars show motion; events are drawn in their label colour.
 * Drag to scrub, pinch or use the buttons to zoom, tap to jump. `scale`: seconds per dp.
 */
@Composable
fun HorizontalTimeline(
    dayStart: Double,
    dayEnd: Double,
    wallTime: () -> Double,
    motion: List<MotionActivity>,
    reviews: List<ReviewItem>,
    map: RecordingMap,
    scale: Double,
    onScaleChange: (Double) -> Unit,
    onScrub: (Double) -> Unit,
    onScrubEnd: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
    onVideo: Boolean = false,
    onInteract: () -> Unit = {},
    onScrubCancel: () -> Unit = {},
    onSelect: ((ReviewItem) -> Unit)? = null,
) {
    val colors = timelineColors(onVideo)
    val painters = rememberLabelPainters(reviews)
    val measurer = rememberTextMeasurer(64)
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val fling = remember { TimelineFling(scope) }
    val iconRow = if (onSelect == null) 0.dp else 22.dp
    val iconSize = 18.dp
    val sortedReviews = remember(reviews) { reviews.sortedBy { it.startTime } }
    var width by remember { mutableStateOf(0f) }
    var dragBase by remember { mutableStateOf<Double?>(null) }
    var pinchBase by remember { mutableStateOf<Double?>(null) }

    val s = rememberUpdatedState(scale)
    fun clamp(t: Double) = min(max(t, dayStart), dayEnd)
    fun secPerPx() = s.value / density

    /** Where event icons go: at each event's start, leaving out icons that would overlap. */
    fun iconPositions(w: Float): List<Pair<Float, ReviewItem>> {
        val center = w / 2
        val now = wallTime()
        val side = iconSize.value * density
        val out = ArrayList<Pair<Float, ReviewItem>>()
        var lastX = Float.NEGATIVE_INFINITY
        for (r in sortedReviews) {
            val x = center + ((r.startTime - now) / secPerPx()).toFloat()
            if (x <= -side || x >= w + side) continue
            if (x - lastX < side + 3 * density) continue
            out.add(x to r)
            lastX = x
        }
        return out
    }

    val handler = remember { TimelineGestureHandler() }.apply {
        onDown = { onInteract(); fling.cancel() }
        onDragStart = { dragBase = wallTime() }
        onDrag = { total ->
            // Dragging right moves back in time.
            onScrub(clamp((dragBase ?: wallTime()) - total.x * secPerPx()))
        }
        onDragEnd = { total, v ->
            val released = clamp((dragBase ?: wallTime()) - total.x * secPerPx())
            dragBase = null
            val snap = s.value * 24
            if (abs(v.x) > TimelineFling.MIN_SPEED_DP * density) {
                fling.start(released, -v.x * secPerPx(), ::clamp, onScrub) { onScrubEnd(it, snap) }
            } else onScrubEnd(released, snap)
        }
        onTap = { p ->
            onInteract()
            val side = iconSize.value * density
            val hit = if (onSelect != null && p.y >= 14 * density && p.y <= (22 + iconRow.value) * density)
                iconPositions(width).minByOrNull { abs(it.first - p.x) }?.takeIf { abs(it.first - p.x) <= side }?.second else null
            if (hit != null) {
                onScrubCancel()
                onSelect?.invoke(hit)
            } else {
                onScrubEnd(clamp(wallTime() + (p.x - width / 2) * secPerPx()), s.value * 24)
            }
        }
        onPinchStart = { pinchBase = s.value; dragBase?.let { onScrubCancel() }; dragBase = null }
        onPinch = { k -> pinchBase?.let { onScaleChange(min(max(it / k, HorizontalTimelineScale.MIN), HorizontalTimelineScale.MAX)) } }
        onPinchEnd = { pinchBase = null }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(30.dp + 22.dp + iconRow)
            .clipToBounds()
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(Unit) { timelineGestures(vertical = false) { handler } },
    ) {
        val now = wallTime()
        val spp = scale / density
        val w = size.width
        val center = w / 2
        val barWidth = 3.dp.toPx()
        val barArea = 30.dp.toPx()
        val step = barWidth * spp
        val maxMotion = max(motion.maxOfOrNull { it.motion ?: 0.0 } ?: 1.0, 1.0)
        val baseline = size.height
        val barBase = baseline - 4.dp.toPx()

        if (onSelect != null) {
            val side = iconSize.toPx()
            for ((x, r) in iconPositions(w)) {
                drawLabelBox(r.labels.firstOrNull() ?: "", x - side / 2, 20.dp.toPx(), side, painters, colors, measurer)
            }
        }

        // Align buckets to multiples of `step` so bars do not shimmer while playing.
        val leftTime = now - center * spp
        var t = floor(leftTime / step) * step
        while (true) {
            val x = center + ((t - now) / spp).toFloat()
            if (x > w) break
            val bucketEnd = t + step
            if (bucketEnd > dayStart && t < dayEnd) {
                val recorded = map.hasRecording(t, bucketEnd)
                // Blue line under the bars where video was recorded, so gaps are visible.
                if (recorded) drawRect(colors.accent.copy(alpha = 0.7f), Offset(x, baseline - 2.dp.toPx()), Size(barWidth, 2.dp.toPx()))
                val r = reviews.firstOrNull { bucketEnd > it.startTime && t < (it.endTime ?: dayEnd) }
                if (r != null) {
                    // As in Frigate: the event as a tinted band, the motion in it still as a waveform (in the event's colour).
                    val color = Palette.labelColor(r.labels.firstOrNull() ?: "")
                    drawRect(color.copy(alpha = 0.14f), Offset(x, barBase - barArea), Size(barWidth, barArea))
                    val level = maxMotionStarting(motion, t - 15, bucketEnd)
                    val h = max(4.dp.toPx(), (level / maxMotion).toFloat() * barArea * 0.8f)
                    drawRoundRect(color, Offset(x, barBase - h), Size(barWidth - 1.dp.toPx(), h), CornerRadius(1.dp.toPx()))
                } else if (recorded) {
                    val level = maxMotionStarting(motion, t - 15, bucketEnd)
                    val h = max(3.dp.toPx(), (level / maxMotion).toFloat() * barArea * 0.8f)
                    drawRoundRect(if (level > 0) colors.motion.copy(alpha = 0.75f) else colors.controlBorder,
                        Offset(x, barBase - h), Size(barWidth - 1.dp.toPx(), h), CornerRadius(1.dp.toPx()))
                }
            }
            t += step
        }

        // Time labels roughly every 110 dp.
        val candidates = listOf(30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0, 7200.0, 10800.0)
        val interval = candidates.firstOrNull { it / scale >= 110 } ?: 21600.0
        var lt = ceil(leftTime / interval) * interval
        val style = MonoDigits.copy(fontSize = 11.sp, color = colors.textSecondary)
        while (true) {
            val x = center + ((lt - now) / spp).toFloat()
            if (x > w) break
            if (lt >= dayStart && lt <= dayEnd + interval) {
                drawRect(colors.textSecondary, Offset(x, 13.dp.toPx()), Size(1.dp.toPx(), 4.dp.toPx()))
                val layout = measurer.measure(Format.time(lt), style)
                drawText(layout, topLeft = Offset(x - layout.size.width / 2, 5.dp.toPx() - layout.size.height / 2f))
            }
            lt += interval
        }
    }
}

/** Zoom buttons under a horizontal timeline. */
@Composable
fun HorizontalZoomButtons(scale: Double, onScaleChange: (Double) -> Unit, tint: Color, onUse: () -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onScaleChange(min(scale * 2, HorizontalTimelineScale.MAX)); onUse() }, Modifier.size(36.dp),
            enabled = scale < HorizontalTimelineScale.MAX) {
            Icon(Icons.Filled.ZoomOut, stringResource(R.string.playback_zoom_out), tint = tint)
        }
        IconButton(onClick = { onScaleChange(max(scale / 2, HorizontalTimelineScale.MIN)); onUse() }, Modifier.size(36.dp),
            enabled = scale > HorizontalTimelineScale.MIN) {
            Icon(Icons.Filled.ZoomIn, stringResource(R.string.playback_zoom_in), tint = tint)
        }
    }
}

// MARK: - Vertical

/**
 * Vertical timeline, newest at the top, with motion as bar widths and events as tinted rows.
 * Dragging scrubs the playhead, which sits in the vertical centre. `compact`: a narrow column
 * without events (Motion review on a phone): no event strip and a short time on the playhead.
 * `segments`: recording segments, sorted by start; zoomed in, motion comes from these, each at
 * its exact time (Frigate's activity buckets round down to the bucket they start in).
 */
@Composable
fun PlaybackTimeline(
    dayStart: Double,
    dayEnd: Double,
    wallTime: () -> Double,
    motion: List<MotionActivity>,
    reviews: List<ReviewItem>,
    map: RecordingMap,
    onScrub: (Double) -> Unit,
    onScrubEnd: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
    annotationOffset: Double = 0.0,
    onSelect: (ReviewItem) -> Unit = {},
    segments: List<RecordingSegment> = emptyList(),
    onScrubCancel: () -> Unit = {},
    compact: Boolean = false,
) {
    val colors = timelineColors(false)
    val painters = rememberLabelPainters(reviews)
    val measurer = rememberTextMeasurer(64)
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val fling = remember { TimelineFling(scope) }
    /** Seconds per row: the zoom, set by the buttons or by pinching. */
    var secondsPerRow by remember { mutableDoubleStateOf(60.0) }
    var dragBase by remember { mutableStateOf<Double?>(null) }
    var pinchBase by remember { mutableStateOf<Double?>(null) }
    var height by remember { mutableStateOf(0f) }
    val zooms = listOf(10.0, 30.0, 60.0, 120.0)
    val pinchSteps = listOf(10.0, 15.0, 20.0, 30.0, 45.0, 60.0, 90.0, 120.0)
    val rowHeightDp = 5f
    val spr = rememberUpdatedState(secondsPerRow)
    fun secPerPx() = spr.value / (rowHeightDp * density)
    fun clamp(t: Double) = min(max(t, dayStart), dayEnd)
    // "10:15 AM" needs more room than "10:15".
    val gutterDp = (if (compact) 60f else 64f) + (if (Format.uses12Hour) 16f else 0f)
    val stripDp = 18f
    val barsStartDp = if (compact) gutterDp + 2 else gutterDp + stripDp + 6
    val sortedNewest = remember(reviews) { reviews.sortedByDescending { it.startTime } }
    val dayMaxMotion = remember(motion) { max(motion.maxOfOrNull { it.motion ?: 0.0 } ?: 1.0, 1.0) }
    val segmentMax = remember(segments) { segments.maxOfOrNull { it.motion ?: 0.0 } ?: 0.0 }

    /** Where an event sits, newest on top: from its end down to its start (px). */
    fun span(r: ReviewItem, center: Float, now: Double): Pair<Float, Float> {
        val start = r.startTime + annotationOffset
        val end = (r.endTime ?: dayEnd) + annotationOffset
        val top = center + ((now - end) / secPerPx()).toFloat()
        val bottom = center + ((now - start) / secPerPx()).toFloat()
        val strip = stripDp * density
        // Short events still get a block big enough for their icon and a finger.
        return if (bottom - top < strip) (bottom - strip) to bottom else top to bottom
    }

    fun reviewAt(p: Offset): ReviewItem? {
        if (compact) return null
        if (p.x < (gutterDp - 10) * density || p.x > (barsStartDp + 4) * density) return null
        val center = height / 2
        val now = wallTime()
        return reviews.map { it to span(it, center, now) }
            .filter { p.y >= it.second.first - 6 * density && p.y <= it.second.second + 6 * density }
            .minByOrNull { abs((it.second.first + it.second.second) / 2 - p.y) }?.first
    }

    val handler = remember { TimelineGestureHandler() }.apply {
        onDown = { fling.cancel() }
        onDragStart = { dragBase = wallTime() }
        // Dragging down moves to later times (newest is on top).
        onDrag = { total -> onScrub(clamp((dragBase ?: wallTime()) + total.y * secPerPx())) }
        onDragEnd = { total, v ->
            val base = dragBase
            dragBase = null
            if (base == null) onScrubCancel() else {
                val released = clamp(base + total.y * secPerPx())
                val snap = secPerPx() * 24 * density
                // A quick swipe keeps going, like scrolling.
                if (abs(v.y) > TimelineFling.MIN_SPEED_DP * density) {
                    fling.start(released, v.y * secPerPx(), ::clamp, onScrub) { onScrubEnd(it, snap) }
                } else onScrubEnd(released, snap)
            }
        }
        onTap = { p -> reviewAt(p)?.let(onSelect) }
        onPinchStart = { pinchBase = spr.value; if (dragBase != null) onScrubCancel(); dragBase = null }
        onPinch = { k ->
            pinchBase?.let { base ->
                // To the nearest fine step, not any value: rows then line up with the 10 s segments
                // and 30 s motion buckets, so the bars do not jumble.
                val wanted = base / k
                val stepValue = pinchSteps.minBy { abs(ln(it / wanted)) }
                if (stepValue != secondsPerRow) secondsPerRow = stepValue
            }
        }
        onPinchEnd = { pinchBase = null }
    }

    BoxWithConstraints(modifier.clipToBounds()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { height = it.height.toFloat() }
                .pointerInput(Unit) { timelineGestures(vertical = true) { handler } },
        ) {
            val now = wallTime()
            val spp = secondsPerRow / (rowHeightDp * density)
            val rowH = rowHeightDp.dp.toPx()
            val gutter = gutterDp.dp.toPx()
            val barsStart = barsStartDp.dp.toPx()
            val center = size.height / 2
            val barAreaWidth = if (compact) size.width - barsStart - 6.dp.toPx() else size.width - gutter - barsStart
            val rows = (size.height / rowH).toInt() + 2
            // Align rows to bucket boundaries so they do not shimmer while playing.
            val topTime = now + center * spp
            val topRowTime = ceil(topTime / secondsPerRow) * secondsPerRow
            val usesSegments = secondsPerRow <= 30 && segments.isNotEmpty()
            // Segment and bucket motion use different units: match the busiest segment of the day to
            // the busiest bucket, so zooming in keeps the bars about as wide.
            val segmentFactor = if (usesSegments && segmentMax > 0) dayMaxMotion / segmentMax else 1.0
            val labelEvery = when {
                secondsPerRow < 15 -> 300.0
                secondsPerRow < 90 -> 900.0
                secondsPerRow < 200 -> 1800.0
                else -> 3600.0
            }
            val tickColor = Color(0xFF6B7480)
            val textStyle = MonoDigits.copy(fontSize = 11.sp, color = colors.textSecondary)

            for (i in 0 until rows) {
                val rowEnd = topRowTime - i * secondsPerRow
                val rowStart = rowEnd - secondsPerRow
                val rowY = center + ((now - rowEnd) / spp).toFloat()
                if (rowY <= -rowH || rowY >= size.height) continue
                if (rowEnd <= dayStart || rowStart >= dayEnd) continue
                // Fade rows near the edges.
                val edge = min(rowY, size.height - rowY) / 40.dp.toPx()
                val alpha = min(1f, max(0.25f, edge))
                val recorded = map.hasRecording(rowStart, rowEnd)
                // Blue line along the left edge where video was recorded, so gaps are visible.
                if (recorded) drawRect(colors.accent.copy(alpha = 0.7f * alpha), Offset(gutter - 7.dp.toPx(), rowY), Size(3.dp.toPx(), rowH))
                // Bar width follows the motion in this row; events add their label colour and a tint.
                val level = if (usesSegments) segmentMotion(segments, rowStart, rowEnd) * segmentFactor
                else maxMotionOverlapping(motion, rowStart, rowEnd)
                val motionWidth = (min(1.0, level / dayMaxMotion) * barAreaWidth * 0.9).toFloat()
                val r = reviews.firstOrNull { rowEnd > it.startTime + annotationOffset && rowStart < (it.endTime ?: dayEnd) + annotationOffset }
                val barY = rowY + 1.dp.toPx()
                val barH = 3.dp.toPx()
                if (r != null) {
                    val color = Palette.labelColor(r.labels.firstOrNull() ?: "")
                    drawRect(color.copy(alpha = 0.08f * alpha), Offset(barsStart, rowY), Size(barAreaWidth, rowH))
                    val w = max(motionWidth, 14.dp.toPx())
                    drawRoundRect(color.copy(alpha = alpha), Offset(barsStart + (barAreaWidth - w) / 2, barY), Size(w, barH), CornerRadius(barH / 2))
                } else if (recorded) {
                    val w = max(4.dp.toPx(), motionWidth)
                    drawRoundRect((if (level > 0) colors.motion else colors.controlBorder).copy(alpha = alpha),
                        Offset(barsStart + (barAreaWidth - w) / 2, barY), Size(w, barH), CornerRadius(barH / 2))
                }
                // Ticks and labels where a round time falls in this row.
                val minor = labelEvery / 3
                val tick = floor(rowEnd / minor) * minor
                if (tick > rowStart - 0.001) {
                    val tickY = center + ((now - tick) / spp).toFloat()
                    if (tick % labelEvery < 0.5) {
                        drawRect(tickColor.copy(alpha = alpha), Offset(0f, tickY), Size(14.dp.toPx(), 1.dp.toPx()))
                        val layout = measurer.measure(Format.time(tick), textStyle.copy(color = colors.textSecondary.copy(alpha = alpha)))
                        drawText(layout, topLeft = Offset(20.dp.toPx(), tickY - layout.size.height / 2f))
                    } else {
                        drawRect(tickColor.copy(alpha = alpha), Offset(0f, tickY), Size(7.dp.toPx(), 1.dp.toPx()))
                    }
                }
            }

            // Event blocks in the strip, as long as the event lasted, with the label icon on top.
            if (!compact) {
                val strip = stripDp.dp.toPx()
                var lastIconY = Float.NEGATIVE_INFINITY
                for (r in sortedNewest) {
                    val (top, bottom) = span(r, center, now)
                    if (bottom <= 0 || top >= size.height) continue
                    val label = r.labels.firstOrNull() ?: ""
                    val color = Palette.labelColor(label)
                    drawRoundRect(color.copy(alpha = if (r.severity == Severity.Alert) 1f else 0.8f), Offset(gutter, top), Size(strip, bottom - top),
                        CornerRadius(5.dp.toPx()))
                    if (top - lastIconY < strip + 2.dp.toPx()) continue
                    lastIconY = top
                    drawLabelBox(label, gutter, top, strip, painters, colors, measurer, drawBackground = false)
                }
            }
        }

        // Playhead
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(start = gutterDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).height(2.dp).background(colors.accent))
            PlayheadLabel(wallTime, !compact, Modifier.padding(start = 6.dp, end = 8.dp))
        }

        if (!compact || maxHeight > 260.dp) {
            Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZoomButton(Icons.Filled.ZoomIn, stringResource(R.string.playback_zoom_in), secondsPerRow > zooms.first() + 0.5) {
                    secondsPerRow = zooms.lastOrNull { it < secondsPerRow - 0.5 } ?: zooms.first()
                }
                ZoomButton(Icons.Filled.ZoomOut, stringResource(R.string.playback_zoom_out), secondsPerRow < zooms.last() - 0.5) {
                    secondsPerRow = zooms.firstOrNull { it > secondsPerRow + 0.5 } ?: zooms.last()
                }
            }
        }
    }
}

/** The time on the playhead; reads the moving time itself, so only it redraws. */
@Composable
private fun PlayheadLabel(wallTime: () -> Double, seconds: Boolean, modifier: Modifier = Modifier) {
    Text(
        Format.time(wallTime(), seconds), modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
        style = MonoDigits,
    )
}

@Composable
private fun ZoomButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    IconButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) { Icon(icon, label) }
}

/** The most motion in 30 s buckets that overlap `from..<to`; buckets are sorted by start time. */
private fun maxMotionOverlapping(buckets: List<MotionActivity>, from: Double, to: Double, scale: Double = 30.0): Double {
    var lo = 0
    var hi = buckets.size
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (buckets[mid].startTime > from - scale) hi = mid else lo = mid + 1
    }
    var best = 0.0
    var i = lo
    while (i < buckets.size && buckets[i].startTime < to) {
        best = max(best, buckets[i].motion ?: 0.0)
        i++
    }
    return best
}

private fun segmentMotion(segments: List<RecordingSegment>, from: Double, to: Double): Double {
    // First segment that may end after `from` (segments last up to about ten seconds).
    var lo = 0
    var hi = segments.size
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (segments[mid].startTime > from - 20) hi = mid else lo = mid + 1
    }
    var best = 0.0
    var i = lo
    while (i < segments.size && segments[i].startTime < to) {
        if (segments[i].endTime > from) best = max(best, segments[i].motion ?: 0.0)
        i++
    }
    return best
}
