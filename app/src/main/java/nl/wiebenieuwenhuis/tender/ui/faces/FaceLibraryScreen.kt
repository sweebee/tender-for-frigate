package nl.wiebenieuwenhuis.tender.ui.faces

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import nl.wiebenieuwenhuis.tender.ui.*
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold

/** Frigate's Face Library. Stub, ported by the faces session. */
@Composable fun FaceLibraryScreen(showsBack: Boolean = true) { ScreenScaffold("Face Library", showsBack = showsBack) { Text("Faces") } }

fun NavGraphBuilder.facesDestinations() {}
