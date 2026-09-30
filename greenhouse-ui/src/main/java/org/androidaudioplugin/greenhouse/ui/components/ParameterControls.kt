package org.androidaudioplugin.greenhouse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.greenhouse.ui.theme.tabularTextStyle
import org.androidaudioplugin.greenhouse.ui.theme.BerryRose
import org.androidaudioplugin.greenhouse.ui.theme.StudioBackground
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceElevated
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceVariant
import org.androidaudioplugin.greenhouse.ui.theme.TextMuted
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.roundToInt

private const val EPSILON = 0.0001
private const val BOOLEAN_OFF_VALUE = 0.0
private const val BOOLEAN_ON_VALUE = 1.0

private val CONTROL_CONTAINER_HEIGHT = 54.dp
private val TOGGLE_BUTTON_HEIGHT = 30.dp
private val CONTROL_VALUE_FONT_SIZE = 11.sp
private val BUTTON_CORNER_RADIUS = 8.dp
private val STEPPER_BUTTON_SIZE = 28.dp
private val STEPPER_ICON_SIZE = 14.dp
private val LED_INDICATOR_SIZE = 6.dp
private const val TOGGLE_BUTTON_WIDTH_FRACTION = 0.84f
private const val DROPDOWN_FIELD_WIDTH_FRACTION = 0.92f
private val DROPDOWN_ICON_SIZE = 16.dp
private val DROPDOWN_LABEL_START_INSET = 2.dp
private val DROPDOWN_FIELD_HORIZONTAL_PADDING = 4.dp
private val ACTIVE_ALPHA = 0.18f
private val INACTIVE_ALPHA = 0.06f
private val BORDER_ACTIVE_ALPHA = 0.55f

@Composable
fun BooleanParameterToggle(
    isOn: Boolean,
    offLabel: String,
    onLabel: String,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onToggle: (Double) -> Unit
) {
    val currentLabel = if (isOn) {
        onLabel
    } else {
        offLabel
    }

    val textColor = if (isOn) {
        activeColor
    } else {
        TextSecondary
    }

    val ledColor = if (isOn) {
        activeColor
    } else {
        TextMuted
    }

    Box(
        modifier = modifier
            .fillMaxWidth(TOGGLE_BUTTON_WIDTH_FRACTION)
            .height(TOGGLE_BUTTON_HEIGHT)
            .controlSurface(isActive = isOn, accent = activeColor, background = StudioSurfaceElevated)
            .clickable {
                val nextValue = if (isOn) {
                    BOOLEAN_OFF_VALUE
                } else {
                    BOOLEAN_ON_VALUE
                }
                onToggle(nextValue)
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(LED_INDICATOR_SIZE)
                    .clip(CircleShape)
                    .background(ledColor)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = currentLabel,
                fontSize = CONTROL_VALUE_FONT_SIZE,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun EnumParameterSelector(
    currentValue: Double,
    options: List<ParameterInformation.EnumerationInformation>,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onSelect: (Double) -> Unit
) {
    var isMenuExpanded by remember { mutableStateOf(false) }

    val currentIndex = options.indexOfFirst { option ->
        abs(option.value - currentValue) < EPSILON
    }.let { foundIndex ->
        if (foundIndex >= 0) {
            foundIndex
        } else {
            0
        }
    }

    val currentOption = if (options.isNotEmpty() && currentIndex in options.indices) {
        options[currentIndex]
    } else {
        null
    }

    val currentLabel = currentOption?.name ?: "—"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CONTROL_CONTAINER_HEIGHT),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(DROPDOWN_FIELD_WIDTH_FRACTION)
                .height(TOGGLE_BUTTON_HEIGHT)
                .controlSurface(background = StudioSurfaceElevated)
                .clickable(enabled = options.isNotEmpty()) {
                    isMenuExpanded = true
                }
                .padding(horizontal = DROPDOWN_FIELD_HORIZONTAL_PADDING),
            contentAlignment = Alignment.Center
        ) {
            // Centered in the room the arrow leaves: the tiles are narrow, so the label gets all the width it can
            Text(
                text = currentLabel,
                fontSize = CONTROL_VALUE_FONT_SIZE,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DROPDOWN_LABEL_START_INSET, end = DROPDOWN_ICON_SIZE)
            )

            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Show Options Menu",
                tint = TextSecondary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(DROPDOWN_ICON_SIZE)
            )

            DropdownMenu(
                expanded = isMenuExpanded,
                onDismissRequest = { isMenuExpanded = false },
                shape = CONTROL_SHAPE,
                containerColor = StudioSurfaceElevated
            ) {
                options.forEachIndexed { index, option ->
                    val isSelected = index == currentIndex

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.name,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                                color = if (isSelected) {
                                    activeColor
                                } else {
                                    TextPrimary
                                }
                            )
                        },
                        trailingIcon = {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = activeColor,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        },
                        onClick = {
                            isMenuExpanded = false
                            onSelect(option.value)
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = TextPrimary
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun IntParameterStepper(
    currentValue: Int,
    min: Int,
    max: Int,
    defaultValue: Int,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CONTROL_CONTAINER_HEIGHT),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Decrement Button
            Box(
                modifier = Modifier
                    .size(STEPPER_BUTTON_SIZE)
                    .clip(CircleShape)
                    .background(StudioSurfaceVariant)
                    .border(1.dp, StudioPanelBorder, CircleShape)
                    .clickable(enabled = currentValue > min) {
                        if (currentValue > min) {
                            onValueChange(currentValue - 1)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = "Decrement Value",
                    tint = if (currentValue > min) {
                        TextPrimary
                    } else {
                        TextMuted
                    },
                    modifier = Modifier.size(STEPPER_ICON_SIZE)
                )
            }

            // Center Integer Value Pill (Click to reset to default)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .height(STEPPER_BUTTON_SIZE + 4.dp)
                    .clip(RoundedCornerShape(BUTTON_CORNER_RADIUS))
                    .background(activeColor.copy(alpha = ACTIVE_ALPHA))
                    .border(1.dp, activeColor.copy(alpha = BORDER_ACTIVE_ALPHA), RoundedCornerShape(BUTTON_CORNER_RADIUS))
                    .clickable {
                        onValueChange(defaultValue)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentValue.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    style = tabularTextStyle,
                    color = activeColor,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }

            // Increment Button
            Box(
                modifier = Modifier
                    .size(STEPPER_BUTTON_SIZE)
                    .clip(CircleShape)
                    .background(StudioSurfaceVariant)
                    .border(1.dp, StudioPanelBorder, CircleShape)
                    .clickable(enabled = currentValue < max) {
                        if (currentValue < max) {
                            onValueChange(currentValue + 1)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Increment Value",
                    tint = if (currentValue < max) {
                        TextPrimary
                    } else {
                        TextMuted
                    },
                    modifier = Modifier.size(STEPPER_ICON_SIZE)
                )
            }
        }
    }
}
