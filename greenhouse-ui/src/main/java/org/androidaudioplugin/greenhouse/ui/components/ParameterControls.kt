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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.greenhouse.ui.theme.BerryRose
import org.androidaudioplugin.greenhouse.ui.theme.StudioBackground
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface
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
private val TOGGLE_BUTTON_HEIGHT = 34.dp
private val TOGGLE_BUTTON_CORNER_RADIUS = 17.dp
private val BUTTON_CORNER_RADIUS = 8.dp
private val STEPPER_BUTTON_SIZE = 28.dp
private val STEPPER_ICON_SIZE = 14.dp
private val LED_INDICATOR_SIZE = 6.dp
private val TOGGLE_BORDER_WIDTH = 1.dp
private const val TOGGLE_BUTTON_WIDTH_FRACTION = 0.84f
private val ACTIVE_ALPHA = 0.18f
private val INACTIVE_ALPHA = 0.06f
private val BORDER_ACTIVE_ALPHA = 0.55f
private val BORDER_INACTIVE_ALPHA = 0.3f

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

    val backgroundColor = if (isOn) {
        activeColor.copy(alpha = ACTIVE_ALPHA)
    } else {
        StudioSurfaceVariant.copy(alpha = 0.5f)
    }

    val borderColor = if (isOn) {
        activeColor.copy(alpha = BORDER_ACTIVE_ALPHA)
    } else {
        StudioPanelBorder.copy(alpha = BORDER_INACTIVE_ALPHA)
    }

    val textColor = if (isOn) {
        activeColor
    } else {
        TextSecondary
    }

    val ledColor = if (isOn) {
        activeColor
    } else {
        TextMuted.copy(alpha = 0.4f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth(TOGGLE_BUTTON_WIDTH_FRACTION)
            .height(TOGGLE_BUTTON_HEIGHT)
            .clip(RoundedCornerShape(TOGGLE_BUTTON_CORNER_RADIUS))
            .background(backgroundColor)
            .border(TOGGLE_BORDER_WIDTH, borderColor, RoundedCornerShape(TOGGLE_BUTTON_CORNER_RADIUS))
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
                text = currentLabel.uppercase(),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Previous Option Button
            Box(
                modifier = Modifier
                    .size(STEPPER_BUTTON_SIZE)
                    .clip(CircleShape)
                    .background(StudioSurfaceVariant)
                    .border(1.dp, StudioPanelBorder, CircleShape)
                    .clickable(enabled = options.isNotEmpty()) {
                        if (options.isNotEmpty()) {
                            val prevIndex = if (currentIndex > 0) {
                                currentIndex - 1
                            } else {
                                options.size - 1
                            }
                            onSelect(options[prevIndex].value)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Previous Option",
                    tint = TextSecondary,
                    modifier = Modifier.size(STEPPER_ICON_SIZE)
                )
            }

            // Center Option Pill (Click to open dropdown menu)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .height(STEPPER_BUTTON_SIZE + 4.dp)
                    .clip(RoundedCornerShape(BUTTON_CORNER_RADIUS))
                    .background(activeColor.copy(alpha = ACTIVE_ALPHA))
                    .border(1.dp, activeColor.copy(alpha = BORDER_ACTIVE_ALPHA), RoundedCornerShape(BUTTON_CORNER_RADIUS))
                    .clickable {
                        isMenuExpanded = true
                    }
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = currentLabel,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = activeColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(2.dp))

                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Show Options Menu",
                        tint = activeColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp)
                    )
                }

                DropdownMenu(
                    expanded = isMenuExpanded,
                    onDismissRequest = { isMenuExpanded = false },
                    modifier = Modifier
                        .background(StudioSurface)
                        .border(1.dp, StudioPanelBorder, RoundedCornerShape(BUTTON_CORNER_RADIUS))
                ) {
                    options.forEachIndexed { index, option ->
                        val isSelected = index == currentIndex

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option.name,
                                    fontSize = 11.sp,
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

            // Next Option Button
            Box(
                modifier = Modifier
                    .size(STEPPER_BUTTON_SIZE)
                    .clip(CircleShape)
                    .background(StudioSurfaceVariant)
                    .border(1.dp, StudioPanelBorder, CircleShape)
                    .clickable(enabled = options.isNotEmpty()) {
                        if (options.isNotEmpty()) {
                            val nextIndex = if (currentIndex < options.size - 1) {
                                currentIndex + 1
                            } else {
                                0
                            }
                            onSelect(options[nextIndex].value)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Next Option",
                    tint = TextSecondary,
                    modifier = Modifier.size(STEPPER_ICON_SIZE)
                )
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
                    fontFamily = FontFamily.Monospace,
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
