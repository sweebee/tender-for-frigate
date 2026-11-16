package nl.wiebenieuwenhuis.tender.ui

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowSizeClass
import nl.wiebenieuwenhuis.tender.MainActivity
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.camera.CameraDebugScreen
import nl.wiebenieuwenhuis.tender.ui.camera.CameraScreen
import nl.wiebenieuwenhuis.tender.ui.chat.ChatScreen
import nl.wiebenieuwenhuis.tender.ui.classification.ClassificationModelScreen
import nl.wiebenieuwenhuis.tender.ui.classification.ClassificationScreen
import nl.wiebenieuwenhuis.tender.ui.explore.ExploreScreen
import nl.wiebenieuwenhuis.tender.ui.explore.ObjectScreen
import nl.wiebenieuwenhuis.tender.ui.explore.exploreDestinations
import nl.wiebenieuwenhuis.tender.ui.exports.ExportPlayerScreen
import nl.wiebenieuwenhuis.tender.ui.exports.ExportsScreen
import nl.wiebenieuwenhuis.tender.ui.exports.exportsDestinations
import nl.wiebenieuwenhuis.tender.ui.faces.FaceLibraryScreen
import nl.wiebenieuwenhuis.tender.ui.faces.facesDestinations
import nl.wiebenieuwenhuis.tender.ui.live.LiveScreen
import nl.wiebenieuwenhuis.tender.ui.more.MoreScreen
import nl.wiebenieuwenhuis.tender.ui.playback.PlaybackScreen
import nl.wiebenieuwenhuis.tender.ui.playback.playbackDestinations
import nl.wiebenieuwenhuis.tender.ui.review.EventDetailScreen
import nl.wiebenieuwenhuis.tender.ui.review.ReviewScreen
import nl.wiebenieuwenhuis.tender.ui.review.reviewDestinations
import nl.wiebenieuwenhuis.tender.ui.servers.ConnectScreen
import nl.wiebenieuwenhuis.tender.ui.servers.LiveSettingsScreen
import nl.wiebenieuwenhuis.tender.ui.servers.ServersScreen
import nl.wiebenieuwenhuis.tender.ui.settings.ServerSettingsScreen
import nl.wiebenieuwenhuis.tender.ui.settings.settingsDestinations
import nl.wiebenieuwenhuis.tender.ui.setup.CameraStreamsScreen
import nl.wiebenieuwenhuis.tender.ui.setup.CameraWizardScreen
import nl.wiebenieuwenhuis.tender.ui.setup.ConfigEditorScreen
import nl.wiebenieuwenhuis.tender.ui.setup.WebInterfaceScreen
import nl.wiebenieuwenhuis.tender.ui.setup.ZoneEditorScreen
import nl.wiebenieuwenhuis.tender.ui.setup.setupDestinations

/** Hides the tab bar or rail while a video fills the screen. */
object AppChrome {
    var immersive by mutableStateOf(false)
}

private data class Section(val route: Any, val title: Int, val icon: ImageVector, val badge: () -> Int = { 0 })

/** The phone's tabs; a wide window shows every section in the rail instead of "More". */
private fun sections(wide: Boolean): List<Section> = buildList {
    add(Section(LiveTab, R.string.tab_live, Icons.Outlined.GridView))
    add(Section(ReviewTab, R.string.tab_review, Icons.Outlined.Visibility) { AppModel.unreviewedAlerts })
    add(Section(ExploreTab, R.string.tab_explore, Icons.Outlined.Search))
    if (wide) {
        add(Section(ExportsTab, R.string.exports, Icons.Outlined.MovieFilter))
        if (AppModel.classificationConnections.isNotEmpty()) add(Section(ClassificationTab, R.string.classification, Icons.Outlined.Category))
        if (AppModel.faceConnections.isNotEmpty()) add(Section(FacesTab, R.string.face_library, Icons.Outlined.Face))
        if (AppModel.chatConnections.isNotEmpty()) add(Section(ChatTab, R.string.chat, Icons.AutoMirrored.Outlined.Chat))
        add(Section(SettingsTab, R.string.settings, Icons.Outlined.Settings))
    } else {
        add(Section(MoreTab, R.string.tab_more, Icons.Outlined.MoreHoriz))
    }
}

@Composable
fun AppRoot(activity: MainActivity) {
    LaunchedEffect(Unit) { AppModel.connectAll() }
    if (AppModel.servers.isEmpty()) {
        val nav = rememberNavController()
        CompositionLocalProvider(LocalNav provides nav) {
            NavHost(nav, startDestination = ConnectRoute()) {
                composable<ConnectRoute> { ConnectScreen(it.toRoute(), onboarding = true) }
            }
        }
        return
    }
    val nav = rememberNavController()
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val wide = nl.wiebenieuwenhuis.tender.ui.components.isWideLayout()
    val entry by nav.currentBackStackEntryAsState()
    val list = sections(wide)
    // The section the user is in: the last top-level section shown. Screens pushed inside it
    // (a camera, an event, Settings from More) keep it highlighted, as the iOS tab bar does.
    val stack by nav.currentBackStack.collectAsState()
    var section by remember { mutableStateOf<Any>(LiveTab) }
    LaunchedEffect(stack, list) {
        // The nearest section down the stack: a pushed screen (also Settings from More) keeps it.
        stack.asReversed().firstNotNullOfOrNull { e -> list.firstOrNull { e.destination.hasRoute(it.route::class) } }?.let { section = it.route }
    }
    // As iOS hides its tab bar on these: video and detail screens get the whole screen.
    val hideBar = entry?.destination?.let { d ->
        listOf(CameraRoute::class, CameraDebugRoute::class, PlaybackRoute::class, EventRoute::class,
            ObjectRoute::class, ExportPlayerRoute::class).any { d.hasRoute(it) }
    } == true

    LaunchedEffect(activity.pendingUri) {
        val uri = activity.pendingUri ?: return@LaunchedEffect
        activity.pendingUri = null
        DeepLinks.handle(uri, nav)
    }

    CompositionLocalProvider(LocalNav provides nav) {
        // A wide window gets an icon-only rail as the iOS app on iPad (names as tooltips), with
        // Settings at the bottom; a phone gets the tab bar. The NavHost stays in one place so
        // going full screen does not rebuild the screen.
        Row(Modifier.fillMaxSize()) {
        if (wide && !AppChrome.immersive) SectionRail(list, nav, section)
        NavigationSuiteScaffold(
            modifier = Modifier.weight(1f),
            layoutType = if (wide || AppChrome.immersive || hideBar) NavigationSuiteType.None else NavigationSuiteType.NavigationBar,
            navigationSuiteItems = {
                for (s in list) {
                    val selected = section == s.route
                    item(
                        selected = selected,
                        onClick = { selectTab(nav, s.route, selected) },
                        icon = { SectionIcon(s) },
                        label = { Text(stringResource(s.title), maxLines = 1, softWrap = false) },
                    )
                }
            },
        ) {
            NavHost(
                nav, startDestination = LiveTab,
                enterTransition = { fadeIn() }, exitTransition = { fadeOut() },
            ) {
                composable<LiveTab> { LiveScreen() }
                composable<ReviewTab> { ReviewScreen() }
                composable<ExploreTab> { ExploreScreen() }
                composable<MoreTab> { MoreScreen() }
                composable<ExportsTab> { ExportsScreen(ExportsRoute(), showsBack = !wide) }
                composable<FacesTab> { FaceLibraryScreen(showsBack = !wide) }
                composable<ClassificationTab> { ClassificationScreen(showsBack = !wide) }
                composable<ChatTab> { ChatScreen(showsBack = !wide) }
                composable<SettingsTab> { ServersScreen(showsBack = !wide) }

                composable<CameraRoute> { CameraScreen(it.toRoute()) }
                composable<CameraDebugRoute> { CameraDebugScreen(it.toRoute()) }
                composable<EventRoute> { EventDetailScreen(it.toRoute()) }
                composable<ObjectRoute> { ObjectScreen(it.toRoute()) }
                composable<PlaybackRoute> { PlaybackScreen(it.toRoute()) }
                composable<ExportsRoute> { ExportsScreen(it.toRoute()) }
                composable<ExportPlayerRoute> { ExportPlayerScreen(it.toRoute()) }
                composable<ClassificationModelRoute> { ClassificationModelScreen(it.toRoute()) }
                composable<ConnectRoute> { ConnectScreen(it.toRoute()) }
                composable<LiveSettingsRoute> { LiveSettingsScreen() }
                composable<ServerSettingsRoute> { ServerSettingsScreen(it.toRoute()) }
                composable<ConfigEditorRoute> { ConfigEditorScreen(it.toRoute()) }
                composable<CameraStreamsRoute> { CameraStreamsScreen(it.toRoute()) }
                composable<CameraWizardRoute> { CameraWizardScreen(it.toRoute()) }
                composable<ZoneEditorRoute> { ZoneEditorScreen(it.toRoute()) }
                composable<WebInterfaceRoute> { WebInterfaceScreen(it.toRoute()) }

                reviewDestinations()
                playbackDestinations()
                exploreDestinations()
                exportsDestinations()
                facesDestinations()
                settingsDestinations()
                setupDestinations()
            }
        }
        }
    }
}

/** Opens a section; tapping the open one again goes back to its top. */
fun selectTab(nav: NavHostController, route: Any, alreadySelected: Boolean = false) {
    if (alreadySelected) {
        // Back to the section's start; when it is not on the stack (opened by a link), open it.
        if (!nav.popBackStack(route, inclusive = false)) nav.navigate(route) { launchSingleTop = true }
        return
    }
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun SectionIcon(s: Section) {
    val n = s.badge()
    if (n > 0) BadgedBox(badge = { Badge { Text(if (n > 99) "99+" else n.toString()) } }) { Icon(s.icon, stringResource(s.title)) }
    else Icon(s.icon, stringResource(s.title))
}

/** The wide layout's rail: icons only, the name as a tooltip, Settings at the bottom. */
@Composable
private fun SectionRail(list: List<Section>, nav: NavHostController, section: Any) {
    NavigationRail(Modifier.fillMaxHeight()) {
        Spacer(Modifier.height(8.dp))
        for (s in list) {
            if (s.route == SettingsTab) Spacer(Modifier.weight(1f))
            val selected = section == s.route
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Right),
                tooltip = { PlainTooltip { Text(stringResource(s.title)) } },
                state = rememberTooltipState(),
            ) {
                NavigationRailItem(selected = selected, onClick = { selectTab(nav, s.route, selected) }, icon = { SectionIcon(s) },
                    label = null, alwaysShowLabel = false, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
