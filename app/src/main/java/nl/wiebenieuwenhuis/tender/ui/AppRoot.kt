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
import androidx.compose.runtime.getValue
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
import nl.wiebenieuwenhuis.tender.ui.classification.classificationDestinations
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
    val wide = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val entry by nav.currentBackStackEntryAsState()
    val list = sections(wide)

    LaunchedEffect(activity.pendingUri) {
        val uri = activity.pendingUri ?: return@LaunchedEffect
        activity.pendingUri = null
        DeepLinks.handle(uri, nav)
    }

    CompositionLocalProvider(LocalNav provides nav) {
        NavigationSuiteScaffold(
            layoutType = when {
                AppChrome.immersive -> NavigationSuiteType.None
                wide -> NavigationSuiteType.NavigationRail
                else -> NavigationSuiteType.NavigationBar
            },
            navigationSuiteItems = {
                for (s in list) {
                    val selected = entry?.destination?.hierarchy?.any { it.hasRoute(s.route::class) } == true
                    item(
                        selected = selected,
                        onClick = { selectTab(nav, s.route, selected) },
                        icon = {
                            val n = s.badge()
                            if (n > 0) BadgedBox(badge = { Badge { Text(if (n > 99) "99+" else n.toString()) } }) { Icon(s.icon, null) }
                            else Icon(s.icon, null)
                        },
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
                composable<ExportsTab> { ExportsScreen(ExportsRoute(), showsBack = false) }
                composable<FacesTab> { FaceLibraryScreen(showsBack = false) }
                composable<ClassificationTab> { ClassificationScreen(showsBack = false) }
                composable<ChatTab> { ChatScreen(showsBack = false) }
                composable<SettingsTab> { ServersScreen(showsBack = false) }

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
                classificationDestinations()
                settingsDestinations()
                setupDestinations()
            }
        }
    }
}

/** Opens a section; tapping the open one again goes back to its top. */
fun selectTab(nav: NavHostController, route: Any, alreadySelected: Boolean = false) {
    if (alreadySelected) {
        nav.popBackStack(route, inclusive = false)
        return
    }
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
