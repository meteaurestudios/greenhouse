package org.androidaudioplugin.greenhouse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceVariant
import org.androidaudioplugin.greenhouse.ui.theme.TextMuted
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary

/*
 * Shared look of the rack's panels and of the controls inside them.
 *
 * A panel is a bordered, rounded card (the main panel, the keyboard). Inside a panel, controls are
 * filled "wells" without an outline: a lighter fill at rest, a tint of their accent while on.
 * Nesting outlines inside outlines is what made the screen look busy.
 */

val PANEL_CORNER_RADIUS = 20.dp
val PANEL_BORDER_WIDTH = 1.dp
val PANEL_SHAPE = RoundedCornerShape(PANEL_CORNER_RADIUS)
val CONTROL_HEIGHT = 34.dp
val CONTROL_CORNER_RADIUS = 10.dp
val CONTROL_SHAPE = RoundedCornerShape(CONTROL_CORNER_RADIUS)
val CONTROL_ICON_SIZE = 16.dp
val CONTROL_LABEL_FONT_SIZE = 12.sp
val CONTROL_HORIZONTAL_PADDING = 12.dp
val CONTROL_CONTENT_SPACING = 6.dp
val CONTROL_SPACING = 8.dp
// Fill of a control that is on, over the panel
const val CONTROL_ACTIVE_FILL_ALPHA = 0.16f
// A disabled control fades as a whole, not only its content
const val CONTROL_DISABLED_ALPHA = 0.4f

/** The bordered card of a rack panel. */
fun Modifier.panelSurface(background: Color = StudioSurface): Modifier {
    return clip(PANEL_SHAPE)
        .background(background)
        .border(PANEL_BORDER_WIDTH, StudioPanelBorder, PANEL_SHAPE)
}

/** The fill of a control inside a panel: [accent] tinted while [isActive]. */
fun Modifier.controlSurface(
    isActive: Boolean = false,
    accent: Color = SproutGreen,
    background: Color = StudioSurfaceVariant
): Modifier {
    val fill = if (isActive) {
        accent.copy(alpha = CONTROL_ACTIVE_FILL_ALPHA)
    } else {
        background
    }

    return clip(CONTROL_SHAPE).background(fill)
}

/**
 * Click handling of a toggle, without the press ripple: the toggle changes colour as it is tapped,
 * which is the feedback, and the ripple's fade lingered over the new colour.
 */
fun Modifier.toggleClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit
): Modifier {
    return clickable(
        interactionSource = null,
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
        onClick = onClick
    )
}

/** Content colour of a control: its accent while on, muted while disabled. */
fun controlContentColor(isActive: Boolean, isEnabled: Boolean = true, accent: Color = SproutGreen): Color {
    return when {
        !isEnabled -> TextMuted
        isActive -> accent
        else -> TextSecondary
    }
}

/**
 * A control of a panel toolbar: an optional icon and a label, centered together. Highlighted in
 * [accent] while [isActive].
 */
@Composable
fun ControlButton(
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isActive: Boolean = false,
    isEnabled: Boolean = true,
    accent: Color = SproutGreen,
    height: Dp = CONTROL_HEIGHT,
    contentDescription: String? = null
) {
    val contentColor = controlContentColor(isActive, isEnabled, accent)
    val horizontalPadding = if (label == null) {
        0.dp
    } else {
        CONTROL_HORIZONTAL_PADDING
    }

    Box(
        modifier = modifier
            // Icon only: a square
            .then(
                if (label == null) {
                    Modifier.size(height)
                } else {
                    Modifier.height(height)
                }
            )
            .alpha(
                if (isEnabled) {
                    1f
                } else {
                    CONTROL_DISABLED_ALPHA
                }
            )
            .controlSurface(isActive = isActive, accent = accent)
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CONTROL_CONTENT_SPACING, Alignment.CenterHorizontally)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(CONTROL_ICON_SIZE)
                )
            }

            if (label != null) {
                Text(
                    text = label,
                    fontSize = CONTROL_LABEL_FONT_SIZE,
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
