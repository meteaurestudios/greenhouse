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
import org.androidaudioplugin.greenhouse.ui.theme.*
import kotlin.math.log10
import kotlin.math.max

const val MIN_METER_DB = -60.0f
const val MAX_METER_DB = 0.0f
const val NOMINAL_METER_DB = -6.0f
const val CLIP_AMPLITUDE_THRESHOLD = 0.98f
const val SILENCE_AMPLITUDE_THRESHOLD = 0.0001f



fun amplitudeToNormalizedLevel(amplitude: Float): Float {
    if (amplitude <= SILENCE_AMPLITUDE_THRESHOLD) {
        return 0.0f
    }

    val db = 20.0f * log10(max(amplitude, SILENCE_AMPLITUDE_THRESHOLD))
    val clampedDb = db.coerceIn(MIN_METER_DB, MAX_METER_DB)
    return (clampedDb - MIN_METER_DB) / (MAX_METER_DB - MIN_METER_DB)
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
    val normL = if (isProcessing && !isBypassed) {
        amplitudeToNormalizedLevel(levelLeft)
    } else {
        0.0f
    }

    val normR = if (isProcessing && !isBypassed) {
        amplitudeToNormalizedLevel(levelRight)
    } else {
        0.0f
    }

    val isClippingL = isProcessing && !isBypassed && levelLeft >= CLIP_AMPLITUDE_THRESHOLD
    val isClippingR = isProcessing && !isBypassed && levelRight >= CLIP_AMPLITUDE_THRESHOLD

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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            // Right Channel Bar
            ChannelMeterBar(
                normalizedLevel = normR,
                isClipping = isClippingR,
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
    modifier: Modifier = Modifier
) {
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
                0.0f to MeterClipColor,
                0.20f to MeterWarningColor,
                0.40f to MeterNormalColor,
                1.0f to MeterNormalColor.copy(alpha = 0.85f),
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
    modifier: Modifier = Modifier
) {
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
                0.0f to MeterNormalColor,
                0.65f to MeterNormalColor,
                0.85f to MeterWarningColor,
                1.0f to MeterClipColor,
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
    }
}
