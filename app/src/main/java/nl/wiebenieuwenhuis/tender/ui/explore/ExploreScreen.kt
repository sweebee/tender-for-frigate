package nl.wiebenieuwenhuis.tender.ui.explore

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import nl.wiebenieuwenhuis.tender.ui.*
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold

/** The Explore tab: tracked objects, search, plates. Stub, ported by the explore session. */
@Composable fun ExploreScreen() { ScreenScaffold("Explore", showsBack = false) { Text("Explore") } }

/** A tracked object; opens its review item when it has one. Stub. */
@Composable fun ObjectScreen(route: ObjectRoute) { ScreenScaffold("Object") { Text(route.eventId) } }

fun NavGraphBuilder.exploreDestinations() {}
