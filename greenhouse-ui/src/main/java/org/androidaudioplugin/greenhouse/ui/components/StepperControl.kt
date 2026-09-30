package org.androidaudioplugin.greenhouse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.ui.theme.tabularTextStyle
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceElevated
import org.androidaudioplugin.greenhouse.ui.theme.TextMuted
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary

private val STEPPER_BUTTON_SIZE = 26.dp
private val STEPPER_ICON_SIZE = 14.dp
private val STEPPER_FONT_SIZE = 10.sp
private val STEPPER_CORNER_RADIUS = 6.dp
// Tap feedback of the steps: a circle around the icon, clear of the label
private val STEPPER_RIPPLE_RADIUS = 10.dp

/**
 * A value between - and + steps in one bordered pill (the keyboard octave, the plugin UI zoom).
 * A step that cannot go further is dimmed.
 */
@Composable
fun StepperControl(
    label: String,
    canDecrement: Boolean,
    canIncrement: Boolean,
    decrementDescription: String,
    incrementDescription: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    /** Also the width of each step. */
    height: Dp = STEPPER_BUTTON_SIZE,
    iconSize: Dp = STEPPER_ICON_SIZE,
    fontSize: TextUnit = STEPPER_FONT_SIZE
) {
    val shape = RoundedCornerShape(STEPPER_CORNER_RADIUS)

    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(StudioSurfaceElevated)
            .border(1.dp, StudioPanelBorder, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepButton(Icons.Default.Remove, decrementDescription, canDecrement, height, iconSize, onDecrement)

        Text(
            text = label,
            fontSize = fontSize,
            style = tabularTextStyle,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            letterSpacing = 0.5.sp,
            maxLines = 1
        )

        StepButton(Icons.Default.Add, incrementDescription, canIncrement, height, iconSize, onIncrement)
    }
}

@Composable
private fun StepButton(
    icon: ImageVector,
    contentDescription: String,
    isEnabled: Boolean,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clickable(
                interactionSource = null,
                indication = ripple(bounded = false, radius = STEPPER_RIPPLE_RADIUS),
                enabled = isEnabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isEnabled) {
                SproutGreen
            } else {
                TextMuted
            },
            modifier = Modifier.size(iconSize)
        )
    }
}
