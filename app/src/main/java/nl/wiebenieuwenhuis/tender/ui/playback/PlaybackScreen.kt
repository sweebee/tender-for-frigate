package nl.wiebenieuwenhuis.tender.ui.playback

import android.app.Activity
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavGraphBuilder
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.ReviewItem
import nl.wiebenieuwenhuis.tender.api.Severity
import nl.wiebenieuwenhuis.tender.api.nowSeconds
import nl.wiebenieuwenhuis.tender.api.toDate
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.AppChrome
import nl.wiebenieuwenhuis.tender.ui.CameraRoute
import nl.wiebenieuwenhuis.tender.ui.LiveTab
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.PlaybackRoute
import nl.wiebenieuwenhuis.tender.ui.components.ConfirmDialog
import nl.wiebenieuwenhuis.tender.ui.components.DestructiveMenuItem
import nl.wiebenieuwenhuis.tender.ui.components.LabelIcon
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.MonoDigits
import nl.wiebenieuwenhuis.tender.ui.components.RemoteImage
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.selectTab
import java.util.Date

/** Recordings of a camera on a day, with the events list, the timeline or the detail view. */
@Composable
fun PlaybackScreen(route: PlaybackRoute) {
    val scope = rememberCoroutineScope()
    val player = rememberRecordingPlayer()
    val preview = remember { PreviewScrubber(scope) }
    DisposableEffect(preview) { onDispose { preview.release() } }
    val state = remember { PlaybackState(route, scope, player, preview) }
    val exporter = remember { ClipExporter() }
    val nav = LocalNav.current
    val activity = LocalActivity.current
    val context = LocalContext.current

    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val tallEnough = sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    /** Tablet: video with a horizontal timeline under it, events beside. */
    val wide = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) && tallEnough
    /** Phone held sideways: full-screen video with an overlay, like an NVR app. */
    val landscape = !wide && !tallEnough

    var showCalendar by remember { mutableStateOf(false) }
    var saveRequest by remember { mutableStateOf<SaveToFrigateRequest?>(null) }
    var deleting by remember { mutableStateOf<ReviewItem?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    val client = state.client
    // Again once the server is connected: opened from a link at launch, it is not yet.
    LaunchedEffect(state.camera, state.day, client == null) { state.load() }
    LaunchedEffect(player.finishedCount) { if (player.finishedCount > 0) state.hourFinished() }
    LaunchedEffect(player.wantsToPlay) {
        if (player.wantsToPlay) return@LaunchedEffect
        // Paused: bring the controls back so it is clear the video is not stuck. Wait a moment
        // first: at the end of each hour playback stops briefly and resumes by itself.
        delay(800)
        if (!player.wantsToPlay) { state.overlayVisible = true; state.keepOverlay() }
    }
    LaunchedEffect(state.overlayToken) {
        // Hide the video controls after 4 seconds of playback without touches.
        while (true) {
            delay(4000)
            if (!player.wantsToPlay || state.scrubTime != null || state.showEventsPanel) return@LaunchedEffect
            if (state.menusOpen > 0) continue // a menu over the video is open: check again later
            state.overlayVisible = false
            return@LaunchedEffect
        }
    }
    // Phone: leaving the landscape view (turned upright) ends full screen.
    LaunchedEffect(landscape) { if (!landscape && !wide) state.fullscreen = false }

    val immersive = landscape || (wide && state.fullscreen)
    LaunchedEffect(immersive) {
        AppChrome.immersive = immersive
        activity?.let { setSystemBarsHidden(it, immersive) }
    }
    DisposableEffect(Unit) {
        onDispose {
            AppChrome.immersive = false
            activity?.let {
                setSystemBarsHidden(it, false)
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
    OrientationUnlock(activity)

    fun setFullscreen(on: Boolean) {
        state.fullscreen = on
        state.keepOverlay()
        if (!wide) {
            // Phone: full screen is the landscape view; turn the screen for it, and back.
            activity?.requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    BackHandler(state.showEventsPanel) { state.showEventsPanel = false }
    BackHandler(state.fullscreen && !state.showEventsPanel) { setFullscreen(false) }

    val actions = PlaybackActions(
        saveToGallery = { s, e ->
            val c = state.client
            if (c != null) {
                val (a, b) = state.clampRange(s, e)
                AppModel.scope.launch { exporter.saveToGallery(c, state.camera, a, b) }
            }
        },
        saveToFrigate = { s, e, label ->
            val c = state.client
            if (c != null) {
                val (a, b) = state.clampRange(s, e)
                saveRequest = SaveToFrigateRequest(c, state.camera, a, b,
                    ClipExporter.exportName(state.camera, state.cameraConfig?.displayName, a, label))
            }
        },
        share = { s, e ->
            val c = state.client
            if (c != null) {
                val (a, b) = state.clampRange(s, e)
                scope.launch { exporter.share(context, c, state.camera, a, b) }
            }
        },
        exportSpan = {
            val c = state.client
            if (c != null) {
                val r = state.currentReview
                val (a, b) = if (r != null) state.clampRange(r.startTime - 3, (r.endTime ?: nowSeconds()) + 3)
                else state.clampRange(state.wallTime - 30, state.wallTime + 30)
                val cams = (state.conn?.cameras ?: emptyList()).toMutableList()
                if (cams.none { it.name == state.camera }) state.cameraConfig?.let { cams.add(0, it) }
                saveRequest = SaveToFrigateRequest(c, state.camera, a, b,
                    ClipExporter.exportName(state.camera, state.cameraConfig?.displayName, a, r?.labels?.firstOrNull()), cams)
            }
        },
        delete = { deleting = it },
        openLive = {
            selectTab(nav, LiveTab)
            nav.navigate(CameraRoute(route.serverId, state.camera))
        },
        pickDay = { showCalendar = true },
        setFullscreen = ::setFullscreen,
        canDelete = state.conn?.isAdmin != false,
    )

    Box(Modifier.fillMaxSize()) {
        when {
            (wide && state.fullscreen) || landscape -> LandscapeBody(state, exporter, actions, wide)
            wide -> WideBody(state, exporter, actions)
            else -> PortraitBody(state, exporter, actions)
        }
        ExportToast(exporter, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.displayCutout))
    }

    if (showCalendar) {
        RecordingCalendarSheet(
            Date(state.day), listOfNotNull(client?.let { DaySource(it, listOf(state.camera)) }),
            onPick = { state.day = it.time }, onDismiss = { showCalendar = false },
        )
    }
    saveRequest?.let { SaveToFrigateSheet(it, exporter) { saveRequest = null } }
    deleting?.let { r ->
        ConfirmDialog(
            title = stringResource(R.string.playback_delete_title),
            message = eventTitle(r),
            confirmText = stringResource(R.string.delete),
            onConfirm = {
                val c = state.client
                if (c != null) scope.launch {
                    // The control that started it (a menu) is gone: the pill shows the wait.
                    exporter.state = ClipExporter.State.Working(context.getString(R.string.playback_deleting))
                    try {
                        c.deleteReviews(listOf(r.id))
                        exporter.state = ClipExporter.State.Idle
                        state.reviews = state.reviews.filter { it.id != r.id }
                        AppModel.scope.launch { state.conn?.refresh() }
                    } catch (e: Exception) {
                        exporter.state = ClipExporter.State.Idle
                        deleteError = e.message ?: context.getString(R.string.playback_something_wrong)
                    }
                }
            },
            onDismiss = { deleting = null },
        )
    }
    deleteError?.let { MessageDialog(stringResource(R.string.playback_something_wrong), it) { deleteError = null } }
}

/** Private routes of playback: none; the screen itself is the shared [PlaybackRoute]. */
fun NavGraphBuilder.playbackDestinations() {}

/** What the screen's menus can do. */
internal class PlaybackActions(
    val saveToGallery: (Double, Double) -> Unit,
    val saveToFrigate: (Double, Double, String?) -> Unit,
    val share: (Double, Double) -> Unit,
    val exportSpan: () -> Unit,
    val delete: (ReviewItem) -> Unit,
    val openLive: () -> Unit,
    val pickDay: () -> Unit,
    val setFullscreen: (Boolean) -> Unit,
    val canDelete: Boolean,
)

private fun setSystemBarsHidden(activity: Activity, hidden: Boolean) {
    val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (hidden) {
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        c.show(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * The full-screen button locks the orientation (sideways, or upright again on leaving). Once the
 * phone is actually held that way the lock is lifted, so turning it moves between the views.
 */
@Composable
private fun OrientationUnlock(activity: Activity?) {
    val ctx = LocalContext.current
    DisposableEffect(activity) {
        val listener = object : OrientationEventListener(ctx) {
            override fun onOrientationChanged(deg: Int) {
                val a = activity ?: return
                if (deg == ORIENTATION_UNKNOWN) return
                // With auto-rotate off, lifting the lock would turn the screen back upright at once.
                val autoRotate = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                if (!autoRotate && a.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) return
                val upright = deg < 20 || deg > 340
                val sideways = deg in 70..110 || deg in 250..290
                when (a.requestedOrientation) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> if (sideways) a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> if (upright) a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
}

@Composable
private fun eventTitle(r: ReviewItem): String =
    stringResource(R.string.playback_event_at, r.labels.firstOrNull()?.let { Format.labelName(it) } ?: stringResource(R.string.playback_event),
        Format.time(r.startTime, seconds = true))

// MARK: - Portrait and tablet

@Composable
private fun PlaybackTopBar(state: PlaybackState, actions: PlaybackActions) {
    val nav = LocalNav.current
    TopAppBar(
        title = { CameraMenu(state, onVideo = false) },
        navigationIcon = {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        },
        actions = {
            IconButton(onClick = actions.pickDay) { Icon(Icons.Outlined.CalendarMonth, stringResource(R.string.playback_pick_date)) }
        },
    )
}

/** The camera's name (and the server's) that opens a menu of the server's cameras. */
@Composable
private fun CameraMenu(state: PlaybackState, onVideo: Boolean) {
    var open by remember { mutableStateOf(false) }
    val conn = state.conn
    val textColor = if (onVideo) Color.White else MaterialTheme.colorScheme.onSurface
    val secondary = if (onVideo) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    fun close() { open = false; state.menusOpen--; state.keepOverlay() }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true; state.menusOpen++; state.keepOverlay() }
                .heightIn(min = 44.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(state.cameraConfig?.displayName ?: Format.prettyName(state.camera), color = textColor,
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(conn?.server?.name ?: "", color = secondary, fontSize = 12.sp, maxLines = 1)
            }
            Icon(Icons.Filled.ArrowDropDown, null, tint = secondary)
        }
        DropdownMenu(open, onDismissRequest = ::close) {
            for (cam in conn?.cameras ?: emptyList()) {
                DropdownMenuItem(
                    text = { Text(cam.displayName, maxLines = 1) },
                    leadingIcon = { if (cam.name == state.camera) Icon(Icons.Filled.Check, null) else Box(Modifier.size(24.dp)) },
                    onClick = { close(); state.switchCamera(cam.name) },
                )
            }
        }
    }
}

@Composable
private fun TabBar(tabs: List<PlaybackState.Tab>, selected: PlaybackState.Tab, onSelect: (PlaybackState.Tab) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        tabs.forEachIndexed { i, t ->
            SegmentedButton(selected == t, { onSelect(t) }, SegmentedButtonDefaults.itemShape(i, tabs.size), icon = {}) {
                Text(tabTitle(t), maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun tabTitle(t: PlaybackState.Tab) = stringResource(
    when (t) {
        PlaybackState.Tab.Events -> R.string.playback_events
        PlaybackState.Tab.Timeline -> R.string.playback_timeline
        PlaybackState.Tab.Detail -> R.string.playback_detail
    }
)

@Composable
private fun FilterBar(state: PlaybackState, modifier: Modifier = Modifier, edge: Dp = 16.dp) {
    PlaybackFilterBar(state.filter, { state.filter = it }, state.reviews, state.cameraConfig?.zones ?: emptyList(), modifier,
        PaddingValues(horizontal = edge))
}

@Composable
private fun PortraitBody(state: PlaybackState, exporter: ClipExporter, actions: PlaybackActions) {
    Scaffold(topBar = { PlaybackTopBar(state, actions) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            VideoArea(state, exporter, actions, large = false, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            TabBar(listOf(PlaybackState.Tab.Events, PlaybackState.Tab.Timeline, PlaybackState.Tab.Detail), state.tab, { state.tab = it },
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp))
            FilterBar(state, Modifier.padding(bottom = 8.dp))
            when (state.tab) {
                PlaybackState.Tab.Events -> EventsList(state, actions, Modifier.weight(1f))
                PlaybackState.Tab.Detail -> DetailList(state, actions, Modifier.weight(1f))
                PlaybackState.Tab.Timeline -> PlaybackTimeline(
                    state.dayStart, state.dayEnd, { state.wallTime }, state.motion, state.visibleReviews, state.map,
                    onScrub = state::scrub, onScrubEnd = { t, snap -> state.finishScrub(t, snap) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    annotationOffset = state.annotationOffset, onSelect = state::play, segments = state.segments,
                    onScrubCancel = state::cancelScrub,
                )
            }
        }
    }
}

@Composable
private fun WideBody(state: PlaybackState, exporter: ClipExporter, actions: PlaybackActions) {
    Scaffold(topBar = { PlaybackTopBar(state, actions) }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            // Explicit size: the video fills the column at 16:9 but leaves room for the timeline.
            val side = minOf(380.dp, maxWidth * 0.34f)
            val videoWidth = maxOf(200.dp, maxWidth - 32.dp - 16.dp - side)
            val videoHeight = minOf(videoWidth * 9f / 16f, maxOf(160.dp, maxHeight - 150.dp))
            Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(videoWidth), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    VideoArea(state, exporter, actions, large = true,
                        Modifier.width(videoHeight * 16f / 9f).height(videoHeight).clip(RoundedCornerShape(12.dp)))
                    TimelineCard(state)
                }
                Column(Modifier.width(side).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabBar(listOf(PlaybackState.Tab.Events, PlaybackState.Tab.Detail), state.sideTab, { state.sideTab = it })
                    FilterBar(state, Modifier.fillMaxWidth(), edge = 0.dp)
                    if (state.sideTab == PlaybackState.Tab.Detail) DetailList(state, actions, Modifier.weight(1f), edge = 0.dp)
                    else EventsList(state, actions, Modifier.weight(1f), edge = 0.dp)
                }
            }
        }
    }
}

/** The horizontal timeline as a card under the video (tablet). */
@Composable
private fun TimelineCard(state: PlaybackState) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(8.dp),
    ) {
        PlayheadTimeline(state, onVideo = false) {}
        HorizontalZoomButtons(state.timelineScale, { state.timelineScale = it }, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The horizontal timeline with the playhead line over its middle. */
@Composable
private fun PlayheadTimeline(state: PlaybackState, onVideo: Boolean, onUse: () -> Unit) {
    Box(contentAlignment = Alignment.TopCenter) {
        HorizontalTimeline(
            state.dayStart, state.dayEnd, { state.wallTime }, state.motion, state.visibleReviews, state.map,
            scale = state.timelineScale, onScaleChange = { state.timelineScale = it },
            onScrub = state::scrub,
            onScrubEnd = { t, snap -> state.finishScrub(t, snap); onUse() },
            modifier = Modifier.padding(top = 6.dp),
            onVideo = onVideo, onInteract = onUse, onScrubCancel = state::cancelScrub,
            onSelect = { state.play(it); onUse() },
        )
        Box(Modifier.width(2.dp).height(74.dp).background(MaterialTheme.colorScheme.primary))
    }
}

// MARK: - Video

/** The video with its overlay: time and objects, save and full screen, and the play controls. */
@Composable
private fun VideoArea(state: PlaybackState, exporter: ClipExporter, actions: PlaybackActions, large: Boolean, modifier: Modifier) {
    val player = state.player
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        ZoomableVideo(player, Modifier.fillMaxSize()) { state.toggleOverlay() }
        if (state.scrubTime != null || state.preview.showing) PreviewSurface(state.preview, Modifier.fillMaxSize())
        if (state.gapTime != null && !state.preview.showing) GapPlaceholder(state)
        VideoStatus(state, large)
        if (!state.map.isEmpty) {
            Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TimeLabel(state, Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp), 12)
                // Every object in the event, as icons.
                for (l in state.currentReview?.labels ?: emptyList()) LabelIcon(l, size = 24.dp)
            }
            FadingControls(state.overlayVisible, Modifier.align(Alignment.TopEnd)) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SaveMenu(state, exporter, actions, 36.dp)
                    FullscreenButton(false) { actions.setFullscreen(true) }
                }
            }
            FadingControls(state.overlayVisible, Modifier.align(Alignment.BottomCenter)) {
                PlaybackOverlayControls(player, large = large, togglePlay = state::togglePlay, onUse = state::keepOverlay)
            }
        }
    }
}

@Composable
private fun FadingControls(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(200)), exit = fadeOut(tween(250))) { content() }
}

/** Loading, no recordings, a playback error, or buffering. */
@Composable
private fun VideoStatus(state: PlaybackState, large: Boolean) {
    val player = state.player
    when {
        state.loading -> VideoSpinner(large)
        state.map.isEmpty -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.VideoLibrary, null, tint = Color.White.copy(alpha = 0.8f))
            Text(stringResource(R.string.playback_no_recordings_for, Format.dayTitle(Date(state.day)).lowercase()),
                color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        player.error != null -> Text(player.error ?: "", Modifier.padding(16.dp), color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
        player.isBuffering && state.gapTime == null && !state.preview.showing -> VideoSpinner(large)
    }
}

/** Shown instead of the video when the playhead is on a moment without recordings. */
@Composable
private fun GapPlaceholder(state: PlaybackState) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.VideocamOff, null, Modifier.size(30.dp), tint = Color.White.copy(alpha = 0.85f))
            Text(stringResource(R.string.playback_no_recording_now), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            if (state.map.spans.any { it.wallStart > (state.gapTime ?: 0.0) }) {
                Text(stringResource(R.string.playback_press_play_next), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Date and time at the playhead, with a pause icon while playback is paused. */
@Composable
private fun TimeLabel(state: PlaybackState, modifier: Modifier, fontSize: Int) {
    val t = state.wallTime
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (!state.player.wantsToPlay && !state.map.isEmpty) {
            Icon(Icons.Filled.Pause, null, Modifier.size(12.dp), tint = Color.White)
            Spacer(Modifier.width(4.dp))
        }
        Text("${Format.dayTitle(t.toDate())} · ${Format.time(t, seconds = true)}", color = Color.White, fontSize = fontSize.sp,
            fontWeight = FontWeight.SemiBold, style = MonoDigits, maxLines = 1, softWrap = false)
    }
}

/** Save button on the video: the event that is playing, or a span around the playhead. */
@Composable
private fun SaveMenu(state: PlaybackState, exporter: ClipExporter, actions: PlaybackActions, size: Dp) {
    var open by remember { mutableStateOf(false) }
    /** Seconds of the "from the playhead" span whose choices are shown, or null for the main menu. */
    var span by remember { mutableStateOf<Double?>(null) }
    // Looked up when the menu opens: an open menu must not change under the finger.
    var review by remember { mutableStateOf<ReviewItem?>(null) }
    var at by remember { mutableDoubleStateOf(0.0) }
    fun close() { open = false; span = null; state.menusOpen--; state.keepOverlay() }
    Box {
        OverlayButton(Icons.Filled.Download, stringResource(R.string.playback_save_video), size, busy = exporter.isWorking) {
            review = state.menuReview
            at = state.wallTime
            open = true
            state.menusOpen++
            state.keepOverlay()
        }
        DropdownMenu(open, onDismissRequest = ::close) {
            val s = span
            if (s != null) {
                MenuHeader(spanTitle(s))
                DropdownMenuItem(text = { Text(stringResource(R.string.playback_to_gallery)) }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                    onClick = { close(); actions.saveToGallery(at - s / 2, at + s / 2) })
                DropdownMenuItem(text = { Text(stringResource(R.string.playback_to_frigate_exports)) }, leadingIcon = { Icon(Icons.Outlined.Storage, null) },
                    onClick = { close(); actions.saveToFrigate(at - s / 2, at + s / 2, null) })
                return@DropdownMenu
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.playback_export_ellipsis)) }, leadingIcon = { Icon(Icons.Outlined.VideoLibrary, null) },
                onClick = { close(); actions.exportSpan() })
            review?.let { r ->
                val end = (r.endTime ?: nowSeconds()) + 3
                HorizontalDivider()
                MenuHeader(eventTitle(r))
                DropdownMenuItem(text = { Text(stringResource(R.string.playback_save_event_gallery)) }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                    onClick = { close(); actions.saveToGallery(r.startTime - 3, end) })
                DropdownMenuItem(text = { Text(stringResource(R.string.playback_save_event_frigate)) }, leadingIcon = { Icon(Icons.Outlined.Storage, null) },
                    onClick = { close(); actions.saveToFrigate(r.startTime - 3, end, r.labels.firstOrNull()) })
                DropdownMenuItem(text = { Text(stringResource(R.string.playback_share_event)) }, leadingIcon = { Icon(Icons.Outlined.Share, null) },
                    onClick = { close(); actions.share(r.startTime - 3, end) })
                if (state.conn?.isAdmin != false) {
                    DestructiveMenuItem(stringResource(R.string.playback_delete_event), Icons.Outlined.Delete) { close(); actions.delete(r) }
                }
            }
            HorizontalDivider()
            MenuHeader(stringResource(R.string.playback_from_playhead))
            for (secs in listOf(30.0, 60.0, 300.0)) {
                DropdownMenuItem(text = { Text(spanTitle(secs)) }, leadingIcon = { Icon(Icons.Outlined.Movie, null) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) }, onClick = { span = secs })
            }
        }
    }
}

@Composable
private fun spanTitle(secs: Double): String =
    if (secs < 60) stringResource(R.string.playback_save_seconds, secs.toInt())
    else pluralStringResource(R.plurals.playback_save_minutes, (secs / 60).toInt(), (secs / 60).toInt())

@Composable
private fun MenuHeader(text: String) {
    Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// MARK: - Events

/** Long-press actions on an event: save, share, delete. */
@Composable
private fun EventMenu(r: ReviewItem, open: Boolean, actions: PlaybackActions, onDismiss: () -> Unit) {
    DropdownMenu(open, onDismissRequest = onDismiss) {
        val end = (r.endTime ?: nowSeconds()) + 3
        MenuHeader(eventTitle(r))
        DropdownMenuItem(text = { Text(stringResource(R.string.playback_save_event_gallery)) }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
            onClick = { onDismiss(); actions.saveToGallery(r.startTime - 3, end) })
        DropdownMenuItem(text = { Text(stringResource(R.string.playback_save_event_frigate)) }, leadingIcon = { Icon(Icons.Outlined.Storage, null) },
            onClick = { onDismiss(); actions.saveToFrigate(r.startTime - 3, end, r.labels.firstOrNull()) })
        DropdownMenuItem(text = { Text(stringResource(R.string.playback_share_event)) }, leadingIcon = { Icon(Icons.Outlined.Share, null) },
            onClick = { onDismiss(); actions.share(r.startTime - 3, end) })
        // Deleting is for admins only in Frigate.
        if (actions.canDelete) DestructiveMenuItem(stringResource(R.string.playback_delete_event), Icons.Outlined.Delete) { onDismiss(); actions.delete(r) }
    }
}

@Composable
private fun EventsList(state: PlaybackState, actions: PlaybackActions, modifier: Modifier, edge: Dp = 16.dp) {
    val list = rememberLazyListState()
    val shown = state.visibleReviews
    val playingId = state.currentReview?.id
    val day = Format.dayTitle(Date(state.day))
    // Scroll to the event asked for, once it is in the list.
    LaunchedEffect(state.pendingReviewId, state.loading) {
        val id = state.pendingReviewId ?: return@LaunchedEffect
        if (state.loading) return@LaunchedEffect
        val i = shown.indexOfFirst { it.id == id }
        if (i >= 0) list.scrollToItem(i + 1)
    }
    LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(start = edge, end = edge, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "header") {
            // No count while loading: "0 events" read as if the day were empty.
            SectionLabel(
                when {
                    state.loading && state.reviews.isEmpty() -> day
                    state.filter.isActive -> pluralStringResource(R.plurals.playback_n_of_m_events, state.reviews.size, day, shown.size, state.reviews.size)
                    else -> pluralStringResource(R.plurals.playback_n_events, shown.size, day, shown.size)
                },
                Modifier.padding(top = 2.dp),
            )
        }
        when {
            state.loading && state.reviews.isEmpty() -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            state.reviews.isEmpty() -> item(key = "none") {
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.playback_no_events_day), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            shown.isEmpty() -> item(key = "filtered") {
                Column(Modifier.fillMaxWidth().heightIn(min = 80.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                    Text(stringResource(R.string.playback_no_events_filter), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { state.filter = PlaybackFilter() }) { Text(stringResource(R.string.playback_clear_filters)) }
                }
            }
        }
        items(shown, key = { it.id }) { r -> EventRow(state, r, playingId == r.id, actions) }
    }
}

@Composable
private fun EventRow(state: PlaybackState, r: ReviewItem, playing: Boolean, actions: PlaybackActions) {
    var menu by remember { mutableStateOf(false) }
    val s = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(s.surfaceContainer)
                .border(if (playing) 1.5.dp else 1.dp, if (playing) s.primary else s.outlineVariant, shape)
                .combinedClickable(onClick = { state.play(r) }, onLongClick = { menu = true })
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                RemoteImage(state.client?.thumbnailUrl(r), Modifier.fillMaxSize())
                Text(state.recordedDurationText(r), Modifier.align(Alignment.BottomEnd).padding(3.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                    color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, style = MonoDigits, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { for (l in r.labels) LabelIcon(l, size = 20.dp) }
                Text(Format.time(r.startTime, seconds = true), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val zones = r.zones.map { Format.prettyName(it) }
                Text(
                    if (zones.isEmpty()) stringResource(if (r.severity == Severity.Alert) R.string.playback_alert else R.string.playback_detection)
                    else stringResource(R.string.playback_zone, zones.joinToString(", ")),
                    fontSize = 12.sp, color = s.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (playing) {
                Box(Modifier.padding(end = 4.dp).size(26.dp).background(s.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.playback_playing), Modifier.size(16.dp), tint = s.onPrimary)
                }
            }
        }
        EventMenu(r, menu, actions) { menu = false }
    }
}

@Composable
private fun DetailList(state: PlaybackState, actions: PlaybackActions, modifier: Modifier, edge: Dp = 16.dp, compact: Boolean = false) {
    var menuFor by remember { mutableStateOf<ReviewItem?>(null) }
    Box(modifier) {
        PlaybackDetailList(
            state.visibleReviews, state.currentReview?.id, { state.wallTime }, state.annotationOffset, state.client,
            Modifier.fillMaxSize(), compact = compact || edge == 0.dp, onLongPress = { menuFor = it },
        ) { t -> state.jump(t, autoplay = true, earlier = 2.0); state.keepOverlay() }
        menuFor?.let { r -> Box(Modifier.align(Alignment.Center)) { EventMenu(r, true, actions) { menuFor = null } } }
    }
}

// MARK: - Landscape and full screen

/**
 * Full-screen video. A tap shows or hides the overlay; it hides by itself after 4 seconds of
 * playback without touches.
 */
@Composable
private fun LandscapeBody(state: PlaybackState, exporter: ClipExporter, actions: PlaybackActions, wide: Boolean) {
    val player = state.player
    val nav = LocalNav.current
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        ZoomableVideo(player, Modifier.fillMaxSize()) { state.toggleOverlay() }
        if (state.scrubTime != null || state.preview.showing) PreviewSurface(state.preview, Modifier.fillMaxSize())
        if (state.gapTime != null && !state.preview.showing) GapPlaceholder(state)
        VideoStatus(state, large = true)

        FadingControls(state.overlayVisible, Modifier.fillMaxSize()) {
            val safe = WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)
            Column(Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).windowInsetsPadding(safe).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.fullscreen) FullscreenButton(true, 40.dp) { actions.setFullscreen(false) }
                    else IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                    }
                    CameraMenu(state, onVideo = true)
                    Spacer(Modifier.weight(1f))
                    if (!state.map.isEmpty) TimeLabel(state, Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 5.dp), 13)
                    for (l in state.currentReview?.labels ?: emptyList()) LabelIcon(l, size = 26.dp)
                    IconButton(onClick = { actions.pickDay(); state.keepOverlay() }) {
                        Icon(Icons.Outlined.CalendarMonth, stringResource(R.string.playback_pick_date), tint = Color.White)
                    }
                }
                // Middle: play controls centred, side buttons on the right.
                Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(safe)) {
                    if (!state.map.isEmpty) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            OverlayButton(Icons.Filled.Replay10, stringResource(R.string.playback_back_10), 50.dp) { player.skip(-10.0); state.keepOverlay() }
                            OverlayButton(if (player.wantsToPlay) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                stringResource(if (player.wantsToPlay) R.string.playback_pause else R.string.playback_play), 60.dp) {
                                state.togglePlay(); state.keepOverlay()
                            }
                            OverlayButton(Icons.Filled.Forward10, stringResource(R.string.playback_forward_10), 50.dp) { player.skip(10.0); state.keepOverlay() }
                        }
                        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            OverlayButton(Icons.AutoMirrored.Filled.List, stringResource(R.string.playback_events)) {
                                state.showEventsPanel = true; state.keepOverlay()
                            }
                            SaveMenu(state, exporter, actions, 44.dp)
                            OverlayMenuButton(RecordingPlayer.rateTitle(player.rate), stringResource(R.string.playback_speed), RecordingPlayer.rates,
                                player.rate, RecordingPlayer::rateTitle, onOpen = { state.keepOverlay() }) { player.changeRate(it); state.keepOverlay() }
                        }
                    }
                }
                // Bottom: horizontal timeline.
                Column(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).windowInsetsPadding(safe)
                        .padding(start = 8.dp, end = 8.dp, bottom = if (wide) 16.dp else 8.dp),
                ) {
                    PlayheadTimeline(state, onVideo = true, onUse = state::keepOverlay)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                        HorizontalZoomButtons(state.timelineScale, { state.timelineScale = it }, Color.White.copy(alpha = 0.7f), state::keepOverlay)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = actions.openLive, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp)) {
                            Text(stringResource(R.string.tab_live), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            state.showEventsPanel, Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
        ) {
            LandscapeEventsPanel(state, actions)
        }
    }
}

@Composable
private fun LandscapeEventsPanel(state: PlaybackState, actions: PlaybackActions) {
    val s = MaterialTheme.colorScheme
    val detail = state.sideTab == PlaybackState.Tab.Detail
    Column(
        Modifier
            .width(if (detail) 300.dp else 250.dp)
            .fillMaxHeight()
            .background(s.surfaceContainer.copy(alpha = 0.97f))
            .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.End))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabBar(listOf(PlaybackState.Tab.Events, PlaybackState.Tab.Detail), state.sideTab, { state.sideTab = it }, Modifier.weight(1f))
            IconButton(onClick = { state.showEventsPanel = false }, Modifier.size(36.dp)) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
        }
        if (detail) {
            DetailList(state, actions, Modifier.weight(1f), edge = 0.dp, compact = true)
        } else {
            val list = rememberLazyListState()
            val playingId = state.currentReview?.id
            LaunchedEffect(Unit) {
                val i = state.visibleReviews.indexOfFirst { it.id == playingId }
                if (i >= 0) list.scrollToItem(maxOf(0, i - 1))
            }
            LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.visibleReviews, key = { it.id }) { r ->
                    var menu by remember { mutableStateOf(false) }
                    val playing = playingId == r.id
                    val shape = RoundedCornerShape(10.dp)
                    Box {
                        Row(
                            Modifier.fillMaxWidth().clip(shape).background(s.surfaceContainerHigh)
                                .border(if (playing) 1.5.dp else 1.dp, if (playing) s.primary else s.outlineVariant, shape)
                                .combinedClickable(onClick = { state.play(r); state.keepOverlay() }, onLongClick = { menu = true })
                                .padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RemoteImage(state.client?.thumbnailUrl(r), Modifier.width(64.dp).height(36.dp).clip(RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(10.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (l in r.labels) LabelIcon(l, size = 18.dp) }
                                Text(Format.time(r.startTime, seconds = true), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                        EventMenu(r, menu, actions) { menu = false }
                    }
                }
            }
        }
    }
}
