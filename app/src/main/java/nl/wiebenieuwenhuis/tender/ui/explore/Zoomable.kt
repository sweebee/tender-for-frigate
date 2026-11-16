package nl.wiebenieuwenhuis.tender.ui.explore

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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

/**
 * Pinch to zoom a picture, drag to move it while zoomed, double-tap to zoom in or back out.
 * A single finger on an unzoomed picture is left alone, so the screen around it still scrolls.
 */
fun Modifier.zoomable(maxScale: Float = 5f): Modifier = composed {
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
                        val next = (scale * event.calculateZoom()).coerceIn(1f, maxScale)
                        val pan = event.calculatePan()
                        val maxX = size.width * (next - 1) / 2
                        val maxY = size.height * (next - 1) / 2
                        offset = if (next <= 1f) Offset.Zero
                        else Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                        scale = next
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onDoubleTap = {
                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
            })
        }
        .graphicsLayer {
            scaleX = scale; scaleY = scale
            translationX = offset.x; translationY = offset.y
        }
}
