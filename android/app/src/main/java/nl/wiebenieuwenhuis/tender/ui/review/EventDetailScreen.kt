package nl.wiebenieuwenhuis.tender.ui.review

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.SeekParameters
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateEvent
import nl.wiebenieuwenhuis.tender.api.GenAISummary
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.ReviewItem
import nl.wiebenieuwenhuis.tender.api.Severity
import nl.wiebenieuwenhuis.tender.api.TimelineEntry
import nl.wiebenieuwenhuis.tender.api.nowSeconds
import nl.wiebenieuwenhuis.tender.api.toDate
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.AppChrome
import nl.wiebenieuwenhuis.tender.ui.EventRoute
import nl.wiebenieuwenhuis.tender.ui.ItemCache
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.PlaybackRoute
import nl.wiebenieuwenhuis.tender.ui.components.ConfirmDialog
import nl.wiebenieuwenhuis.tender.ui.components.ErrorBanner
import nl.wiebenieuwenhuis.tender.ui.components.LabelTag
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.MonoDigits
import nl.wiebenieuwenhuis.tender.ui.components.RemoteImage
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.VideoSurface
import nl.wiebenieuwenhuis.tender.ui.components.load
import nl.wiebenieuwenhuis.tender.ui.components.rememberVideoPlayer
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import nl.wiebenieuwenhuis.tender.ui.explore.ObjectDescriptionSection
import nl.wiebenieuwenhuis.tender.ui.playback.VideoOverlayControls
import java.util.Calendar
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Seconds of recording before and after the review item or object: Frigate's REVIEW_PADDING. */
private const val PADDING = 4.0

/** A review item: its clip, objects, GenAI summary and actions. Loads the item when it is not in [ItemCache]. */
@Composable
fun EventDetailScreen(route: EventRoute) {
    val conn = AppModel.connection(route.serverId)
    val client = conn?.client
    var item by remember(route.reviewId) { mutableStateOf(ItemCache.reviews[route.reviewId]) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(route.reviewId, client) {
        if (item != null || client == null) return@LaunchedEffect
        try {
            item = client.review(route.reviewId).also { ItemCache.put(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    val loaded = item
    if (loaded == null || conn == null) {
        ScreenScaffold(stringResource(R.string.review_event)) { padding ->
            Column(Modifier.padding(padding).padding(16.dp)) {
                val e = error
                if (e != null) ErrorBanner(e) else LoadingBox()
            }
        }
        return
    }
    EventDetail(route, conn, loaded) { item = it }
}

/** Player state the screen reads, updated from ExoPlayer's listener and a position poll. */
private class PlayerState {
    var position by mutableDoubleStateOf(0.0)
    var duration by mutableDoubleStateOf(0.0)
    var wantsToPlay by mutableStateOf(true)
    var buffering by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    /** Frames have been on screen: the still goes and stays gone when paused. */
    var appeared by mutableStateOf(false)
    var scrubbing by mutableStateOf(false)
}

/** A spinner in a given colour, for buttons on the video and coloured buttons. */
@Composable
private fun Spinner(color: Color = LocalContentColor.current, size: Dp = 18.dp) {
    CircularProgressIndicator(Modifier.size(size), strokeWidth = 2.dp, color = color)
}

@Composable
private fun EventDetail(route: EventRoute, conn: ServerConnection, item: ReviewItem, onItem: (ReviewItem) -> Unit) {
    val nav = LocalNav.current
    val client = conn.client
    val scope = rememberCoroutineScope()
    val actions = remember { ReviewActions(scope) }
    val player = rememberVideoPlayer()
    val ps = remember { PlayerState() }
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val tallEnough = sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    val wide = tallEnough && nl.wiebenieuwenhuis.tender.ui.components.isWideLayout()
    // A phone on its side: the video fills the screen.
    val landscape = !tallEnough

    var events by remember { mutableStateOf<List<FrigateEvent>>(emptyList()) }
    var focusLoaded by remember { mutableStateOf(route.focusEventId?.let { ItemCache.events[it] }) }
    var timeline by remember { mutableStateOf<List<TimelineEntry>>(emptyList()) }
    var noRecording by remember { mutableStateOf(false) }
    var videoStart by remember { mutableStateOf<Double?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var snapshotEvent by remember { mutableStateOf<FrigateEvent?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayToken by remember { mutableIntStateOf(0) }
    /** Menus over the video that are open (the speed); the controls stay while one is. */
    var menusOpen by remember { mutableIntStateOf(0) }
    var rate by remember { mutableFloatStateOf(1f) }
    fun keepOverlay() { overlayToken++ }

    val isReviewed = item.isReviewed
    val camera = conn.camera(item.camera)
    val camName = cameraName(conn, item.camera)
    // Frigate's annotation offset for this camera, so detections line up with the recording.
    val offset = camera?.annotationOffset ?: 0.0
    val aspect = camera?.aspectRatio ?: (16f / 9f)
    val isAdmin = conn.isAdmin

    // Opened on one object: just that object, freshly loaded if it was still going.
    val focus: FrigateEvent? = route.focusEventId?.let { id -> events.firstOrNull { it.id == id } ?: focusLoaded }
    // What the video covers, as in Frigate's web app: a review item from its start to its end,
    // an object from Explore from when it was first to last seen. The padding goes around it.
    val now = nowSeconds()
    val activityStart = (focus?.startTime ?: item.startTime) + offset
    // Capped at 10 minutes: a parked car can be tracked, or a review item left open, for hours.
    val activityEnd = (if (focus != null) minOf(focus.endTime ?: now, now, focus.startTime + 600)
        else minOf(item.endTime ?: now, now, item.startTime + 600)) + offset
    // Whole seconds, as the /vod endpoint uses, so the event range lines up with the video.
    val clipStart = floor(activityStart - PADDING)
    val clipEnd = min(activityEnd + PADDING, now)
    // Where the player's time 0 is, fixed when the video loads.
    val origin = videoStart ?: clipStart
    val canPlay = !(noRecording || ps.error != null || ps.buffering || ps.duration == 0.0)
    val immersive = landscape || fullscreen

    // MARK: Player state
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { ps.wantsToPlay = playWhenReady }
            override fun onPlaybackStateChanged(playbackState: Int) {
                ps.buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                if (playbackState == Player.STATE_ENDED) player.playWhenReady = false
            }
            override fun onPlayerError(error: PlaybackException) { ps.error = error.localizedMessage ?: error.errorCodeName }
            override fun onRenderedFirstFrame() { ps.appeared = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            if (!ps.scrubbing) ps.position = player.currentPosition / 1000.0
            ps.duration = player.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0
            delay(if (player.isPlaying) 50 else 250)
        }
    }
    // The controls over the video hide after 4 seconds of playback without use; pausing brings them back.
    LaunchedEffect(overlayToken, ps.wantsToPlay, overlayVisible, menusOpen) {
        if (!ps.wantsToPlay) { overlayVisible = true; return@LaunchedEffect }
        if (!overlayVisible || menusOpen > 0) return@LaunchedEffect
        delay(4000)
        if (ps.wantsToPlay && !ps.scrubbing) overlayVisible = false
    }

    // Full screen is for tablets (a phone turns sideways for it); Back leaves it first.
    LaunchedEffect(wide) { if (!wide) fullscreen = false }
    BackHandler(fullscreen) { fullscreen = false; keepOverlay() }

    // Full screen: no tab bar or rail, no system bars.
    val view = LocalView.current
    DisposableEffect(immersive) {
        AppChrome.immersive = immersive
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (immersive) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            AppChrome.immersive = false
        }
    }

    // MARK: Loading
    LaunchedEffect(item.id) {
        val c = client ?: return@LaunchedEffect
        // Read the reviewed flag fresh: the screen that opened this item may hold an old copy.
        launch {
            val fresh = runCatching { c.review(item.id) }.getOrNull()
            if (fresh?.hasBeenReviewed == true && !item.hasBeenReviewed) onItem(item.copy(hasBeenReviewed = true))
        }
        // Opened on one object: its own times decide the clip, so it is needed first.
        val focusId = route.focusEventId
        if (focusId != null && focusLoaded == null) focusLoaded = runCatching { c.event(focusId) }.getOrNull()
        val f = focusId?.let { focusLoaded }
        val n = nowSeconds()
        val aStart = (f?.startTime ?: item.startTime) + offset
        val aEnd = (if (f != null) minOf(f.endTime ?: n, n, f.startTime + 600) else minOf(item.endTime ?: n, n, item.startTime + 600)) + offset
        val start = floor(aStart - PADDING)
        val end = min(aEnd + PADDING, n)
        // The tracked objects and whether there is a recording, both at once.
        val ids = item.detections
        coroutineScope {
            val loadedEvents = async { runCatching { c.events(ids) }.getOrDefault(emptyList()) }
            val segments = async { runCatching { c.recordings(item.camera, start, end) }.getOrNull() }
            events = loadedEvents.await()
            val segs = segments.await()
            if (segs != null && segs.isEmpty()) {
                noRecording = true
            } else if (videoStart == null) {
                // Just the clip, from its start: the review item or the object with 4 s around it.
                videoStart = start
                player.load(c.clipVodUrl(item.camera, start, end))
            }
        }
        // The lifecycle of the object opened from Explore only, as Frigate's tracking details.
        val sources = focusId?.let { listOf(it) } ?: ids.take(10)
        timeline = coroutineScope {
            sources.map { id -> async { runCatching { c.timeline(id) }.getOrDefault(emptyList()) } }.awaitAll()
        }.flatten().sortedBy { it.timestamp }
    }

    // MARK: Actions
    fun clipRequest() = ClipRequest(route.serverId, item.camera, clipStart, clipEnd,
        exportName(item.camera, camera?.displayName, activityStart, item.labels.firstOrNull()))

    fun markReviewed() {
        val c = client ?: return
        if (reviewing || isReviewed) return
        scope.launch {
            reviewing = true
            try {
                c.markViewed(listOf(item.id))
                conn.noteReviewed(listOf(item))
                AppModel.reviewedIds[item.id] = true
                onItem(item.copy(hasBeenReviewed = true))
                AppModel.scope.launch { conn.refresh() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.message ?: e.toString()
            } finally {
                reviewing = false
            }
        }
    }

    fun delete() {
        val c = client ?: return
        if (deleting) return
        scope.launch {
            deleting = true
            try {
                c.deleteReviews(listOf(item.id))
                ReviewSession.deletedIds[item.id] = true
                AppModel.scope.launch { conn.refresh() }
                nav.popBackStack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.message ?: e.toString()
            } finally {
                deleting = false
            }
        }
    }

    fun share() { if (!downloading) actions.share(clipRequest()) { downloading = it } }

    fun openPlayback() {
        nav.navigate(PlaybackRoute(route.serverId, item.camera, startOfDay(item.startTime), reviewId = item.id,
            startTime = focus?.startTime?.let { it + offset - 1 }))
    }

    fun seek(seconds: Double, precise: Boolean = true) {
        player.setSeekParameters(if (precise) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        val t = seconds.coerceIn(0.0, max(ps.duration, 0.0))
        ps.position = t
        player.seekTo((t * 1000).toLong())
    }

    fun togglePlay() {
        if (ps.wantsToPlay) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun playFrom(e: FrigateEvent) {
        seek(max(0.0, e.startTime + offset - origin - 1))
        player.play()
        keepOverlay()
    }

    // The tracked objects: the one opened from Explore first, then only those near it in time.
    val objectEvents: List<FrigateEvent> = remember(events, focus) {
        var list = events.sortedBy { it.startTime }
        if (focus != null) {
            val from = focus.startTime - PADDING
            val to = (focus.endTime ?: nowSeconds()) + PADDING
            list = list.filter { it.id == focus.id || (it.startTime <= to && (it.endTime ?: Double.MAX_VALUE) >= from) }
        }
        val i = list.indexOfFirst { it.id == route.focusEventId }
        if (i > 0) list = listOf(list[i]) + list.filterIndexed { j, _ -> j != i }
        list
    }
    // The still while the video loads: the object it was opened on, otherwise the first with a snapshot.
    val posterUrl: String? = client?.let { c ->
        val shown = focus ?: objectEvents.firstOrNull { it.hasSnapshot == true } ?: objectEvents.firstOrNull()
        shown?.let { if (it.hasSnapshot == true) c.snapshotUrl(it, bbox = false) else c.thumbnailUrl(it) } ?: c.thumbnailUrl(item)
    }
    val shownLabels = focus?.let { listOf(it.label) } ?: item.labels
    val timeRange = timeRangeText(focus?.startTime ?: item.startTime, if (focus != null) focus.endTime else item.endTime)

    // MARK: Pieces

    @Composable
    fun TimeLabel(modifier: Modifier = Modifier, fontSize: Int = 12) {
        val t = (origin + ps.position).toDate()
        Row(modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (!ps.wantsToPlay) { Icon(Icons.Filled.Pause, null, Modifier.size(12.dp), tint = Color.White); Spacer(Modifier.width(4.dp)) }
            Text("${Format.dayTitle(t)} · ${Format.time(t, seconds = true)}", color = Color.White, fontSize = fontSize.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, style = MonoDigits)
        }
    }

    @Composable
    fun BoxScope.PlayerStatus(showPlay: Boolean) {
        Box(Modifier.align(Alignment.Center)) {
            when {
                noRecording -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.VideocamOff, null, Modifier.size(28.dp), tint = Color.White.copy(alpha = 0.85f))
                    Text(stringResource(R.string.review_no_recording), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                }
                ps.error != null -> Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = Color.White)
                    Text(ps.error ?: "", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
                ps.buffering || ps.duration == 0.0 -> CircularProgressIndicator(color = Color.White)
                showPlay && !ps.wantsToPlay -> RoundButton(Icons.Filled.PlayArrow, stringResource(R.string.review_play), 56) { player.play() }
            }
        }
    }

    @Composable
    fun CenterControls(modifier: Modifier = Modifier) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Filled.Replay10, stringResource(R.string.review_back_10), 48) { seek(ps.position - 10); keepOverlay() }
            RoundButton(if (ps.wantsToPlay) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(if (ps.wantsToPlay) R.string.review_pause else R.string.review_play), 60) {
                togglePlay()
                keepOverlay()
            }
            RoundButton(Icons.Filled.Forward10, stringResource(R.string.review_forward_10), 48) { seek(ps.position + 10); keepOverlay() }
        }
    }

    @Composable
    fun Scrubber(onDark: Boolean, modifier: Modifier = Modifier) {
        Column(modifier.padding(horizontal = 16.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EventScrubber(
                position = ps.position, duration = ps.duration,
                eventStart = activityStart - origin, eventEnd = activityEnd - origin, onDark = onDark,
                onScrub = { t, done ->
                    ps.scrubbing = !done
                    seek(t, precise = done)
                    keepOverlay()
                },
            )
            // The time of day, like Frigate: where the playhead is, and where the clip ends.
            Row {
                val color = if (onDark) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                Text(Format.time(origin + ps.position, seconds = true), color = color, style = MaterialTheme.typography.bodySmall.merge(MonoDigits))
                Spacer(Modifier.weight(1f))
                Text(Format.time(origin + (if (ps.duration > 0) ps.duration else clipEnd - origin), seconds = true), color = color,
                    style = MaterialTheme.typography.bodySmall.merge(MonoDigits))
            }
        }
    }

    @Composable
    fun Video(modifier: Modifier) {
        Box(modifier.background(Color.Black)) {
            VideoSurface(player, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().pointerInput(Unit) {
                detectTapGestures { overlayVisible = !overlayVisible; keepOverlay() }
            })
            // A still of the object while the video loads, as Frigate shows its snapshot first.
            if (!ps.appeared && !noRecording && posterUrl != null) {
                RemoteImage(posterUrl, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
            }
            PlayerStatus(showPlay = !overlayVisible)
            // Along the bottom, as on the playback screen: back 10 s, play/pause, forward 10 s and the speed.
            AnimatedVisibility(overlayVisible && canPlay, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
                VideoOverlayControls(
                    playing = ps.wantsToPlay, rate = rate,
                    onSkip = { seek(ps.position + it) },
                    onTogglePlay = { togglePlay() },
                    onRate = { rate = it; player.setPlaybackSpeed(it) },
                    large = wide, onUse = { keepOverlay() },
                    onMenuOpen = { menusOpen++ }, onMenuClose = { menusOpen-- },
                )
            }
            if (ps.appeared) TimeLabel(Modifier.align(Alignment.TopStart).padding(10.dp))
            // Tablets only: on a phone, turning it sideways is full screen.
            if (wide && !noRecording) AnimatedVisibility(overlayVisible, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
                RoundButton(Icons.Filled.Fullscreen, stringResource(R.string.review_full_screen), 40, Modifier.padding(8.dp)) {
                    fullscreen = true; keepOverlay()
                }
            }
        }
    }

    @Composable
    fun Info() {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                shownLabels.forEach { LabelTag(it, fontSize = 12) }
                val level = item.metadata?.threatLevel
                if (!isReviewed && level != null) ThreatChip(level)
                Text(stringResource(if (item.severity == Severity.Alert) R.string.review_alert else R.string.review_detection),
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(camName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(timeRange, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    fun Tiles() {
        val infoEvents = focus?.let { listOf(it) } ?: events
        val score = infoEvents.mapNotNull { it.bestScore }.maxOrNull()
        val zones = (focus?.zones ?: item.zones).map { Format.prettyName(it) }
        val subLabels = ((if (focus == null) item.subLabels else emptyList()) + infoEvents.mapNotNull { it.subLabel }).filter { it.isNotEmpty() }.distinct()
        val plates = infoEvents.mapNotNull { it.recognizedPlate }.distinct()
        val third = when {
            plates.isNotEmpty() -> stringResource(R.string.review_tile_plate) to plates.joinToString(", ")
            subLabels.isNotEmpty() -> stringResource(R.string.review_tile_sub_label) to subLabels.joinToString(", ")
            else -> stringResource(R.string.review_tile_duration) to
                (if (focus == null && item.duration > 3600) stringResource(R.string.more_than_hour) else Format.shortDuration(max(0.0, activityEnd - activityStart)))
        }
        Row(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoTile(stringResource(R.string.review_tile_score), score?.let { Format.percent(it) } ?: "–", Modifier.weight(1f))
            InfoTile(stringResource(R.string.review_tile_zones), if (zones.isEmpty()) stringResource(R.string.review_none) else zones.joinToString(", "), Modifier.weight(1f))
            InfoTile(third.first, third.second, Modifier.weight(1f))
        }
    }

    @Composable
    fun Analysis() {
        val ai = item.metadata ?: return
        if (ai.trimmedTitle == null && ai.scene.isNullOrBlank()) return
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.review_ai_analysis))
            Card {
                SelectionContainer {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ai.trimmedTitle?.let { AnalysisField(stringResource(R.string.review_ai_title), it) }
                        ai.scene?.takeIf { it.isNotBlank() }?.let { AnalysisField(stringResource(R.string.review_ai_description), it) }
                        ai.confidence?.let { AnalysisField(stringResource(R.string.review_ai_score), Format.percent(it)) }
                        AnalysisField(stringResource(R.string.review_ai_concerns), concerns(ai))
                    }
                }
            }
        }
    }

    // Opened on one object: its description and Find similar, as Frigate's tracked object details.
    @Composable
    fun Describe() {
        val f = focus ?: return
        ObjectDescriptionSection(route.serverId, f)
    }

    // A tap on the picture shows it large; the time on it plays the video from that object.
    @Composable
    fun ObjectCard(e: FrigateEvent, modifier: Modifier) {
        val c = client ?: return
        val first = max(e.startTime, origin - offset)
        val border = if (e.id == route.focusEventId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, border, RoundedCornerShape(12.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val showSnapshot = stringResource(R.string.review_show_snapshot)
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black)
                .clickable(onClickLabel = showSnapshot) { snapshotEvent = e }) {
                RemoteImage(if (e.hasSnapshot == true) c.snapshotUrl(e) else c.thumbnailUrl(e), Modifier.matchParentSize())
                if (canPlay) {
                    val label = stringResource(R.string.review_play_from, Format.time(first, seconds = true))
                    Row(Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.65f)).clickable(onClickLabel = label) { playFrom(e) }
                        .padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(12.dp), tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text(Format.time(first, seconds = true), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = MonoDigits)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabelTag(e.label, fontSize = 12)
                Spacer(Modifier.weight(1f))
                e.bestScore?.let { Text(Format.percent(it), style = MaterialTheme.typography.titleSmall) }
            }
        }
    }

    @Composable
    fun Objects() {
        val list = objectEvents
        if (list.isEmpty()) return
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.review_objects), Modifier.padding(horizontal = 16.dp))
            if (wide) {
                // Tablets: as many columns of at least 200 dp as fit, none cut off at the edge.
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    val columns = max(1, ((maxWidth + 10.dp) / 210.dp).toInt())
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = columns) {
                        list.forEach { ObjectCard(it, Modifier.weight(1f)) }
                        val rest = (columns - list.size % columns) % columns
                        repeat(rest) { Spacer(Modifier.weight(1f)) }
                    }
                }
            } else {
                // Runs to the screen edge, like the rows in Explore; the margin sits inside.
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.forEach { ObjectCard(it, Modifier.width(196.dp)) }
                }
            }
        }
    }

    /** `edge`: its side margin; none under the video, where it lines up with the video's edges. */
    @Composable
    fun Lifecycle(edge: Dp = 16.dp) {
        if (timeline.isEmpty()) return
        val length = if (ps.duration > 0) ps.duration else clipEnd - origin
        val spanStart = origin - offset
        val spanEnd = spanStart + max(length, 0.0)
        Column(Modifier.padding(horizontal = edge).padding(top = 16.dp)) {
            Card {
                Text(stringResource(R.string.review_object_lifecycle).uppercase(), Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                timeline.forEach { entry ->
                    // Rows from before or after the video (a car parked long before) are dimmed: nothing to jump to.
                    val inVideo = entry.timestamp in spanStart..spanEnd
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .clickable(enabled = inVideo) { seek(entry.timestamp + offset - origin); keepOverlay() }
                            .alpha(if (inVideo) 1f else 0.4f).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(8.dp).background(dotColor(entry), CircleShape))
                        Text(lifecycleText(entry), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(Format.time(entry.timestamp, seconds = true), style = MaterialTheme.typography.bodySmall.merge(MonoDigits),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    @Composable
    fun ActionsBar(modifier: Modifier = Modifier) {
        Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { markReviewed() }, enabled = !isReviewed && !reviewing, modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(12.dp)) {
                when {
                    reviewing -> Spinner()
                    isReviewed -> { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.review_reviewed), maxLines = 1) }
                    else -> Text(stringResource(R.string.review_reviewed), maxLines = 1)
                }
            }
            // Save: as a file, or as an export kept in Frigate (sharing is the button at the top).
            Box(Modifier.weight(1f).height(50.dp)) {
                var menu by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { menu = true }, enabled = !actions.isWorking && !downloading, modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(12.dp)) {
                    // The spinner shows on the button that started the work: sharing has its own.
                    if (actions.isWorking && !downloading) Spinner() else Text(stringResource(R.string.review_save), maxLines = 1)
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.review_save_to_device)) }, leadingIcon = { Icon(Icons.Outlined.SaveAlt, null) },
                        onClick = { menu = false; actions.saveToDevice(clipRequest()) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.review_save_to_frigate)) }, leadingIcon = { Icon(Icons.Outlined.Save, null) },
                        onClick = { menu = false; actions.saveToFrigate(clipRequest()) })
                }
            }
            // Frigate lets only admins delete review items.
            if (isAdmin) {
                OutlinedButton(onClick = { confirmDelete = true }, enabled = !deleting, modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    if (deleting) Spinner(MaterialTheme.colorScheme.error) else Text(stringResource(R.string.review_delete), maxLines = 1)
                }
            }
        }
    }

    @Composable
    fun Details() {
        Info()
        Tiles()
        Analysis()
        Describe()
        Objects()
    }

    // MARK: Layout

    ReviewActionsHost(actions, Modifier.fillMaxSize()) {
        if (immersive) {
            // Full-screen video. A tap shows or hides the controls; they hide by themselves after 4 seconds of playback.
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                VideoSurface(player, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { overlayVisible = !overlayVisible; keepOverlay() } })
                if (!ps.appeared && !noRecording && posterUrl != null) RemoteImage(posterUrl, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                PlayerStatus(showPlay = !overlayVisible)
                AnimatedVisibility(overlayVisible, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
                    Box(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.75f))
                                .safeDrawingPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (fullscreen) IconButton(onClick = { fullscreen = false }) {
                                Icon(Icons.Filled.FullscreenExit, stringResource(R.string.review_exit_full_screen), tint = Color.White)
                            } else IconButton(onClick = { nav.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                            }
                            Column(Modifier.weight(1f, fill = false)) {
                                Text(camName, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(timeRange, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            shownLabels.forEach { LabelTag(it, fontSize = 12) }
                            Spacer(Modifier.weight(1f))
                            if (ps.appeared) TimeLabel(fontSize = 13)
                            ReviewedPill(isReviewed, reviewing) { markReviewed(); keepOverlay() }
                            IconButton(onClick = { share(); keepOverlay() }) {
                                if (downloading) Spinner(Color.White) else Icon(Icons.Outlined.Share, stringResource(R.string.review_share_clip), tint = Color.White)
                            }
                            if (isAdmin) IconButton(onClick = { confirmDelete = true; keepOverlay() }, enabled = !deleting) {
                                if (deleting) Spinner(Color.White)
                                else Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.review_delete), tint = Palette.status.destructive)
                            }
                        }
                        if (canPlay) CenterControls(Modifier.align(Alignment.Center))
                        Scrubber(onDark = true, Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f))
                            .navigationBarsPadding().padding(bottom = 10.dp))
                    }
                }
            }
        } else {
            // No title: the camera's name is the large heading under the video.
            ScreenScaffold(
                title = "",
                actions = {
                    IconButton(onClick = { openPlayback() }) { Icon(Icons.Outlined.VideoLibrary, stringResource(R.string.review_open_in_playback)) }
                    // Not disabled while loading: that would dim the spinner. share() ignores a second tap.
                    IconButton(onClick = { share() }) {
                        if (downloading) Spinner() else Icon(Icons.Outlined.Share, stringResource(R.string.review_share_clip))
                    }
                },
                bottomBar = { if (!wide) Surface(color = MaterialTheme.colorScheme.surface) { ActionsBar(Modifier.navigationBarsPadding()) } },
            ) { padding ->
                if (wide) {
                    // Tablets: the video as large as the window allows, with the details around it.
                    WideDetailLayout(
                        aspect, Modifier.fillMaxSize().padding(padding),
                        media = { w, h ->
                            Column(Modifier.width(w)) {
                                Video(Modifier.width(w).height(h).clip(RoundedCornerShape(12.dp)))
                                Scrubber(onDark = false)
                            }
                        },
                        details = { Details() },
                        extra = { edge -> Lifecycle(edge) },
                        actions = { ActionsBar() },
                    )
                } else {
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                        Video(Modifier.fillMaxWidth().aspectRatio(aspect))
                        Scrubber(onDark = false)
                        Details()
                        Lifecycle()
                    }
                }
            }
        }
    }

    snapshotEvent?.let { e -> SnapshotViewer(e, conn, onClose = { snapshotEvent = null }) }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.review_delete_title),
            message = deleteMessage(item),
            confirmText = stringResource(R.string.review_delete),
            onConfirm = { delete() },
            onDismiss = { confirmDelete = false },
        )
    }
    errorMessage?.let { MessageDialog(stringResource(R.string.review_something_went_wrong), it) { errorMessage = null } }
}

// MARK: Small parts

/** Height under the video that the media also uses: the scrubber and its times. */
private val ScrubberReserve = 64.dp

/**
 * Tablets: the video as large as the window allows, with the details around it, as the iOS app's
 * WideDetailLayout.
 * - Upright windows (narrower than 1.1 × their height): the video across the width, the details under it.
 * - Wide windows: the video on the left and the details beside it; when the video leaves 200 dp or
 *   more under it, `extra` (the lifecycle) fills that room instead of the side.
 * `media` gets the video's width and height; `details` and `extra` bring their own 16 dp margins
 * (`extra` gets the margin to use).
 */
@Composable
private fun WideDetailLayout(
    aspect: Float,
    modifier: Modifier,
    media: @Composable (Dp, Dp) -> Unit,
    details: @Composable ColumnScope.() -> Unit,
    extra: @Composable ColumnScope.(Dp) -> Unit,
    actions: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val w = maxWidth
        val h = maxHeight
        if (w < h * 1.1f) {
            val height = minOf((w - 32.dp) / aspect, h * 0.55f)
            Column(Modifier.fillMaxSize().padding(bottom = 8.dp)) {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.TopCenter) { media(height * aspect, height) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    details()
                    extra(16.dp)
                }
                actions()
            }
        } else {
            val side = minOf(400.dp, w * 0.36f)
            val mediaWidth = maxOf(200.dp, w - side - 32.dp)
            val height = minOf(mediaWidth / aspect, maxOf(160.dp, h - 28.dp - ScrubberReserve))
            // Room left under the video for the lifecycle.
            val extraBelow = h - 28.dp - ScrubberReserve - height >= 200.dp
            Row(Modifier.fillMaxSize().padding(start = 16.dp, top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(mediaWidth).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    media(height * aspect, height)
                    if (extraBelow) {
                        Column(Modifier.width(height * aspect).weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                            extra(0.dp)
                        }
                    }
                }
                Column(Modifier.width(side).fillMaxHeight()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                        details()
                        if (!extraBelow) extra(16.dp)
                    }
                    actions()
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)), content = content)
}

@Composable
private fun InfoTile(title: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AnalysisField(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The threat level and any other concerns, one per line, as Frigate lists them. */
private fun concerns(ai: GenAISummary): String {
    val list = mutableListOf<String>()
    val level = ai.threatLevel
    if (level != null && level > 0) list.add(L(if (level >= 2) R.string.review_security_concern else R.string.review_needs_review))
    list.addAll(ai.otherConcerns ?: emptyList())
    return if (list.isEmpty()) L(R.string.review_none) else list.joinToString("\n") { "• $it" }
}

@Composable
private fun dotColor(e: TimelineEntry): Color = when (e.classType) {
    "visible", "heard" -> Palette.labelColor(e.label ?: "")
    "entered_zone" -> MaterialTheme.colorScheme.primary
    "gone" -> Palette.status.offline
    else -> MaterialTheme.colorScheme.outline
}

/** "Person entered Driveway", as Frigate's tracking details. */
private fun lifecycleText(e: TimelineEntry): String {
    val label = Format.labelName(e.label ?: "object")
    return when (e.classType) {
        "visible" -> L(R.string.review_lc_detected, label)
        "entered_zone" -> {
            val z = e.zones.joinToString(", ") { Format.prettyName(it) }
            if (z.isEmpty()) L(R.string.review_lc_entered_zone, label) else L(R.string.review_lc_entered, label, z)
        }
        "active" -> L(R.string.review_lc_active, label)
        "stationary" -> L(R.string.review_lc_stationary, label)
        "attribute" -> L(R.string.review_lc_attribute, label, e.attribute?.let { Format.labelName(it) } ?: "attribute")
        "gone" -> L(R.string.review_lc_gone, label)
        "heard" -> L(R.string.review_lc_heard, label)
        "external" -> L(R.string.review_lc_external, label)
        else -> "$label: ${e.classType.replace('_', ' ')}"
    }
}

/** "Today, 15:06:12 to 15:06:40"; the end date too when Frigate closed the item on another day. */
private fun timeRangeText(start: Double, end: Double?): String {
    val day = Format.dayTitle(start.toDate())
    val s = Format.time(start, seconds = true)
    if (end == null) return L(R.string.review_time_in_progress, day, s)
    val a = Calendar.getInstance().apply { timeInMillis = (start * 1000).toLong() }
    val b = Calendar.getInstance().apply { timeInMillis = (end * 1000).toLong() }
    if (a.get(Calendar.YEAR) != b.get(Calendar.YEAR) || a.get(Calendar.DAY_OF_YEAR) != b.get(Calendar.DAY_OF_YEAR)) {
        return L(R.string.review_time_range_days, day, s, Format.dayTitle(end.toDate()), Format.time(end, seconds = true))
    }
    return L(R.string.review_time_range, day, s, Format.time(end, seconds = true))
}

/** A round translucent button over the video. */
@Composable
private fun RoundButton(icon: ImageVector, label: String, size: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.size(size.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)) {
        Icon(icon, label, Modifier.size((size * 0.55f).dp), tint = Color.White)
    }
}

/** "Reviewed" over the full-screen video: filled while not yet reviewed, with a spinner while it is marked. */
@Composable
private fun ReviewedPill(reviewed: Boolean, busy: Boolean, onClick: () -> Unit) {
    val bg = if (reviewed) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primary
    val fg = if (reviewed) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onPrimary
    Row(
        Modifier.height(34.dp).clip(CircleShape).background(bg).clickable(enabled = !reviewed && !busy, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (busy) Spinner(fg, 14.dp)
        else Icon(if (reviewed) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = fg)
        Text(stringResource(R.string.review_reviewed), color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
    }
}

/**
 * The clip's track: the event's span highlighted, the playhead, and the part played. A tap or
 * drag seeks; `done` is true when the finger lifts.
 */
@Composable
private fun EventScrubber(
    position: Double, duration: Double, eventStart: Double, eventEnd: Double, onDark: Boolean,
    onScrub: (Double, Boolean) -> Unit,
) {
    val track = if (onDark) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val warning = Palette.status.warning
    val total = max(duration, 0.1)
    var lastX by remember { mutableFloatStateOf(0f) }
    Canvas(
        Modifier.fillMaxWidth().height(28.dp)
            .pointerInput(total) {
                detectTapGestures { o -> onScrub((o.x / size.width).coerceIn(0f, 1f) * total, true) }
            }
            .pointerInput(total) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> lastX = o.x; onScrub((o.x / size.width).coerceIn(0f, 1f) * total, false) },
                    onDragEnd = { onScrub((lastX / size.width).coerceIn(0f, 1f) * total, true) },
                    onDragCancel = { onScrub((lastX / size.width).coerceIn(0f, 1f) * total, true) },
                ) { change, _ ->
                    lastX = change.position.x
                    onScrub((lastX / size.width).coerceIn(0f, 1f) * total, false)
                }
            },
    ) {
        val w = size.width
        val cy = size.height / 2
        val barH = 4.dp.toPx()
        drawRoundRect(track, Offset(0f, cy - barH / 2), Size(w, barH), CornerRadius(barH / 2))
        val evStart = (eventStart / total).coerceIn(0.0, 1.0).toFloat()
        // At least a sliver: in a long video a short event is under a pixel.
        val evEnd = max(evStart + 4f / max(w, 1f), (eventEnd / total).toFloat()).coerceAtMost(1f)
        val evH = 14.dp.toPx()
        drawRoundRect(warning.copy(alpha = 0.28f), Offset(evStart * w, cy - evH / 2), Size(max(0f, (evEnd - evStart) * w), evH), CornerRadius(3.dp.toPx()))
        val progress = (position / total).coerceIn(0.0, 1.0).toFloat()
        drawRoundRect(accent, Offset(0f, cy - barH / 2), Size(progress * w, barH), CornerRadius(barH / 2))
        drawCircle(Color.Black.copy(alpha = 0.25f), 9.dp.toPx(), Offset(progress * w, cy))
        drawCircle(Color.White, 8.dp.toPx(), Offset(progress * w, cy))
    }
}

/** A tracked object's snapshot, large, over everything. */
@Composable
private fun SnapshotViewer(e: FrigateEvent, conn: ServerConnection, onClose: () -> Unit) {
    val c = conn.client ?: return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            RemoteImage(if (e.hasSnapshot == true) c.snapshotUrl(e) else c.thumbnailUrl(e), Modifier.fillMaxSize().safeDrawingPadding(),
                contentScale = ContentScale.Fit, contentDescription = Format.labelName(e.label))
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().safeDrawingPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onClose, modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Color.White)
                }
                LabelTag(e.label, fontSize = 12)
                Text("${cameraName(conn, e.camera)} · ${Format.time(e.startTime, seconds = true)}", color = Color.White,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
