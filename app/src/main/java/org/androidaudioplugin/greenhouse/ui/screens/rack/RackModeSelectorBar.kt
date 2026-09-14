package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.ui.RackSlotData
import org.androidaudioplugin.greenhouse.ui.StudioRackViewMode
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceElevated
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary

private val MODE_BAR_SPACING = 6.dp
private val MODE_CHIP_CORNER_RADIUS = 12.dp
private val MODE_CHIP_PADDING_HORIZONTAL = 14.dp
private val MODE_CHIP_PADDING_VERTICAL = 8.dp
private const val MODE_SELECTED_BORDER_ALPHA = 0.8f

@Composable
fun StatusAndModeSelectorBar(
    activeSlot: RackSlotData,
    currentMode: StudioRackViewMode,
    onModeSelected: (StudioRackViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = remember(activeSlot.pluginInfo, activeSlot.hasCustomUi, activeSlot.presetCount) {
        val list = mutableListOf(StudioRackViewMode.PARAMETERS)

        if (activeSlot.hasCustomUi) {
            list.add(StudioRackViewMode.NATIVE_SURFACE)
        }

        if (activeSlot.presetCount > 1) {
            list.add(StudioRackViewMode.PRESETS)
        }

        list
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(MODE_BAR_SPACING),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(modes) { mode ->
                val isSelected = currentMode == mode
                val badgeText = if (mode == StudioRackViewMode.PRESETS && activeSlot.presetCount > 1) {
                    " (${activeSlot.presetCount})"
                } else {
                    ""
                }

                val backgroundColor = if (isSelected) {
                    StudioSurfaceElevated
                } else {
                    StudioSurface
                }

                val borderColor = if (isSelected) {
                    SproutGreen.copy(alpha = MODE_SELECTED_BORDER_ALPHA)
                } else {
                    StudioPanelBorder
                }

                val textColor = if (isSelected) {
                    TextPrimary
                } else {
                    TextSecondary
                }

                val textWeight = if (isSelected) {
                    FontWeight.Bold
                } else {
                    FontWeight.Medium
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(MODE_CHIP_CORNER_RADIUS))
                        .background(backgroundColor)
                        .border(
                            width = 1.dp,
                            color = borderColor,
                            shape = RoundedCornerShape(MODE_CHIP_CORNER_RADIUS)
                        )
                        .clickable { onModeSelected(mode) }
                        .padding(horizontal = MODE_CHIP_PADDING_HORIZONTAL, vertical = MODE_CHIP_PADDING_VERTICAL)
                ) {
                    Text(
                        text = "${mode.title}$badgeText",
                        fontSize = 11.sp,
                        fontWeight = textWeight,
                        color = textColor
                    )
                }
            }
        }
    }
}
