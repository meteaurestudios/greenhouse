package org.androidaudioplugin.greenhouse.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import org.androidaudioplugin.greenhouse.ui.theme.tabularTextStyle
import org.androidaudioplugin.greenhouse.ui.theme.TextMuted
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary

private val STEPPER_ICON_SIZE = 16.dp
// Tap feedback of the steps: a circle around the icon, clear of the label
private val STEPPER_RIPPLE_RADIUS = 14.dp

/**
 * A value between - and + steps in one control (the keyboard octave, the plugin UI zoom).
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
    height: Dp = CONTROL_HEIGHT,
    iconSize: Dp = STEPPER_ICON_SIZE,
    fontSize: TextUnit = CONTROL_LABEL_FONT_SIZE,
    /** Width kept for the label, so the steps do not move as it changes; null to fit it. */
    labelWidth: Dp? = null
) {
    Row(
        modifier = modifier
            .height(height)
            .controlSurface(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepButton(Icons.Default.Remove, decrementDescription, canDecrement, height, iconSize, onDecrement)

        Text(
            text = label,
            fontSize = fontSize,
            style = tabularTextStyle,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = if (labelWidth != null) {
                Modifier.width(labelWidth)
            } else {
                Modifier
            }
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
                TextSecondary
            } else {
                TextMuted.copy(alpha = CONTROL_DISABLED_ALPHA)
            },
            modifier = Modifier.size(iconSize)
        )
    }
}
