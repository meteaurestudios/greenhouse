package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.ui.RackSlotData
import org.androidaudioplugin.greenhouse.ui.components.GridVerticalScrollBar
import org.androidaudioplugin.greenhouse.ui.components.SCROLLBAR_HORIZONTAL_OFFSET
import org.androidaudioplugin.greenhouse.ui.components.SCROLLBAR_TRACK_PADDING
import org.androidaudioplugin.greenhouse.ui.components.SCROLLBAR_WIDTH
import org.androidaudioplugin.greenhouse.ui.theme.AccentGold
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceElevated
import org.androidaudioplugin.greenhouse.ui.theme.TextMuted
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary
import androidx.compose.ui.unit.Dp
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary

private val PRESET_ITEM_HEIGHT = 38.dp
private val PRESET_GRID_SPACING = 8.dp
private val PRESET_ADAPTIVE_MIN_WIDTH = 130.dp
private val PRESET_CORNER_RADIUS = 10.dp
private val PRESET_PADDING_HORIZONTAL = 10.dp
private val PRESET_PADDING_VERTICAL = 8.dp
private val PRESET_LOADING_SPINNER_SIZE = 20.dp
private const val PRESET_SELECTED_BORDER_ALPHA = 0.8f

@Composable
fun PluginPresetsView(
    slot: RackSlotData,
    gridState: LazyGridState,
    onPresetSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (slot.isLoadingPresets) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(PRESET_LOADING_SPINNER_SIZE),
                    color = AccentGold,
                    strokeWidth = 2.dp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Loading presets...",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }

        return
    }

    val presets = slot.presets

    if (presets.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "This plugin exposes no factory presets.",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }

        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = PRESET_ADAPTIVE_MIN_WIDTH),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(PRESET_GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(PRESET_GRID_SPACING),
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 8.dp)
        ) {
            items(
                presets.size,
                key = { idx -> "${slot.index}_${slot.pluginInfo?.pluginId ?: ""}_preset_${presets[idx].nativeIndex}" }
            ) { idx ->
                val preset = presets[idx]
                val isPresetSelected = slot.selectedPresetIndex >= 0 && preset.nativeIndex == slot.selectedPresetIndex

                val backgroundColor = if (isPresetSelected) {
                    StudioSurfaceElevated
                } else {
                    StudioSurface
                }

                val borderColor = if (isPresetSelected) {
                    SproutGreen.copy(alpha = PRESET_SELECTED_BORDER_ALPHA)
                } else {
                    StudioPanelBorder
                }

                val numberColor = if (isPresetSelected) {
                    AccentGold
                } else {
                    TextMuted
                }

                val nameColor = if (isPresetSelected) {
                    TextPrimary
                } else {
                    TextSecondary
                }

                val nameWeight = if (isPresetSelected) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Normal
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PRESET_CORNER_RADIUS))
                        .background(backgroundColor)
                        .border(
                            width = 1.dp,
                            color = borderColor,
                            shape = RoundedCornerShape(PRESET_CORNER_RADIUS)
                        )
                        .clickable {
                            onPresetSelected(preset.nativeIndex)
                        }
                        .padding(horizontal = PRESET_PADDING_HORIZONTAL, vertical = PRESET_PADDING_VERTICAL),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${idx + 1}.",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isPresetSelected) FontWeight.Bold else FontWeight.Normal,
                        color = numberColor
                    )

                    Text(
                        text = preset.name,
                        fontSize = 11.sp,
                        fontWeight = nameWeight,
                        color = nameColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        GridVerticalScrollBar(
            gridState = gridState,
            totalItems = presets.size,
            estimatedRowHeightDp = PRESET_ITEM_HEIGHT + PRESET_GRID_SPACING,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = SCROLLBAR_HORIZONTAL_OFFSET)
                .fillMaxHeight()
                .width(SCROLLBAR_WIDTH)
                .padding(vertical = SCROLLBAR_TRACK_PADDING)
        )
    }
}
