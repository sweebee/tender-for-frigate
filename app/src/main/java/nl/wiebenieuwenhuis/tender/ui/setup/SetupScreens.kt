package nl.wiebenieuwenhuis.tender.ui.setup

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import nl.wiebenieuwenhuis.tender.ui.*
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold

/** Stubs, ported by the setup session. */
@Composable fun ConfigEditorScreen(route: ConfigEditorRoute) { ScreenScaffold("Config") { Text(route.serverId) } }
@Composable fun CameraStreamsScreen(route: CameraStreamsRoute) { ScreenScaffold("Streams") { Text(route.serverId) } }
@Composable fun CameraWizardScreen(route: CameraWizardRoute) { ScreenScaffold("Add camera") { Text(route.serverId) } }
@Composable fun ZoneEditorScreen(route: ZoneEditorRoute) { ScreenScaffold("Zones") { Text(route.camera) } }
@Composable fun WebInterfaceScreen(route: WebInterfaceRoute) { ScreenScaffold("Frigate") { Text(route.serverId) } }

fun NavGraphBuilder.setupDestinations() {}
