package nl.wiebenieuwenhuis.tender.ui.servers

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import nl.wiebenieuwenhuis.tender.ui.ConnectRoute
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold

@Composable fun ServersScreen(showsBack: Boolean = true) { ScreenScaffold("Settings", showsBack = showsBack) { Text("Settings") } }
@Composable fun ConnectScreen(route: ConnectRoute, onboarding: Boolean = false) { ScreenScaffold("Add server", showsBack = !onboarding) { Text("Connect") } }
@Composable fun LiveSettingsScreen() { ScreenScaffold("Live") { Text("Live") } }
