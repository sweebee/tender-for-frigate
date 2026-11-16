package nl.wiebenieuwenhuis.tender.ui.components

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.window.core.layout.WindowSizeClass

/**
 * The tablet layout (rail, grids, panels side by side). As in the iOS app a phone is always
 * compact, also in landscape: only a device whose smallest side is 600 dp or more (a tablet,
 * a foldable opened up) gets it, and only while its window is that wide.
 */
@Composable
fun isWideLayout(): Boolean {
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    return tablet && currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
}

/** A phone held sideways: its screens go full screen (live video, playback) as on iOS. */
@Composable
fun isPhoneLandscape(): Boolean {
    val c = LocalConfiguration.current
    return c.smallestScreenWidthDp < 600 && c.screenWidthDp > c.screenHeightDp
}
