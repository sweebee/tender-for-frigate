package nl.wiebenieuwenhuis.tender.ui.playback

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.CameraConfig
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.MotionActivity
import nl.wiebenieuwenhuis.tender.api.RecordingSegment
import nl.wiebenieuwenhuis.tender.api.ReviewItem
import nl.wiebenieuwenhuis.tender.api.nowSeconds
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.PlaybackRoute
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.floor

/**
 * Everything the playback screen knows: the day's recordings, events and motion, the hour that is
 * loaded in the player, and where the playhead is. Recordings play an hour at a time through
 * Frigate's /vod (it refuses long ranges with 503); each hour starts on a segment boundary, so the
 * player's position maps exactly to wall-clock time through [RecordingMap].
 */
@Stable
class PlaybackState(
    val route: PlaybackRoute,
    private val scope: CoroutineScope,
    val player: RecordingPlayer,
    val preview: PreviewScrubber,
) {
    enum class Tab { Events, Timeline, Detail }

    var camera by mutableStateOf(route.camera)
    /** Local midnight of the day, epoch ms. */
    var day by mutableLongStateOf(route.day)
    var tab by mutableStateOf(if (route.showTimeline) Tab.Timeline else Tab.Events)
    /** The side panel (phone sideways) and the right column (tablet): events or detail. */
    var sideTab by mutableStateOf(Tab.Events)

    /** Recordings for the whole day, used by the timelines. */
    var map by mutableStateOf(RecordingMap()); private set
    /** Recordings in the loaded hour. */
    var chunkMap by mutableStateOf(RecordingMap()); private set
    private var chunkStart: Double? = null
    var segments by mutableStateOf<List<RecordingSegment>>(emptyList()); private set
    var reviews by mutableStateOf<List<ReviewItem>>(emptyList())
    var filter by mutableStateOf(PlaybackFilter())
    var motion by mutableStateOf<List<MotionActivity>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var pendingReviewId by mutableStateOf(route.reviewId)
    private var pendingStartTime: Double? = route.startTime
    /** The playhead's time when another camera was picked: that camera starts at the same moment. */
    private var switchTime: Double? = null

    /** Wall-clock time shown while dragging a timeline. */
    var scrubTime by mutableStateOf<Double?>(null)
    /** Where the user scrubbed to, shown until the player has arrived there. */
    var heldTime by mutableStateOf<Double?>(null)
    /** The playhead is on a moment without recordings: the video shows a placeholder. */
    var gapTime by mutableStateOf<Double?>(null)
    /** Seconds per dp on the horizontal timelines. */
    var timelineScale by mutableDoubleStateOf(2.0)

    // Overlay over the video.
    var overlayVisible by mutableStateOf(true)
    var overlayToken by mutableIntStateOf(0)
    /** Menus in the overlay that are open; the overlay must not hide underneath them. */
    var menusOpen by mutableIntStateOf(0)
    var showEventsPanel by mutableStateOf(false)
    /** Tablet: the video fills the window. Phone: turned sideways by the full-screen button. */
    var fullscreen by mutableStateOf(false)

    val conn: ServerConnection? get() = AppModel.connection(route.serverId)
    val client: FrigateClient? get() = conn?.client
    val cameraConfig: CameraConfig? get() = conn?.camera(camera)
    /** Frigate's detect.annotation_offset for this camera, in seconds. */
    val annotationOffset: Double get() = cameraConfig?.annotationOffset ?: 0.0
    val dayStart: Double get() = day / 1000.0
    val dayEnd: Double
        get() = minOf(Calendar.getInstance().apply { timeInMillis = day; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis / 1000.0, nowSeconds())

    private val playerWallTime: Double get() = if (chunkMap.isEmpty) dayStart else chunkMap.wallTime(player.currentTime)
    /** Wall-clock time at the playhead. */
    val wallTime: Double get() = scrubTime ?: gapTime ?: heldTime ?: playerWallTime

    /** Review items that pass the type, label and zone filters. */
    val visibleReviews: List<ReviewItem> by derivedStateOf { reviews.filter { filter.matches(it) } }

    /** The event under the playhead (changes only when another event plays, so it redraws little). */
    val currentReview: ReviewItem? by derivedStateOf {
        val t = wallTime - annotationOffset
        reviews.firstOrNull { t >= it.startTime - 5 && t <= (it.endTime ?: Double.POSITIVE_INFINITY) + 5 }
    }

    /** The event offered in the save menu: the last one that started before the playhead. */
    val menuReview: ReviewItem?
        get() {
            // Event times are detection times; the recording runs `annotationOffset` from them.
            val t = wallTime - annotationOffset
            return visibleReviews.filter { it.startTime - 5 <= t }.maxByOrNull { it.startTime }
        }

    fun keepOverlay() { overlayToken++ }

    fun toggleOverlay() {
        if (showEventsPanel) { showEventsPanel = false; overlayToken++; return }
        overlayVisible = !overlayVisible
        overlayToken++
    }

    // MARK: Scrubbing

    fun scrub(t: Double) {
        heldTime = null
        scrubTime = t
        preview.show(t - annotationOffset)
    }

    fun cancelScrub() {
        scrubTime = null
        preview.hide()
    }

    /** Ends a scrub: keep showing `t` until the player reports a position near it. */
    fun finishScrub(raw: Double, snap: Double = 0.0) {
        scrubTime = null
        jump(snapToRecording(raw, snap), player.wantsToPlay, earlier = 1.0)
    }

    private var holdJob: Job? = null

    /** Jumps to `t` and shows that time (and the event there) at once, while the player may still be loading another hour. */
    fun jump(t: Double, autoplay: Boolean, earlier: Double = 0.0) {
        heldTime = t
        seek(t, autoplay, earlier = earlier)
        holdJob?.cancel()
        holdJob = scope.launch {
            // Release only once the player is ready and has reported a nearby position on two
            // checks in a row, so a new hour loading cannot flash its start.
            var steady = 0
            repeat(60) {
                delay(150)
                if (heldTime != t) return@launch
                val ready = player.duration > 0 && player.pendingStart == null
                val near = playerWallTime > t - earlier - 0.5 && playerWallTime < t + 1.5
                steady = if (ready && near) steady + 1 else 0
                if (steady >= 2) {
                    if (heldTime == t) heldTime = null
                    preview.hide()
                    return@launch
                }
            }
            if (heldTime == t) heldTime = null
            preview.hide()
        }
    }

    /** Plays a review item from just before it starts. */
    fun play(r: ReviewItem) = jump(r.startTime - 1 + annotationOffset, autoplay = true, earlier = 2.0)

    /** Released just beside a recording: move onto its nearest edge instead of showing a gap. */
    fun snapToRecording(t: Double, window: Double): Double {
        if (window <= 0 || map.isEmpty || map.hasRecording(t)) return t
        var bestD = Double.POSITIVE_INFINITY
        var best: Double? = null
        for (s in map.spans) {
            for ((edge, target) in listOf(s.wallStart to s.wallStart, s.wallEnd to s.wallEnd - 1)) {
                val d = abs(edge - t)
                if (d <= window && d < bestD) { bestD = d; best = target }
            }
        }
        return best ?: t
    }

    /** Play/pause. On a gap, play continues from the next recording. */
    fun togglePlay() {
        val gap = gapTime
        if (gap != null) {
            map.spans.firstOrNull { it.wallStart > gap }?.let { seek(it.wallStart, autoplay = true, skipGap = true) }
            return
        }
        player.toggle()
    }

    /** The hour played to its end: continue with the next hour that has recordings. */
    fun hourFinished() {
        val start = chunkStart ?: return
        val next = start + CHUNK
        if (next < dayEnd) seek(next, autoplay = true, skipGap = true)
    }

    /** Recording segments that start within the hour, unclipped, so the playlist begins exactly on a segment boundary. */
    private fun chunk(hour: Double): RecordingMap =
        RecordingMap.of(segments.filter { it.startTime >= hour && it.startTime < hour + CHUNK }, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)

    /**
     * Seeks to a wall-clock time, loading the hour of recordings that contains it. `earlier`: how
     * far before `t` playback may start, on a keyframe (an exact seek on cameras with few
     * keyframes took seconds; landing on the keyframe itself starts at once).
     */
    fun seek(t: Double, autoplay: Boolean, skipGap: Boolean = false, earlier: Double = 0.0) {
        val client = client ?: return
        if (map.isEmpty) return
        var target = t
        if (!map.hasRecording(target)) {
            val next = map.spans.firstOrNull { it.wallStart > target }
            if (skipGap && next != null) {
                target = next.wallStart
            } else {
                // Nothing recorded here: show that instead of jumping elsewhere.
                player.pause()
                gapTime = t
                heldTime = null
                preview.hide()
                return
            }
        }
        gapTime = null
        // A segment belongs to the hour it starts in.
        val segStart = segments.lastOrNull { it.startTime <= target }?.startTime ?: target
        var hour = dayStart + floor((maxOf(segStart, dayStart) - dayStart) / CHUNK) * CHUNK
        if (hour != chunkStart) {
            var cm = chunk(hour)
            while (cm.isEmpty && hour + CHUNK < dayEnd) {
                hour += CHUNK
                cm = chunk(hour)
            }
            val first = cm.spans.firstOrNull() ?: return
            val last = cm.spans.last()
            chunkStart = hour
            chunkMap = cm
            player.load(client.vodUrl(camera, first.wallStart, last.wallEnd), cm.playerTime(target), autoplay)
            return
        }
        player.seek(chunkMap.playerTime(target), precise = earlier == 0.0, earlier = earlier)
        if (autoplay) player.play()
    }

    fun switchCamera(name: String) {
        if (name != camera && !loading && !map.isEmpty) switchTime = wallTime
        camera = name
    }

    // MARK: Loading

    suspend fun load() {
        val client = client ?: run { loading = false; return }
        loading = true
        val keepTime = switchTime
        switchTime = null
        player.stop()
        map = RecordingMap()
        val start = dayStart
        val end = dayEnd
        val cam = camera
        val (s, r, m) = coroutineScope {
            val segs = async { runCatching { client.recordings(cam, start, end) }.getOrNull() }
            val revs = async { runCatching { client.reviews(cameras = listOf(cam), after = start, before = end, limit = 500) }.getOrNull() }
            val mot = async { runCatching { client.motionActivity(listOf(cam), start, end, 30) }.getOrNull() }
            Triple(segs.await(), revs.await(), mot.await())
        }
        reviews = (r ?: emptyList()).sortedByDescending { it.startTime }
        motion = (m ?: emptyList()).sortedBy { it.startTime }
        segments = (s ?: emptyList()).sortedBy { it.startTime }
        map = RecordingMap.of(segments, start, end)
        preview.prepare(client, cam, start, end)
        // Fill the calendar's cache now, so the day picker opens without waiting.
        scope.launch { DayAvailability.load(listOf(DaySource(client, listOf(cam)))) }
        chunkStart = null
        chunkMap = RecordingMap()
        gapTime = null
        heldTime = null
        loading = false
        val last = map.spans.lastOrNull() ?: return

        // Start playing right away: at the time watched on the other camera (or the recording
        // closest to it), at the requested moment or review item, else the latest event, else near the end.
        val pendingStart = pendingStartTime
        val pendingItem = pendingReviewId?.let { id -> reviews.firstOrNull { it.id == id } }
        when {
            keepTime != null && keepTime >= start && keepTime < end ->
                jump(snapToRecording(keepTime, Double.POSITIVE_INFINITY), autoplay = true, earlier = 1.0)
            pendingStart != null && pendingStart >= start && pendingStart < end -> {
                pendingStartTime = null
                seek(pendingStart - 3 + annotationOffset, autoplay = true, skipGap = true)
            }
            pendingItem != null -> play(pendingItem)
            else -> {
                val latest = reviews.firstOrNull { map.hasRecording(it.startTime + annotationOffset + 1) }
                if (latest != null) play(latest) else seek(maxOf(last.wallStart, last.wallEnd - 60), autoplay = true)
            }
        }
    }

    /** Clamps a range to the loaded day and to what has been recorded. */
    fun clampRange(start: Double, end: Double): Pair<Double, Double> {
        val s = maxOf(start, dayStart)
        val e = minOf(end, dayEnd, nowSeconds())
        return s to maxOf(e, s + 1)
    }

    /** Length of a review item as far as it was recorded; "No video" when nothing was. */
    fun recordedDurationText(r: ReviewItem): String {
        val s = r.startTime + annotationOffset
        val e = (r.endTime ?: nowSeconds()) + annotationOffset
        val recorded = map.spans.sumOf { maxOf(0.0, minOf(it.wallEnd, e) - maxOf(it.wallStart, s)) }
        // Very short items (start equals end) cover no span; check the moment itself.
        if (recorded <= 0 && map.hasRecording(s)) return Format.duration(r.duration)
        if (recorded <= 0 && !map.isEmpty) return L(R.string.playback_no_video)
        return if (recorded > 0) Format.duration(recorded) else Format.reviewDuration(r)
    }

    companion object {
        const val CHUNK = 3600.0
    }
}
