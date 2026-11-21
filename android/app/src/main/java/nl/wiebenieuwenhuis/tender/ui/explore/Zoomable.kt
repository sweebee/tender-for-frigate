package nl.wiebenieuwenhuis.tender.ui.explore

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize

/**
 * One step of a pinch, as in a photo viewer: zooms by `zoom` around the fingers' `centroid` (in the
 * box's pixels) and moves along with them by `pan`, so what was between the fingers stays there.
 * Keeps the content covering the box. For content drawn with `graphicsLayer` scale and translation
 * around its centre.
 */
fun pinchStep(scale: Float, offset: Offset, centroid: Offset, pan: Offset, zoom: Float, size: IntSize, maxScale: Float): Pair<Float, Offset> {
    val next = (scale * zoom).coerceIn(1f, maxScale)
    if (next <= 1f) return 1f to Offset.Zero
    val c = centroid - Offset(size.width / 2f, size.height / 2f)
    val o = c - (c - pan - offset) * (next / scale)
    val maxX = size.width * (next - 1) / 2
    val maxY = size.height * (next - 1) / 2
    return next to Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}

/**
 * Pinch and pan, calling `onStep` with the fingers' centroid, pan and zoom for each move. Two
 * fingers always; one finger only while `zoomed()`. An event in which a finger goes down or lifts
 * is skipped: the centroid leaps there (to the finger that stays), and following it would throw
 * the picture aside when one finger is lifted while zooming and panning at once.
 */
suspend fun PointerInputScope.detectPinch(zoomed: () -> Boolean, onStep: (centroid: Offset, pan: Offset, zoom: Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val fingers = event.changes.count { it.pressed }
            val settled = event.changes.none { it.pressed != it.previousPressed }
            if (settled && (fingers >= 2 || (fingers == 1 && zoomed()))) {
                onStep(event.calculateCentroid(), event.calculatePan(), event.calculateZoom())
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Pinch to zoom a picture, drag to move it while zoomed, double-tap to zoom in or back out.
 * A single finger on an unzoomed picture is left alone, so the screen around it still scrolls.
 * `onTap`: a single tap (a video's controls).
 */
fun Modifier.zoomable(maxScale: Float = 5f, onTap: (() -> Unit)? = null): Modifier = composed {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    this
        .clipToBounds()
        .pointerInput(Unit) {
            detectPinch({ scale > 1f }) { centroid, pan, zoom ->
                pinchStep(scale, offset, centroid, pan, zoom, size, maxScale).let { (s, o) -> scale = s; offset = o }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onTap = onTap?.let { f -> { _ -> f() } }, onDoubleTap = { at ->
                // In on the tapped spot, or back out.
                if (scale > 1f) { scale = 1f; offset = Offset.Zero }
                else pinchStep(1f, Offset.Zero, at, Offset.Zero, 2.5f, size, maxScale).let { (s, o) -> scale = s; offset = o }
            })
        }
        .graphicsLayer {
            scaleX = scale; scaleY = scale
            translationX = offset.x; translationY = offset.y
        }
}
