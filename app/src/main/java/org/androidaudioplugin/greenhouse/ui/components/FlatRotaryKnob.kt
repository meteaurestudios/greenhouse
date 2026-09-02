package org.androidaudioplugin.greenhouse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.androidaudioplugin.greenhouse.ui.theme.KnobArcBackground
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceVariant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val KNOB_START_ANGLE = 135f
private const val KNOB_MAX_SWEEP_ANGLE = 270f
private val DEFAULT_DRAG_TRAVEL_DISTANCE = 200.dp
private const val DOUBLE_TAP_TIMEOUT_MS = 320L
private const val TAP_MAX_DISTANCE_PX = 18f

@Composable
fun FlatRotaryKnob(
    value: Double,
    minimumValue: Double,
    maximumValue: Double,
    defaultValue: Double,
    activeColor: Color = SproutGreen,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    onValueChange: (Double) -> Unit
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentValue by rememberUpdatedState(value)

    val rangeSpan = (maximumValue - minimumValue).coerceAtLeast(0.00001)
    val normalizedValue = ((value - minimumValue) / rangeSpan).coerceIn(0.0, 1.0)
    val isBipolar = minimumValue < 0.0 && maximumValue > 0.0

    val density = LocalDensity.current
    val dragSensitivityPx = with(density) { DEFAULT_DRAG_TRAVEL_DISTANCE.toPx() }

    Box(
        modifier = modifier
            .size(size)
            .pointerInput(minimumValue, maximumValue, defaultValue, dragSensitivityPx) {
                var lastTapTimestamp = 0L

                awaitPointerEventScope {
                    val boundsWidth = this.size.width.toFloat()
                    val boundsHeight = this.size.height.toFloat()

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val down = event.changes.firstOrNull { change ->
                            change.changedToDown() &&
                            change.position.x in 0f..boundsWidth &&
                            change.position.y in 0f..boundsHeight
                        }

                        if (down == null) {
                            continue
                        }

                        down.consume()

                        val pointerId = down.id
                        val downTime = System.currentTimeMillis()
                        val downPos = down.position
                        var lastY = down.position.y
                        var hasDragged = false
                        var runningValue = currentValue

                        while (true) {
                            val dragEvent = awaitPointerEvent(PointerEventPass.Main)
                            val change = dragEvent.changes.firstOrNull { it.id == pointerId }

                            if (change == null) {
                                break
                            }

                            if (change.changedToUp() || !change.pressed) {
                                change.consume()
                                val upTime = System.currentTimeMillis()
                                val dist = (change.position - downPos).getDistance()

                                if (!hasDragged && (upTime - downTime) < DOUBLE_TAP_TIMEOUT_MS && dist < TAP_MAX_DISTANCE_PX) {
                                    if ((upTime - lastTapTimestamp) < DOUBLE_TAP_TIMEOUT_MS) {
                                        currentOnValueChange(defaultValue)
                                        lastTapTimestamp = 0L
                                    } else {
                                        lastTapTimestamp = upTime
                                    }
                                }

                                break
                            }

                            val currentY = change.position.y
                            val dy = lastY - currentY
                            lastY = currentY

                            if (abs(dy) > 0.01f) {
                                hasDragged = true
                            }

                            change.consume()

                            val deltaNormalized = dy / dragSensitivityPx
                            val currentNorm = ((runningValue - minimumValue) / rangeSpan).coerceIn(0.0, 1.0)
                            val newNorm = (currentNorm + deltaNormalized).coerceIn(0.0, 1.0)
                            val newValue = minimumValue + (newNorm * rangeSpan)

                            if (newValue != runningValue) {
                                runningValue = newValue
                                currentOnValueChange(newValue)
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val canvasSize = this.size.minDimension
            val strokeWidthPx = (canvasSize * 0.085f).coerceIn(2.5.dp.toPx(), 4.5.dp.toPx())
            val paddingPx = strokeWidthPx / 2f + 1.5.dp.toPx()
            val arcSize = canvasSize - (paddingPx * 2f)
            val arcTopLeft = Offset(paddingPx, paddingPx)
            val center = Offset(canvasSize / 2f, canvasSize / 2f)
            val radius = arcSize / 2f

            // 1. Draw 2D Background Arc Track (270 degrees)
            drawArc(
                color = KnobArcBackground,
                startAngle = KNOB_START_ANGLE,
                sweepAngle = KNOB_MAX_SWEEP_ANGLE,
                useCenter = false,
                topLeft = arcTopLeft,
                size = Size(arcSize, arcSize),
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )

            // 2. Draw Active Value Arc (Bipolar or Unipolar)
            if (isBipolar) {
                val zeroNorm = ((0.0 - minimumValue) / rangeSpan).coerceIn(0.0, 1.0)
                val activeNorm = normalizedValue.toFloat()
                val zeroNormFloat = zeroNorm.toFloat()

                val startAngle = KNOB_START_ANGLE + (zeroNormFloat * KNOB_MAX_SWEEP_ANGLE)
                val sweepAngle = (activeNorm - zeroNormFloat) * KNOB_MAX_SWEEP_ANGLE

                if (abs(sweepAngle) > 0.5f) {
                    drawArc(
                        color = activeColor,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = Size(arcSize, arcSize),
                        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                    )
                }
            } else {
                val sweepAngle = (normalizedValue.toFloat() * KNOB_MAX_SWEEP_ANGLE).coerceAtLeast(1.0f)

                drawArc(
                    color = activeColor,
                    startAngle = KNOB_START_ANGLE,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = Size(arcSize, arcSize),
                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                )
            }

            // 3. Draw Center 2D Flat Disc
            val innerDiscRadius = radius - strokeWidthPx - 2.dp.toPx()

            if (innerDiscRadius > 3.dp.toPx()) {
                // Disc Fill
                drawCircle(
                    color = StudioSurface,
                    radius = innerDiscRadius,
                    center = center
                )

                // Disc Hairline Outline
                drawCircle(
                    color = StudioSurfaceVariant,
                    radius = innerDiscRadius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )

                // 4. Draw Precision Radial Pointer Tick
                val pointerAngleDeg = KNOB_START_ANGLE + (normalizedValue.toFloat() * KNOB_MAX_SWEEP_ANGLE)
                val pointerAngleRad = Math.toRadians(pointerAngleDeg.toDouble())

                val tickInnerRadius = innerDiscRadius * 0.35f
                val tickOuterRadius = innerDiscRadius * 0.88f

                val startX = center.x + (tickInnerRadius * cos(pointerAngleRad)).toFloat()
                val startY = center.y + (tickInnerRadius * sin(pointerAngleRad)).toFloat()
                val endX = center.x + (tickOuterRadius * cos(pointerAngleRad)).toFloat()
                val endY = center.y + (tickOuterRadius * sin(pointerAngleRad)).toFloat()

                drawLine(
                    color = activeColor,
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = (strokeWidthPx * 0.65f).coerceAtLeast(1.8.dp.toPx()),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
