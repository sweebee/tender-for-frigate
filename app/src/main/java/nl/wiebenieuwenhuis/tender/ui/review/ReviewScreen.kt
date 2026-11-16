package nl.wiebenieuwenhuis.tender.ui.review

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import nl.wiebenieuwenhuis.tender.ui.*
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold

/** The Review tab: alerts and detections (and Motion), as Frigate's Review page. Stub, ported by the review session. */
@Composable fun ReviewScreen() { ScreenScaffold("Review", showsBack = false) { Text("Review") } }

/** A review item: its clip, objects, GenAI summary and actions. Stub. */
@Composable fun EventDetailScreen(route: EventRoute) { ScreenScaffold("Event") { Text(route.reviewId) } }

/** Private routes of the review feature. */
fun NavGraphBuilder.reviewDestinations() {}
