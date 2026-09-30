package org.androidaudioplugin.greenhouse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.androidaudioplugin.greenhouse.ui.theme.KnobArcBackground
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen

private val FADER_TRACK_HEIGHT = 3.dp
private val FADER_THUMB_RADIUS = 5.dp
private val FADER_THUMB_BORDER_WIDTH = 1.5.dp
private val FADER_DEFAULT_DOT_RADIUS = 1.5.dp
// Faint green: a discreet mark on the empty track
private const val FADER_DEFAULT_DOT_ALPHA = 0.35f
private const val FADER_MIN_TRAVEL_PX = 1f

/**
 * Small horizontal fader for host-side controls, drawn like [FlatRotaryKnob] laid flat. It works in
 * fader positions (0..1), so the caller picks the taper. Dragging moves the position relative to
 * where it was; travel past either end is kept, so the thumb only moves again once the finger comes
 * back to where it left the track. It keeps every drag started on it, vertical ones included.
 * Double-tap goes back to [defaultPosition], marked by a dot on the track.
 */
@Composable
fun HostFader(
    position: Float,
    defaultPosition: Float,
    modifier: Modifier = Modifier,
    onPositionChange: (Float) -> Unit
) {
    val currentOnPositionChange by rememberUpdatedState(onPositionChange)
    val currentPosition by rememberUpdatedState(position)
    val currentDefault by rememberUpdatedState(defaultPosition)

    val normalizedValue = position.coerceIn(0f, 1f)
    val normalizedDefault = defaultPosition.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        currentOnPositionChange(currentDefault)
                    }
                )
            }
            .pointerInput(Unit) {
                // Unclamped, so overshoot past 0 or 1 has to be dragged back before the value moves
                var dragPosition = currentPosition

                // A drag started on the fader is its own in any direction, only its horizontal part
                // moving it: a vertical wobble must not scroll the page or close a bottom sheet under it
                detectDragGestures(
                    onDragStart = {
                        dragPosition = currentPosition
                    }
                ) { change, dragAmount ->
                    change.consume()

                    val travel = (size.width - FADER_THUMB_RADIUS.toPx() * 2f).coerceAtLeast(FADER_MIN_TRAVEL_PX)
                    dragPosition += dragAmount.x / travel
                    val newPosition = dragPosition.coerceIn(0f, 1f)

                    if (newPosition != currentPosition) {
                        currentOnPositionChange(newPosition)
                    }
                }
            }
    ) {
        val thumbRadius = FADER_THUMB_RADIUS.toPx()
        val trackHeight = FADER_TRACK_HEIGHT.toPx()
        val trackStart = thumbRadius
        val trackWidth = (size.width - thumbRadius * 2f).coerceAtLeast(0f)
        val centerY = size.height / 2f
        val trackTop = centerY - trackHeight / 2f
        val cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        val thumbX = trackStart + trackWidth * normalizedValue
        val defaultX = trackStart + trackWidth * normalizedDefault

        // The track stops at the ring of the hollow thumb: filled up to it, empty after it
        val fillEnd = thumbX - thumbRadius
        val emptyStart = thumbX + thumbRadius
        val trackEnd = trackStart + trackWidth

        if (fillEnd > trackStart) {
            drawRoundRect(
                color = SproutGreen,
                topLeft = Offset(trackStart, trackTop),
                size = Size(fillEnd - trackStart, trackHeight),
                cornerRadius = cornerRadius
            )
        }

        if (trackEnd > emptyStart) {
            drawRoundRect(
                color = KnobArcBackground,
                topLeft = Offset(emptyStart, trackTop),
                size = Size(trackEnd - emptyStart, trackHeight),
                cornerRadius = cornerRadius
            )
        }

        // Default dot, kept off the fill. The thumb is a hollow ring, so the dot shows through it
        // while sliding over it.
        if (defaultX - FADER_DEFAULT_DOT_RADIUS.toPx() >= fillEnd) {
            drawCircle(
                color = SproutGreen.copy(alpha = FADER_DEFAULT_DOT_ALPHA),
                radius = FADER_DEFAULT_DOT_RADIUS.toPx(),
                center = Offset(defaultX, centerY)
            )
        }

        drawCircle(
            color = SproutGreen,
            radius = thumbRadius - FADER_THUMB_BORDER_WIDTH.toPx() / 2f,
            center = Offset(thumbX, centerY),
            style = Stroke(width = FADER_THUMB_BORDER_WIDTH.toPx())
        )
    }
}
