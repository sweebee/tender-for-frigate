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
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val fingers = event.changes.count { it.pressed }
                    if (fingers >= 2 || scale > 1f) {
                        val (next, moved) = pinchStep(scale, offset, event.calculateCentroid(), event.calculatePan(),
                            event.calculateZoom(), size, maxScale)
                        scale = next; offset = moved
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
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
