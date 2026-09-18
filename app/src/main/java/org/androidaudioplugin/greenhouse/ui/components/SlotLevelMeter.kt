package org.androidaudioplugin.greenhouse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import org.androidaudioplugin.greenhouse.ui.theme.*
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

const val MIN_METER_DB = -60.0f
const val LOW_METER_DB = -36.0f
const val MID_METER_DB = -18.0f
const val NOMINAL_METER_DB = -6.0f
const val MAX_METER_DB = 0.0f

const val NORM_MIN = 0.0f
const val NORM_LOW = 0.15f
const val NORM_MID = 0.40f
const val NORM_NOMINAL = 0.70f
const val NORM_MAX = 1.0f

const val CLIP_AMPLITUDE_THRESHOLD = 0.98f
const val SILENCE_AMPLITUDE_THRESHOLD = 0.0001f
const val DB_SCALE_FACTOR = 20.0f

// Vertical Gradient Stops (0.0f = Top of meter bar, 1.0f = Bottom of meter bar)
const val VERTICAL_STOP_CLIP_TOP = 0.00f
const val VERTICAL_STOP_CLIP_BOTTOM = 0.04f
const val VERTICAL_STOP_WARNING_TOP = 0.10f
const val VERTICAL_STOP_WARNING_BOTTOM = 0.25f
const val VERTICAL_STOP_NORMAL_TOP = 0.30f
const val VERTICAL_STOP_NORMAL_BOTTOM = 1.00f

// Horizontal Gradient Stops (0.0f = Left of meter bar, 1.0f = Right of meter bar)
const val HORIZONTAL_STOP_NORMAL_START = 0.00f
const val HORIZONTAL_STOP_NORMAL_END = 0.70f
const val HORIZONTAL_STOP_WARNING_START = 0.75f
const val HORIZONTAL_STOP_WARNING_END = 0.90f
const val HORIZONTAL_STOP_CLIP_START = 0.96f
const val HORIZONTAL_STOP_CLIP_END = 1.00f

// Floating Peak-Hold Needle Constants
const val PEAK_HOLD_DURATION_MS = 2000L
const val PEAK_DECAY_TIME_CONSTANT_SEC = 0.18f
const val PEAK_MIN_DECAY_PER_SECOND = 1.20f
const val SILENCE_PEAK_THRESHOLD = 0.005f
const val MAX_FRAME_DELTA_MS = 100L
const val MILLIS_PER_SECOND = 1000.0f
const val NEEDLE_CORNER_RADIUS_PX = 1.0f
const val NEEDLE_CENTER_OFFSET_MULTIPLIER = 0.5f
const val PEAK_NEEDLE_WARNING_THRESHOLD = 0.70f
const val PEAK_NEEDLE_CLIP_THRESHOLD = 0.90f
val PEAK_NEEDLE_SIZE_DP = 1.5.dp

fun amplitudeToNormalizedLevel(amplitude: Float): Float {
    if (amplitude <= SILENCE_AMPLITUDE_THRESHOLD) {
        return NORM_MIN
    }

    val db = (DB_SCALE_FACTOR * log10(max(amplitude, SILENCE_AMPLITUDE_THRESHOLD))).coerceIn(MIN_METER_DB, MAX_METER_DB)

    if (db <= MIN_METER_DB) {
        return NORM_MIN
    }

    if (db < LOW_METER_DB) {
        val fraction = (db - MIN_METER_DB) / (LOW_METER_DB - MIN_METER_DB)
        return fraction * NORM_LOW
    }

    if (db < MID_METER_DB) {
        val fraction = (db - LOW_METER_DB) / (MID_METER_DB - LOW_METER_DB)
        return NORM_LOW + fraction * (NORM_MID - NORM_LOW)
    }

    if (db < NOMINAL_METER_DB) {
        val fraction = (db - MID_METER_DB) / (NOMINAL_METER_DB - MID_METER_DB)
        return NORM_MID + fraction * (NORM_NOMINAL - NORM_MID)
    }

    val fraction = (db - NOMINAL_METER_DB) / (MAX_METER_DB - NOMINAL_METER_DB)
    return NORM_NOMINAL + fraction * (NORM_MAX - NORM_NOMINAL)
}

@Composable
fun SlotStereoLevelMeter(
    levelLeft: Float,
    levelRight: Float,
    isBypassed: Boolean,
    isProcessing: Boolean,
    modifier: Modifier = Modifier,
    meterWidth: Dp = 13.dp,
    meterHeight: Dp = 86.dp
) {
    val isMeterActive = isProcessing && !isBypassed

    val normL = if (isMeterActive) {
        amplitudeToNormalizedLevel(levelLeft)
    } else {
        0.0f
    }

    val normR = if (isMeterActive) {
        amplitudeToNormalizedLevel(levelRight)
    } else {
        0.0f
    }

    val isClippingL = isMeterActive && levelLeft >= CLIP_AMPLITUDE_THRESHOLD
    val isClippingR = isMeterActive && levelRight >= CLIP_AMPLITUDE_THRESHOLD

    Box(
        modifier = modifier
            .width(meterWidth)
            .height(meterHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(MeterTrackBackground)
            .border(1.dp, StudioPanelBorder.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .padding(horizontal = 2.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Left Channel Bar
            ChannelMeterBar(
                normalizedLevel = normL,
                isClipping = isClippingL,
                isActive = isMeterActive,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            // Right Channel Bar
            ChannelMeterBar(
                normalizedLevel = normR,
                isClipping = isClippingR,
                isActive = isMeterActive,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun ChannelMeterBar(
    normalizedLevel: Float,
    isClipping: Boolean,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    var peakLevel by remember { mutableFloatStateOf(0.0f) }
    val currentLevel by rememberUpdatedState(normalizedLevel)
    val activeState by rememberUpdatedState(isActive)

    val shouldAnimate = activeState && (currentLevel > 0.0f || peakLevel > 0.0f)

    LaunchedEffect(shouldAnimate) {
        if (shouldAnimate) {
            var holdUntilMs = System.currentTimeMillis() + PEAK_HOLD_DURATION_MS
            var lastFrameTimeMs = System.currentTimeMillis()

            while (activeState && (peakLevel > 0.0f || currentLevel > 0.0f)) {
                withFrameNanos { _ ->
                    val nowMs = System.currentTimeMillis()
                    val frameDeltaMs = (nowMs - lastFrameTimeMs).coerceIn(0L, MAX_FRAME_DELTA_MS)
                    val dtSec = frameDeltaMs.toFloat() / MILLIS_PER_SECOND
                    lastFrameTimeMs = nowMs
                    val target = currentLevel

                    if (!activeState) {
                        peakLevel = 0.0f
                    } else if (target >= peakLevel) {
                        peakLevel = target
                        holdUntilMs = nowMs + PEAK_HOLD_DURATION_MS
                    } else if (nowMs > holdUntilMs && peakLevel > target) {
                        val alpha = 1.0f - exp(-dtSec / PEAK_DECAY_TIME_CONSTANT_SEC)
                        val expDecay = (peakLevel - target) * alpha
                        val linearDecay = dtSec * PEAK_MIN_DECAY_PER_SECOND
                        val decayDelta = max(expDecay, linearDecay)
                        peakLevel = max(target, peakLevel - decayDelta)
                    }

                    if (peakLevel <= SILENCE_PEAK_THRESHOLD && target <= SILENCE_PEAK_THRESHOLD) {
                        peakLevel = 0.0f
                    }
                }
            }
        } else {
            peakLevel = 0.0f
        }
    }

    Canvas(modifier = modifier) {
        val totalHeight = size.height
        val barWidth = size.width
        val fillHeight = totalHeight * normalizedLevel.coerceIn(0.0f, 1.0f)
        val fillTop = totalHeight - fillHeight

        // Background Track
        drawRoundRect(
            color = MeterTrackBackground,
            size = size,
            cornerRadius = CornerRadius(2f, 2f)
        )

        // Active Level with Studio 3-tier Color Gradient
        if (fillHeight > 0f) {
            val gradientBrush = Brush.verticalGradient(
                VERTICAL_STOP_CLIP_TOP to MeterClipColor,
                VERTICAL_STOP_CLIP_BOTTOM to MeterClipColor,
                VERTICAL_STOP_WARNING_TOP to MeterWarningColor,
                VERTICAL_STOP_WARNING_BOTTOM to MeterWarningColor,
                VERTICAL_STOP_NORMAL_TOP to MeterNormalColor,
                VERTICAL_STOP_NORMAL_BOTTOM to MeterNormalColor.copy(alpha = 0.85f),
                startY = 0f,
                endY = totalHeight
            )

            drawRoundRect(
                brush = gradientBrush,
                topLeft = Offset(0f, fillTop),
                size = Size(barWidth, fillHeight),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }

        // Floating Peak-Hold Needle
        if (peakLevel > SILENCE_PEAK_THRESHOLD) {
            val needleY = totalHeight - (totalHeight * peakLevel.coerceIn(NORM_MIN, NORM_MAX))
            val needleHeightPx = PEAK_NEEDLE_SIZE_DP.toPx()
            val needleTop = (needleY - (needleHeightPx * NEEDLE_CENTER_OFFSET_MULTIPLIER))
                .coerceIn(0.0f, totalHeight - needleHeightPx)

            val needleColor = when {
                peakLevel >= PEAK_NEEDLE_CLIP_THRESHOLD -> MeterClipColor
                peakLevel >= PEAK_NEEDLE_WARNING_THRESHOLD -> MeterWarningColor
                else -> SproutGreenBright
            }

            drawRoundRect(
                color = needleColor,
                topLeft = Offset(0.0f, needleTop),
                size = Size(barWidth, needleHeightPx),
                cornerRadius = CornerRadius(NEEDLE_CORNER_RADIUS_PX, NEEDLE_CORNER_RADIUS_PX)
            )
        }

        // Top Clip Indicator LED dot
        if (isClipping) {
            drawRoundRect(
                color = MeterClipColor,
                topLeft = Offset(0f, 0f),
                size = Size(barWidth, 3.dp.toPx()),
                cornerRadius = CornerRadius(1.5f, 1.5f)
            )
        }
    }
}

@Composable
fun HorizontalStereoMeter(
    levelLeft: Float,
    levelRight: Float,
    isProcessing: Boolean,
    modifier: Modifier = Modifier
) {
    val normL = if (isProcessing) {
        amplitudeToNormalizedLevel(levelLeft)
    } else {
        0.0f
    }

    val normR = if (isProcessing) {
        amplitudeToNormalizedLevel(levelRight)
    } else {
        0.0f
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MeterTrackBackground)
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "L",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = TextSecondary,
                modifier = Modifier.width(10.dp)
            )

            HorizontalChannelBar(
                normalizedLevel = normL,
                isActive = isProcessing,
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "R",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = TextSecondary,
                modifier = Modifier.width(10.dp)
            )

            HorizontalChannelBar(
                normalizedLevel = normR,
                isActive = isProcessing,
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
            )
        }
    }
}

@Composable
private fun HorizontalChannelBar(
    normalizedLevel: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    var peakLevel by remember { mutableFloatStateOf(0.0f) }
    val currentLevel by rememberUpdatedState(normalizedLevel)
    val activeState by rememberUpdatedState(isActive)

    val shouldAnimate = activeState && (currentLevel > 0.0f || peakLevel > 0.0f)

    LaunchedEffect(shouldAnimate) {
        if (shouldAnimate) {
            var holdUntilMs = System.currentTimeMillis() + PEAK_HOLD_DURATION_MS
            var lastFrameTimeMs = System.currentTimeMillis()

            while (activeState && (peakLevel > 0.0f || currentLevel > 0.0f)) {
                withFrameNanos { _ ->
                    val nowMs = System.currentTimeMillis()
                    val frameDeltaMs = (nowMs - lastFrameTimeMs).coerceIn(0L, MAX_FRAME_DELTA_MS)
                    val dtSec = frameDeltaMs.toFloat() / MILLIS_PER_SECOND
                    lastFrameTimeMs = nowMs
                    val target = currentLevel

                    if (!activeState) {
                        peakLevel = 0.0f
                    } else if (target >= peakLevel) {
                        peakLevel = target
                        holdUntilMs = nowMs + PEAK_HOLD_DURATION_MS
                    } else if (nowMs > holdUntilMs && peakLevel > target) {
                        val alpha = 1.0f - exp(-dtSec / PEAK_DECAY_TIME_CONSTANT_SEC)
                        val expDecay = (peakLevel - target) * alpha
                        val linearDecay = dtSec * PEAK_MIN_DECAY_PER_SECOND
                        val decayDelta = max(expDecay, linearDecay)
                        peakLevel = max(target, peakLevel - decayDelta)
                    }

                    if (peakLevel <= SILENCE_PEAK_THRESHOLD && target <= SILENCE_PEAK_THRESHOLD) {
                        peakLevel = 0.0f
                    }
                }
            }
        } else {
            peakLevel = 0.0f
        }
    }

    Canvas(modifier = modifier) {
        val totalWidth = size.width
        val barHeight = size.height
        val fillWidth = totalWidth * normalizedLevel.coerceIn(0.0f, 1.0f)

        // Background Track
        drawRoundRect(
            color = MeterTrackBackground,
            size = size,
            cornerRadius = CornerRadius(2f, 2f)
        )

        // Active Level Gradient (Left to Right)
        if (fillWidth > 0f) {
            val gradientBrush = Brush.horizontalGradient(
                HORIZONTAL_STOP_NORMAL_START to MeterNormalColor,
                HORIZONTAL_STOP_NORMAL_END to MeterNormalColor,
                HORIZONTAL_STOP_WARNING_START to MeterWarningColor,
                HORIZONTAL_STOP_WARNING_END to MeterWarningColor,
                HORIZONTAL_STOP_CLIP_START to MeterClipColor,
                HORIZONTAL_STOP_CLIP_END to MeterClipColor,
                startX = 0f,
                endX = totalWidth
            )

            drawRoundRect(
                brush = gradientBrush,
                topLeft = Offset(0f, 0f),
                size = Size(fillWidth, barHeight),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }

        // Floating Peak-Hold Needle
        if (peakLevel > SILENCE_PEAK_THRESHOLD) {
            val needleX = totalWidth * peakLevel.coerceIn(NORM_MIN, NORM_MAX)
            val needleWidthPx = PEAK_NEEDLE_SIZE_DP.toPx()
            val needleLeft = (needleX - (needleWidthPx * NEEDLE_CENTER_OFFSET_MULTIPLIER))
                .coerceIn(0.0f, totalWidth - needleWidthPx)

            val needleColor = when {
                peakLevel >= PEAK_NEEDLE_CLIP_THRESHOLD -> MeterClipColor
                peakLevel >= PEAK_NEEDLE_WARNING_THRESHOLD -> MeterWarningColor
                else -> SproutGreenBright
            }

            drawRoundRect(
                color = needleColor,
                topLeft = Offset(needleLeft, 0.0f),
                size = Size(needleWidthPx, barHeight),
                cornerRadius = CornerRadius(NEEDLE_CORNER_RADIUS_PX, NEEDLE_CORNER_RADIUS_PX)
            )
        }
    }
}
