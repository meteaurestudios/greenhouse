package org.androidaudioplugin.greenhouse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
private const val KNOB_START_ANGLE_RAD = 2.356194490192345 // 135 deg in radians
private const val KNOB_SWEEP_RAD = 4.71238898038469 // 270 deg in radians
private val DEFAULT_DRAG_TRAVEL_DISTANCE = 200.dp
private const val DOUBLE_TAP_TIMEOUT_MS = 320L
private const val TAP_MAX_DISTANCE_PX = 18f
private const val MIN_RANGE_SPAN = 0.00001
private const val VALUE_EPSILON = 0.000001

@Composable
fun FlatRotaryKnob(
    value: Double,
    minimumValue: Double,
    maximumValue: Double,
    defaultValue: Double,
    step: Double? = null,
    activeColor: Color = SproutGreen,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    onValueChange: (Double) -> Unit
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentValue by rememberUpdatedState(value)
    val currentMin by rememberUpdatedState(minimumValue)
    val currentMax by rememberUpdatedState(maximumValue)
    val currentDefault by rememberUpdatedState(defaultValue)
    val currentStep by rememberUpdatedState(step)

    val rangeSpan = (maximumValue - minimumValue).coerceAtLeast(MIN_RANGE_SPAN)
    val normalizedValue = ((value - minimumValue) / rangeSpan).coerceIn(0.0, 1.0)
    val isBipolar = minimumValue < 0.0 && maximumValue > 0.0

    val density = LocalDensity.current
    val dragSensitivityPx = with(density) { DEFAULT_DRAG_TRAVEL_DISTANCE.toPx() }
    val minStrokeWidthPx = with(density) { 2.5.dp.toPx() }
    val maxStrokeWidthPx = with(density) { 4.5.dp.toPx() }
    val basePaddingPx = with(density) { 1.5.dp.toPx() }
    val discSpacingPx = with(density) { 2.dp.toPx() }
    val minInnerDiscPx = with(density) { 3.dp.toPx() }
    val discBorderWidthPx = with(density) { 1.dp.toPx() }
    val minTickWidthPx = with(density) { 1.8.dp.toPx() }

    Canvas(
        modifier = modifier
            .size(size)
            .pointerInput(Unit) {
                var lastTapTimestamp = 0L

                awaitPointerEventScope {
                    while (true) {
                        val boundsWidth = this.size.width.toFloat()
                        val boundsHeight = this.size.height.toFloat()

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
                        var continuousValue = currentValue
                        var lastEmittedValue = currentValue

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
                                        continuousValue = currentDefault
                                        lastEmittedValue = currentDefault
                                        currentOnValueChange(currentDefault)
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

                            val curMin = currentMin
                            val curMax = currentMax
                            val curSpan = (curMax - curMin).coerceAtLeast(MIN_RANGE_SPAN)

                            val deltaNormalized = dy / dragSensitivityPx
                            val currentNorm = ((continuousValue - curMin) / curSpan).coerceIn(0.0, 1.0)
                            val newNorm = (currentNorm + deltaNormalized).coerceIn(0.0, 1.0)
                            continuousValue = curMin + (newNorm * curSpan)

                            val stepVal = currentStep
                            val steppedValue = if (stepVal != null && stepVal > 0.0) {
                                val stepCount = kotlin.math.round((continuousValue - curMin) / stepVal)
                                (curMin + (stepCount * stepVal)).coerceIn(curMin, curMax)
                            } else {
                                continuousValue
                            }

                            if (abs(steppedValue - lastEmittedValue) > VALUE_EPSILON) {
                                lastEmittedValue = steppedValue
                                currentOnValueChange(steppedValue)
                            }
                        }
                    }
                }
            }
    ) {
        val canvasSize = this.size.minDimension
        val strokeWidthPx = (canvasSize * 0.085f).coerceIn(minStrokeWidthPx, maxStrokeWidthPx)
        val paddingPx = (strokeWidthPx / 2f) + basePaddingPx
        val arcSize = canvasSize - (paddingPx * 2f)
        val arcTopLeft = Offset(paddingPx, paddingPx)
        val center = Offset(canvasSize / 2f, canvasSize / 2f)
        val radius = arcSize / 2f

        val trackStroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)

        // 1. Draw 2D Background Arc Track (270 degrees)
        drawArc(
            color = KnobArcBackground,
            startAngle = KNOB_START_ANGLE,
            sweepAngle = KNOB_MAX_SWEEP_ANGLE,
            useCenter = false,
            topLeft = arcTopLeft,
            size = Size(arcSize, arcSize),
            style = trackStroke
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
                    style = trackStroke
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
                style = trackStroke
            )
        }

        // 3. Draw Center 2D Flat Disc
        val innerDiscRadius = radius - strokeWidthPx - discSpacingPx

        if (innerDiscRadius > minInnerDiscPx) {
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
                style = Stroke(width = discBorderWidthPx)
            )

            // 4. Draw Precision Radial Pointer Tick
            val pointerAngleRad = KNOB_START_ANGLE_RAD + (normalizedValue * KNOB_SWEEP_RAD)

            val tickInnerRadius = innerDiscRadius * 0.35f
            val tickOuterRadius = innerDiscRadius * 0.88f

            val cosVal = cos(pointerAngleRad).toFloat()
            val sinVal = sin(pointerAngleRad).toFloat()

            val startX = center.x + (tickInnerRadius * cosVal)
            val startY = center.y + (tickInnerRadius * sinVal)
            val endX = center.x + (tickOuterRadius * cosVal)
            val endY = center.y + (tickOuterRadius * sinVal)

            drawLine(
                color = activeColor,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = (strokeWidthPx * 0.65f).coerceAtLeast(minTickWidthPx),
                cap = StrokeCap.Round
            )
        }
    }
}
