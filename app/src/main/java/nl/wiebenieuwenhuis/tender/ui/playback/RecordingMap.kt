package nl.wiebenieuwenhuis.tender.ui.playback

import androidx.compose.runtime.Immutable
import nl.wiebenieuwenhuis.tender.api.RecordingSegment

/**
 * Maps between wall-clock time and the position in a Frigate VOD playlist, which concatenates
 * recording segments and skips gaps.
 */
@Immutable
class RecordingMap(val spans: List<Span> = emptyList()) {
    data class Span(val wallStart: Double, val wallEnd: Double, val offset: Double)

    val isEmpty: Boolean get() = spans.isEmpty()
    val duration: Double get() = spans.lastOrNull()?.let { it.offset + (it.wallEnd - it.wallStart) } ?: 0.0

    fun playerTime(wall: Double): Double {
        val first = spans.firstOrNull() ?: return 0.0
        if (wall <= first.wallStart) return 0.0
        for (s in spans) {
            if (wall < s.wallStart) return s.offset
            if (wall <= s.wallEnd) return s.offset + (wall - s.wallStart)
        }
        return duration
    }

    fun wallTime(player: Double): Double {
        val first = spans.firstOrNull() ?: return 0.0
        for (s in spans) if (player < s.offset + (s.wallEnd - s.wallStart)) return s.wallStart + maxOf(0.0, player - s.offset)
        return spans.lastOrNull()?.wallEnd ?: first.wallStart
    }

    /**
     * Index of the first span starting at or after `t`. Spans are sorted by start, so this is a
     * binary search: the timelines ask this for every bar on every frame, and a day has
     * thousands of segments.
     */
    private fun firstSpan(t: Double): Int {
        var lo = 0
        var hi = spans.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (spans[mid].wallStart < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Whether any recording overlaps `start..<end`. */
    fun hasRecording(start: Double, end: Double): Boolean {
        // Segments hardly overlap, so the few spans just before `end` are the only ones that can reach into the range.
        var i = firstSpan(end) - 1
        var checked = 0
        while (i >= 0 && checked < 4) {
            if (spans[i].wallEnd > start) return true
            i--; checked++
        }
        return false
    }

    fun hasRecording(wall: Double): Boolean {
        var i = firstSpan(Math.nextUp(wall)) - 1
        var checked = 0
        while (i >= 0 && checked < 4) {
            if (wall >= spans[i].wallStart && wall <= spans[i].wallEnd) return true
            i--; checked++
        }
        return false
    }

    companion object {
        fun of(segments: List<RecordingSegment>, from: Double, to: Double): RecordingMap {
            val out = ArrayList<Span>(segments.size)
            var offset = 0.0
            for (s in segments.sortedBy { it.startTime }) {
                val a = maxOf(s.startTime, from)
                val b = minOf(s.endTime, to)
                if (b <= a) continue
                // Segments can overlap by a moment; the VOD still plays each in full.
                out.add(Span(a, b, offset))
                offset += b - a
            }
            return RecordingMap(out)
        }

        /** One span over the whole range: the timeline then draws motion everywhere. */
        fun full(start: Double, end: Double) = RecordingMap(listOf(Span(start, end, 0.0)))
    }
}
