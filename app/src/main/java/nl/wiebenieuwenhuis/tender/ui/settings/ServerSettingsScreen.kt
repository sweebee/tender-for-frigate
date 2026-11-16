package nl.wiebenieuwenhuis.tender.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import nl.wiebenieuwenhuis.tender.ui.*
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold

/** A server's Frigate settings (admin). Stub, ported by the settings session. */
@Composable fun ServerSettingsScreen(route: ServerSettingsRoute) { ScreenScaffold("Server settings") { Text(route.serverId) } }

fun NavGraphBuilder.settingsDestinations() {}
