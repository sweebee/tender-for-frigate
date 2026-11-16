package nl.wiebenieuwenhuis.tender.ui.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The media as large as the window allows, with the details around it (iOS `WideDetailLayout`).
 * The media stays put; only the details scroll.
 * - Upright windows: media across the width (at most 55% of the height), the details under it.
 *   With `edgeToEdge` (phones) the media runs to the screen's edges, without rounded corners.
 * - Wide windows: media on the left, its height capped to the window, the details beside it;
 *   when the media leaves at least 200 dp under it, `extra` fills that room instead of the side.
 *
 * `media` gets a modifier with its exact size. `details`, `extra` and `actions` bring their own
 * 16 dp side margins; `extra` is told when it sits under the media, where it lines up with the
 * media's edges instead.
 */
@Composable
fun WideDetailLayout(
    aspect: Float,
    modifier: Modifier = Modifier,
    edgeToEdge: Boolean = false,
    /** Height under the picture that `media` also uses, e.g. a scrubber. */
    reserve: Dp = 0.dp,
    media: @Composable (Modifier) -> Unit,
    details: @Composable ColumnScope.() -> Unit,
    extra: @Composable ColumnScope.(underMedia: Boolean) -> Unit = {},
    actions: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        if (w < h * 1.1f) {
            val width = if (edgeToEdge) w else w - 32.dp
            val height = minOf(width / aspect, h * 0.55f)
            Column(Modifier.fillMaxSize().padding(bottom = 8.dp)) {
                Box(Modifier.fillMaxWidth().padding(top = if (edgeToEdge) 0.dp else 4.dp), contentAlignment = Alignment.TopCenter) {
                    media(
                        Modifier.size(height * aspect, height + reserve)
                            .then(if (edgeToEdge) Modifier else Modifier.clip(shape)),
                    )
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    details()
                    extra(false)
                    Spacer(Modifier.height(16.dp))
                }
                actions()
            }
        } else {
            val sideWidth = minOf(400.dp, w * 0.36f)
            val mediaWidth = maxOf(200.dp, w - sideWidth - 32.dp)
            val height = minOf(mediaWidth / aspect, maxOf(160.dp, h - 28.dp - reserve))
            // Room left under the media for `extra`.
            val extraBelow = h - 28.dp - reserve - height >= 200.dp
            Row(
                Modifier.fillMaxSize().padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.width(mediaWidth).fillMaxHeight()) {
                    media(Modifier.size(height * aspect, height + reserve).clip(shape))
                    if (extraBelow) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                            extra(true)
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
                Column(Modifier.width(sideWidth).fillMaxHeight()) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        details()
                        if (!extraBelow) extra(false)
                        Spacer(Modifier.height(16.dp))
                    }
                    actions()
                }
            }
        }
    }
}
