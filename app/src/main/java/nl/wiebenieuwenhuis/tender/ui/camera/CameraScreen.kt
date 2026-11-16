package nl.wiebenieuwenhuis.tender.ui.camera

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import nl.wiebenieuwenhuis.tender.ui.CameraDebugRoute
import nl.wiebenieuwenhuis.tender.ui.CameraRoute
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold

@Composable fun CameraScreen(route: CameraRoute) { ScreenScaffold(route.camera) { Text(route.camera) } }
@Composable fun CameraDebugScreen(route: CameraDebugRoute) { ScreenScaffold(route.camera) { Text(route.camera) } }
