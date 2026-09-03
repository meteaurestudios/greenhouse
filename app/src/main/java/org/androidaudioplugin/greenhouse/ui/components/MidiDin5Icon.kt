package org.androidaudioplugin.greenhouse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen

/**
 * Modern, crisp 5-pin DIN (DIN-5) MIDI connector icon.
 * Features an outer metallic chassis ring, guide arc, 5 precision pins in 180° DIN arc, and alignment notch.
 */
@Composable
fun MidiDin5Icon(
    tint: Color = SproutGreen,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val sizePx = size.minDimension
        val s = sizePx / 24.0f
        val cx = size.width / 2.0f
        val cy = size.height / 2.0f

        // Outer chassis ring
        val outerRadius = 10.0f * s
        val outerStrokeWidth = (1.6f * s).coerceAtLeast(1.0f)

        drawCircle(
            color = tint,
            radius = outerRadius,
            center = Offset(cx, cy),
            style = Stroke(width = outerStrokeWidth)
        )

        // Inner plug bezel ring
        val innerRadius = 7.8f * s
        val innerStrokeWidth = (0.9f * s).coerceAtLeast(0.7f)

        drawCircle(
            color = tint.copy(alpha = 0.35f),
            radius = innerRadius,
            center = Offset(cx, cy),
            style = Stroke(width = innerStrokeWidth)
        )

        // 5 Pins in standard 180° DIN-5 arc (relative to 24x24 grid centered at (12, 12))
        val pinRadius = (1.15f * s).coerceAtLeast(0.8f)

        val pinCenters = arrayOf(
            Offset(cx - (5.8f * s), cy - (0.5f * s)),  // Pin 1: ~9 o'clock (lower left)
            Offset(cx - (4.0f * s), cy - (4.4f * s)),  // Pin 2: ~10:30 o'clock (upper left)
            Offset(cx, cy - (5.8f * s)),                // Pin 3: ~12 o'clock (top center)
            Offset(cx + (4.0f * s), cy - (4.4f * s)),  // Pin 4: ~1:30 o'clock (upper right)
            Offset(cx + (5.8f * s), cy - (0.5f * s))   // Pin 5: ~3 o'clock (lower right)
        )

        for (pin in pinCenters) {
            drawCircle(
                color = tint,
                radius = pinRadius,
                center = pin
            )
        }

        // Bottom orientation notch (subtle rounded keyway at bottom center)
        val notchW = 3.2f * s
        val notchH = 2.6f * s
        val notchCorner = 0.8f * s

        drawRoundRect(
            color = tint,
            topLeft = Offset(cx - (notchW / 2.0f), cy + (5.8f * s)),
            size = Size(notchW, notchH),
            cornerRadius = CornerRadius(notchCorner, notchCorner)
        )
    }
}
