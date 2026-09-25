package org.androidaudioplugin.greenhouse.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.androidaudioplugin.greenhouse.ui.theme.*

private const val EYE_GLINT_FRACTION = 0.35f
private const val BLINK_CYCLE_DURATION_MS = 7200
private const val IDLE_BOB_DURATION_MS = 1400
private const val LISTENING_BOB_DURATION_MS = 620
private const val LEAF_FLUTTER_DURATION_MS = 1800
private const val BLUSH_PULSE_DURATION_MS = 900
private const val MAX_LEAF_SWAY_DEG = 4.0f
private const val HAPPY_EYE_HOLD_DURATION_MS = 500L
private val SIMPLIFIED_SIZE_THRESHOLD_DP = 38.dp

@Composable
fun GreenhouseMascot(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    simplified: Boolean = size <= SIMPLIFIED_SIZE_THRESHOLD_DP,
    isAnimated: Boolean = true,
    isListening: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var isHappyEyes by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "MascotTransition")

    // 1. Rhythmic Groove / Breathing Bob (Strictly vertical, no tilting)
    val bobTarget = if (isListening) {
        3.5f
    } else {
        1.6f
    }

    val bobDuration = if (isListening) {
        LISTENING_BOB_DURATION_MS
    } else {
        IDLE_BOB_DURATION_MS
    }

    val bobOffset by if (isAnimated) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = bobTarget,
            animationSpec = infiniteRepeatable(
                animation = tween(bobDuration, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "MascotBob"
        )
    } else {
        rememberUpdatedState(0f)
    }

    // 2. Sprout Left Leaf Flutter
    val leftLeafSway by if (isAnimated) {
        infiniteTransition.animateFloat(
            initialValue = -MAX_LEAF_SWAY_DEG,
            targetValue = MAX_LEAF_SWAY_DEG * 0.7f,
            animationSpec = infiniteRepeatable(
                animation = tween(LEAF_FLUTTER_DURATION_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "MascotLeftLeafSway"
        )
    } else {
        rememberUpdatedState(0f)
    }

    // 3. Sprout Right Leaf Flutter (Phase shifted)
    val rightLeafSway by if (isAnimated) {
        infiniteTransition.animateFloat(
            initialValue = MAX_LEAF_SWAY_DEG * 0.8f,
            targetValue = -MAX_LEAF_SWAY_DEG,
            animationSpec = infiniteRepeatable(
                animation = tween(LEAF_FLUTTER_DURATION_MS + 250, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "MascotRightLeafSway"
        )
    } else {
        rememberUpdatedState(0f)
    }

    // 4. Blushing Cheek Pulse when listening to music
    val cheekPulseScale by if (isAnimated && isListening) {
        infiniteTransition.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(BLUSH_PULSE_DURATION_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "MascotCheekPulse"
        )
    } else {
        rememberUpdatedState(1f)
    }

    // 5. Natural Blinking: Single, infrequent crisp blink
    val blinkFraction by if (isAnimated) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.05f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = BLINK_CYCLE_DURATION_MS
                    // Eyes remain open for most of the period
                    1.0f at 0
                    1.0f at 5800
                    // Single crisp blink
                    0.05f at 5920
                    1.0f at 6040
                    1.0f at BLINK_CYCLE_DURATION_MS
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "MascotBlink"
        )
    } else {
        rememberUpdatedState(1f)
    }

    val handleTap: () -> Unit = {
        coroutineScope.launch {
            isHappyEyes = true
            kotlinx.coroutines.delay(HAPPY_EYE_HOLD_DURATION_MS)
            isHappyEyes = false
        }

        if (onClick != null) {
            onClick()
        }
    }

    val clickableModifier = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null
    ) {
        handleTap()
    }

    Canvas(
        modifier = modifier
            .size(size)
            .then(clickableModifier)
    ) {
        val canvasWidth = this.size.width
        val canvasHeight = this.size.height
        val unit = canvasWidth / 100f
        val centerY = (canvasHeight / 2f) + (bobOffset * unit * 0.5f)
        val centerX = canvasWidth / 2f

        val leafColor = Color(0xFF6ED199)
        val leafOutlineColor = Color(0xFF2C6D48)
        val headphoneColor = BlossomCoral
        val headphoneOutlineColor = Color(0xFF9E3A32)

        val bodyRadiusX = 28.5f * unit
        val bodyRadiusY = 23.5f * unit
        val bodyColor = Color(0xFFA8E6CF)
        val bodyOutlineColor = Color(0xFF264E36)

        // 1. Headphone Headband Arc
        val arcRadiusX = 29.5f * unit
        val arcRadiusY = 32f * unit
        val arcTopY = centerY - arcRadiusY - 5f * unit

        if (simplified) {
            drawArc(
                color = headphoneColor,
                startAngle = 185f,
                sweepAngle = 170f,
                useCenter = false,
                topLeft = Offset(centerX - arcRadiusX, arcTopY),
                size = Size(arcRadiusX * 2f, arcRadiusY * 2f),
                style = Stroke(width = 5.0f * unit, cap = StrokeCap.Round)
            )
        } else {
            drawArc(
                color = headphoneOutlineColor,
                startAngle = 185f,
                sweepAngle = 170f,
                useCenter = false,
                topLeft = Offset(centerX - arcRadiusX, arcTopY),
                size = Size(arcRadiusX * 2f, arcRadiusY * 2f),
                style = Stroke(width = 6f * unit, cap = StrokeCap.Round)
            )

            drawArc(
                color = headphoneColor,
                startAngle = 185f,
                sweepAngle = 170f,
                useCenter = false,
                topLeft = Offset(centerX - arcRadiusX, arcTopY),
                size = Size(arcRadiusX * 2f, arcRadiusY * 2f),
                style = Stroke(width = 3.6f * unit, cap = StrokeCap.Round)
            )
        }

        // 2. Oval Pea Body
        if (!simplified) {
            drawOval(
                color = Color(0x33000000),
                topLeft = Offset(centerX - bodyRadiusX, centerY - bodyRadiusY + 2f * unit),
                size = Size(bodyRadiusX * 2f, bodyRadiusY * 2f)
            )
        }

        // Main Body Fill
        drawOval(
            color = bodyColor,
            topLeft = Offset(centerX - bodyRadiusX, centerY - bodyRadiusY),
            size = Size(bodyRadiusX * 2f, bodyRadiusY * 2f)
        )

        // Body Outline
        val bodyStrokeWidth = if (simplified) {
            2.6f * unit
        } else {
            2.2f * unit
        }

        drawOval(
            color = bodyOutlineColor,
            topLeft = Offset(centerX - bodyRadiusX, centerY - bodyRadiusY),
            size = Size(bodyRadiusX * 2f, bodyRadiusY * 2f),
            style = Stroke(width = bodyStrokeWidth)
        )

        // 3. Headphone Earcups
        if (simplified) {
            val simpleEarcupWidth = 7.0f * unit
            val simpleEarcupHeight = 24.0f * unit
            val simpleCornerRadius = 3.5f * unit

            // Left Earcup Pill
            val leftEarcupTopLeft = Offset(centerX - bodyRadiusX - 4.5f * unit, centerY - simpleEarcupHeight / 2f)
            drawRoundRect(
                color = headphoneColor,
                topLeft = leftEarcupTopLeft,
                size = Size(simpleEarcupWidth, simpleEarcupHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(simpleCornerRadius, simpleCornerRadius)
            )
            drawRoundRect(
                color = headphoneOutlineColor,
                topLeft = leftEarcupTopLeft,
                size = Size(simpleEarcupWidth, simpleEarcupHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(simpleCornerRadius, simpleCornerRadius),
                style = Stroke(width = 2.4f * unit)
            )

            // Right Earcup Pill
            val rightEarcupTopLeft = Offset(centerX + bodyRadiusX - 2.5f * unit, centerY - simpleEarcupHeight / 2f)
            drawRoundRect(
                color = headphoneColor,
                topLeft = rightEarcupTopLeft,
                size = Size(simpleEarcupWidth, simpleEarcupHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(simpleCornerRadius, simpleCornerRadius)
            )
            drawRoundRect(
                color = headphoneOutlineColor,
                topLeft = rightEarcupTopLeft,
                size = Size(simpleEarcupWidth, simpleEarcupHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(simpleCornerRadius, simpleCornerRadius),
                style = Stroke(width = 2.4f * unit)
            )
        } else {
            val earcupHeight = 27.5f * unit
            val earcupWidth = 5.8f * unit
            val outerDomeRadius = 9f * unit

            // Left Inner Cushion
            drawRoundRect(
                color = headphoneOutlineColor,
                topLeft = Offset(centerX - bodyRadiusX - 1.5f * unit, centerY - earcupHeight / 2f),
                size = Size(earcupWidth, earcupHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.5f * unit, 3.5f * unit)
            )
            drawRoundRect(
                color = headphoneColor,
                topLeft = Offset(centerX - bodyRadiusX - 0.5f * unit, centerY - (earcupHeight / 2f) + 1f * unit),
                size = Size(earcupWidth - 1.8f * unit, earcupHeight - 2f * unit),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.6f * unit, 2.6f * unit)
            )

            // Left Outer Semi-Circle Dome
            drawArc(
                color = headphoneOutlineColor,
                startAngle = 90f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(centerX - bodyRadiusX - 1.5f * unit - outerDomeRadius, centerY - outerDomeRadius),
                size = Size(outerDomeRadius * 2f, outerDomeRadius * 2f),
                style = Fill
            )
            drawArc(
                color = headphoneColor,
                startAngle = 90f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(centerX - bodyRadiusX - 1.5f * unit - outerDomeRadius + 1.2f * unit, centerY - outerDomeRadius + 1.2f * unit),
                size = Size((outerDomeRadius - 1.2f * unit) * 2f, (outerDomeRadius - 1.2f * unit) * 2f),
                style = Fill
            )
            drawArc(
                color = headphoneOutlineColor,
                startAngle = 90f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(centerX - bodyRadiusX - 1.5f * unit - outerDomeRadius, centerY - outerDomeRadius),
                size = Size(outerDomeRadius * 2f, outerDomeRadius * 2f),
                style = Stroke(width = 2.2f * unit)
            )

            // Right Inner Cushion
            drawRoundRect(
                color = headphoneOutlineColor,
                topLeft = Offset(centerX + bodyRadiusX - 4.3f * unit, centerY - earcupHeight / 2f),
                size = Size(earcupWidth, earcupHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.5f * unit, 3.5f * unit)
            )
            drawRoundRect(
                color = headphoneColor,
                topLeft = Offset(centerX + bodyRadiusX - 3.3f * unit, centerY - (earcupHeight / 2f) + 1f * unit),
                size = Size(earcupWidth - 1.8f * unit, earcupHeight - 2f * unit),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.6f * unit, 2.6f * unit)
            )

            // Right Outer Semi-Circle Dome
            drawArc(
                color = headphoneOutlineColor,
                startAngle = 270f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(centerX + bodyRadiusX + 1.5f * unit - outerDomeRadius, centerY - outerDomeRadius),
                size = Size(outerDomeRadius * 2f, outerDomeRadius * 2f),
                style = Fill
            )
            drawArc(
                color = headphoneColor,
                startAngle = 270f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(centerX + bodyRadiusX + 1.5f * unit - outerDomeRadius + 1.2f * unit, centerY - outerDomeRadius + 1.2f * unit),
                size = Size((outerDomeRadius - 1.2f * unit) * 2f, (outerDomeRadius - 1.2f * unit) * 2f),
                style = Fill
            )
            drawArc(
                color = headphoneOutlineColor,
                startAngle = 270f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(centerX + bodyRadiusX + 1.5f * unit - outerDomeRadius, centerY - outerDomeRadius),
                size = Size(outerDomeRadius * 2f, outerDomeRadius * 2f),
                style = Stroke(width = 2.2f * unit)
            )
        }

        // 4. Vertical Sprout Stem & Two Sprout Leaves
        val stemTopY = centerY - bodyRadiusY - 3.5f * unit
        val stemBaseY = centerY - bodyRadiusY + 2f * unit

        if (!simplified) {
            drawLine(
                color = leafOutlineColor,
                start = Offset(centerX, stemBaseY),
                end = Offset(centerX, stemTopY),
                strokeWidth = 4f * unit,
                cap = StrokeCap.Round
            )
        }

        val stemColor = if (simplified) {
            leafOutlineColor
        } else {
            leafColor
        }

        val stemStrokeWidth = if (simplified) {
            3.0f * unit
        } else {
            2.2f * unit
        }

        drawLine(
            color = stemColor,
            start = Offset(centerX, stemBaseY),
            end = Offset(centerX, stemTopY),
            strokeWidth = stemStrokeWidth,
            cap = StrokeCap.Round
        )

        val leafStrokeWidth = if (simplified) {
            2.5f * unit
        } else {
            2.2f * unit
        }

        // Left Sprout Leaf
        withTransform({
            rotate(leftLeafSway, pivot = Offset(centerX, stemTopY))
        }) {
            val leftLeafTip = Offset(centerX - 19f * unit, stemTopY - 9.5f * unit)
            val leftLeafPath = Path().apply {
                moveTo(centerX, stemTopY)
                cubicTo(
                    centerX - 4f * unit, stemTopY - 12f * unit,
                    centerX - 13f * unit, stemTopY - 15f * unit,
                    leftLeafTip.x, leftLeafTip.y
                )
                cubicTo(
                    centerX - 19f * unit, stemTopY - 2f * unit,
                    centerX - 9f * unit, stemTopY + 1.5f * unit,
                    centerX, stemTopY
                )
                close()
            }

            drawPath(leftLeafPath, color = leafColor, style = Fill)
            drawPath(leftLeafPath, color = leafOutlineColor, style = Stroke(width = leafStrokeWidth))

            if (!simplified) {
                drawLine(
                    color = leafOutlineColor,
                    start = Offset(centerX, stemTopY),
                    end = Offset(centerX - 13f * unit, stemTopY - 6.5f * unit),
                    strokeWidth = 1.8f * unit,
                    cap = StrokeCap.Round
                )
            }
        }

        // Right Sprout Leaf
        withTransform({
            rotate(rightLeafSway, pivot = Offset(centerX, stemTopY))
        }) {
            val rightLeafTip = Offset(centerX + 19f * unit, stemTopY - 9.5f * unit)
            val rightLeafPath = Path().apply {
                moveTo(centerX, stemTopY)
                cubicTo(
                    centerX + 4f * unit, stemTopY - 12f * unit,
                    centerX + 13f * unit, stemTopY - 15f * unit,
                    rightLeafTip.x, rightLeafTip.y
                )
                cubicTo(
                    centerX + 19f * unit, stemTopY - 2f * unit,
                    centerX + 9f * unit, stemTopY + 1.5f * unit,
                    centerX, stemTopY
                )
                close()
            }

            drawPath(rightLeafPath, color = leafColor, style = Fill)
            drawPath(rightLeafPath, color = leafOutlineColor, style = Stroke(width = leafStrokeWidth))

            if (!simplified) {
                drawLine(
                    color = leafOutlineColor,
                    start = Offset(centerX, stemTopY),
                    end = Offset(centerX + 13f * unit, stemTopY - 6.5f * unit),
                    strokeWidth = 1.8f * unit,
                    cap = StrokeCap.Round
                )
            }
        }

        // 5. Blushing Cheeks
        val effectiveCheekScale = if (isHappyEyes) {
            1.22f
        } else {
            cheekPulseScale
        }

        val blushColor = BlossomCoralSoft.copy(alpha = (0.85f * effectiveCheekScale).coerceIn(0f, 1f))
        val baseBlushX = if (simplified) {
            3.8f
        } else {
            4.5f
        }
        val baseBlushY = if (simplified) {
            2.5f
        } else {
            3.0f
        }
        val blushRadiusX = baseBlushX * unit * effectiveCheekScale
        val blushRadiusY = baseBlushY * unit * effectiveCheekScale

        drawOval(
            color = blushColor,
            topLeft = Offset(centerX - 17f * unit - blushRadiusX, centerY + 5f * unit - blushRadiusY),
            size = Size(blushRadiusX * 2f, blushRadiusY * 2f)
        )
        drawOval(
            color = blushColor,
            topLeft = Offset(centerX + 17f * unit - blushRadiusX, centerY + 5f * unit - blushRadiusY),
            size = Size(blushRadiusX * 2f, blushRadiusY * 2f)
        )

        // 6. Cute Eyes
        val eyeColor = Color(0xFF1B231D)
        val eyeRadius = if (simplified) {
            3.6f * unit
        } else {
            3.2f * unit
        }
        val eyeScaleY = blinkFraction

        if (isHappyEyes) {
            val leftHappyPath = Path().apply {
                moveTo(centerX - 13.5f * unit, centerY + 1.8f * unit)
                quadraticTo(
                    centerX - 10.5f * unit, centerY - 1.0f * unit,
                    centerX - 7.5f * unit, centerY + 1.8f * unit
                )
            }
            drawPath(leftHappyPath, color = eyeColor, style = Stroke(width = 2.4f * unit, cap = StrokeCap.Round))

            val rightHappyPath = Path().apply {
                moveTo(centerX + 7.5f * unit, centerY + 1.8f * unit)
                quadraticTo(
                    centerX + 10.5f * unit, centerY - 1.0f * unit,
                    centerX + 13.5f * unit, centerY + 1.8f * unit
                )
            }
            drawPath(rightHappyPath, color = eyeColor, style = Stroke(width = 2.4f * unit, cap = StrokeCap.Round))
        } else if (eyeScaleY > 0.25f) {
            // Left Eye
            drawOval(
                color = eyeColor,
                topLeft = Offset(centerX - 10.5f * unit - eyeRadius, centerY + 1f * unit - (eyeRadius * eyeScaleY)),
                size = Size(eyeRadius * 2f, eyeRadius * 2f * eyeScaleY)
            )

            if (!simplified) {
                drawCircle(
                    color = Color.White,
                    radius = eyeRadius * EYE_GLINT_FRACTION,
                    center = Offset(centerX - 11.5f * unit, centerY - 0.2f * unit)
                )
            }

            // Right Eye
            drawOval(
                color = eyeColor,
                topLeft = Offset(centerX + 10.5f * unit - eyeRadius, centerY + 1f * unit - (eyeRadius * eyeScaleY)),
                size = Size(eyeRadius * 2f, eyeRadius * 2f * eyeScaleY)
            )

            if (!simplified) {
                drawCircle(
                    color = Color.White,
                    radius = eyeRadius * EYE_GLINT_FRACTION,
                    center = Offset(centerX + 9.5f * unit, centerY - 0.2f * unit)
                )
            }
        } else {
            drawLine(
                color = eyeColor,
                start = Offset(centerX - 13.5f * unit, centerY + 1f * unit),
                end = Offset(centerX - 7.5f * unit, centerY + 1f * unit),
                strokeWidth = 2.4f * unit,
                cap = StrokeCap.Round
            )
            drawLine(
                color = eyeColor,
                start = Offset(centerX + 7.5f * unit, centerY + 1f * unit),
                end = Offset(centerX + 13.5f * unit, centerY + 1f * unit),
                strokeWidth = 2.4f * unit,
                cap = StrokeCap.Round
            )
        }

        // 7. Sweet Smile Mouth
        val mouthWidth = if (isHappyEyes) {
            5.2f * unit
        } else {
            4.5f * unit
        }
        val mouthDepth = if (isHappyEyes) {
            9.0f * unit
        } else {
            8.5f * unit
        }
        val smilePath = Path().apply {
            moveTo(centerX - mouthWidth, centerY + 5.5f * unit)
            quadraticTo(
                centerX, centerY + mouthDepth,
                centerX + mouthWidth, centerY + 5.5f * unit
            )
        }
        val smileStrokeWidth = if (simplified) {
            2.5f * unit
        } else {
            2.2f * unit
        }

        drawPath(
            path = smilePath,
            color = eyeColor,
            style = Stroke(width = smileStrokeWidth, cap = StrokeCap.Round)
        )
    }
}
