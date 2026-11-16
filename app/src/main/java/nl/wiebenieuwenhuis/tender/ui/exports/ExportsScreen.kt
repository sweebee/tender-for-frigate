package nl.wiebenieuwenhuis.tender.ui.exports

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import nl.wiebenieuwenhuis.tender.ui.*
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold

/** Frigate's Exports and cases. Stub, ported by the explore session. */
@Composable fun ExportsScreen(route: ExportsRoute, showsBack: Boolean = true) { ScreenScaffold("Exports", showsBack = showsBack) { Text("Exports") } }

/** One export played. Stub. */
@Composable fun ExportPlayerScreen(route: ExportPlayerRoute) { ScreenScaffold("Export") { Text(route.exportId) } }

fun NavGraphBuilder.exportsDestinations() {}
