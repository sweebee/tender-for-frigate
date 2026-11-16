package nl.wiebenieuwenhuis.tender.ui.playback

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import nl.wiebenieuwenhuis.tender.ui.*
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold

/** Recordings of a camera on a day, with events list or timeline. Stub, ported by the playback session. */
@Composable fun PlaybackScreen(route: PlaybackRoute) { ScreenScaffold("Playback") { Text(route.camera) } }

/** Review's Motion view: every camera at one moment with a motion timeline. Shown inside the Review tab. Stub. */
@Composable fun MotionReviewPane(modifier: Modifier = Modifier) { Text("Motion", modifier) }

fun NavGraphBuilder.playbackDestinations() {}
