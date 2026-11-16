package nl.wiebenieuwenhuis.tender.ui.classification

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import nl.wiebenieuwenhuis.tender.ui.*
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold

/** Frigate's custom classification models. Stub, ported by the faces session. */
@Composable fun ClassificationScreen(showsBack: Boolean = true) { ScreenScaffold("Classification", showsBack = showsBack) { Text("Classification") } }

@Composable fun ClassificationModelScreen(route: ClassificationModelRoute) { ScreenScaffold(route.model) { Text(route.model) } }

fun NavGraphBuilder.classificationDestinations() {}
