package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

private val TAB_HEIGHT = 36.dp
private val BAR_CONTAINER_PADDING = 4.dp
private val BAR_CONTAINER_HEIGHT = 44.dp
private val BAR_CONTAINER_BORDER_WIDTH = 1.dp
private val TAB_SPACING = 3.dp
private val TAB_HORIZONTAL_PADDING = 11.dp
private val TAB_ICON_SIZE = 15.dp
private val TAB_PRESET_ICON_SIZE = 14.dp
private val TAB_ICON_LABEL_SPACING = 5.dp
private val TAB_BADGE_SPACING = 4.dp
private val TAB_BADGE_HORIZONTAL_PADDING = 5.dp
private val TAB_BADGE_VERTICAL_PADDING = 1.dp
private val TAB_BADGE_FONT_SIZE = 9.5.sp
private val TAB_LABEL_FONT_SIZE = 11.5.sp
private val TAB_BORDER_WIDTH = 1.dp
private const val TAB_ACTIVE_BORDER_ALPHA = 0.5f
private const val TAB_ACTIVE_BADGE_BG_ALPHA = 0.2f
private const val TAB_INACTIVE_BADGE_BG_ALPHA = 0.4f
private const val TAB_ANIMATION_DURATION_MS = 180

private fun getModeIcon(mode: StudioRackViewMode): ImageVector {
    return when (mode) {
        StudioRackViewMode.PARAMETERS -> {
            Icons.Default.Tune
        }

        StudioRackViewMode.NATIVE_SURFACE -> {
            Icons.Default.WebAsset
        }

        StudioRackViewMode.PRESETS -> {
            Icons.Default.Bookmark
        }
    }
}

@Composable
fun StatusAndModeSelectorBar(
    activeSlot: RackSlotData,
    currentMode: StudioRackViewMode,
    onModeSelected: (StudioRackViewMode) -> Unit,
    modifier: Modifier = Modifier
) {

    if (activeSlot.pluginInfo == null || activeSlot.isLoading) {
        return
    }

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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .height(BAR_CONTAINER_HEIGHT)
                .clip(CircleShape)
                .background(StudioSurface)
                .border(
                    width = BAR_CONTAINER_BORDER_WIDTH,
                    color = StudioPanelBorder,
                    shape = CircleShape
                )
                .padding(BAR_CONTAINER_PADDING),
            horizontalArrangement = Arrangement.spacedBy(TAB_SPACING),
            verticalAlignment = Alignment.CenterVertically
        ) {
            modes.forEach { mode ->
                val isSelected = currentMode == mode

                val targetBgColor = if (isSelected) {
                    StudioSurfaceElevated
                } else {
                    Color.Transparent
                }

                val animatedBgColor by animateColorAsState(
                    targetValue = targetBgColor,
                    animationSpec = tween(durationMillis = TAB_ANIMATION_DURATION_MS),
                    label = "tabBackground"
                )

                val targetBorderColor = if (isSelected) {
                    SproutGreen.copy(alpha = TAB_ACTIVE_BORDER_ALPHA)
                } else {
                    Color.Transparent
                }

                val animatedBorderColor by animateColorAsState(
                    targetValue = targetBorderColor,
                    animationSpec = tween(durationMillis = TAB_ANIMATION_DURATION_MS),
                    label = "tabBorder"
                )

                val targetIconColor = if (isSelected) {
                    SproutGreen
                } else {
                    TextSecondary
                }

                val animatedIconColor by animateColorAsState(
                    targetValue = targetIconColor,
                    animationSpec = tween(durationMillis = TAB_ANIMATION_DURATION_MS),
                    label = "tabIcon"
                )

                val targetTextColor = if (isSelected) {
                    TextPrimary
                } else {
                    TextSecondary
                }

                val animatedTextColor by animateColorAsState(
                    targetValue = targetTextColor,
                    animationSpec = tween(durationMillis = TAB_ANIMATION_DURATION_MS),
                    label = "tabText"
                )

                val textWeight = if (isSelected) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Medium
                }

                val iconSize = if (mode == StudioRackViewMode.PRESETS) {
                    TAB_PRESET_ICON_SIZE
                } else {
                    TAB_ICON_SIZE
                }

                Box(
                    modifier = Modifier
                        .height(TAB_HEIGHT)
                        .clip(CircleShape)
                        .background(animatedBgColor)
                        .border(
                            width = TAB_BORDER_WIDTH,
                            color = animatedBorderColor,
                            shape = CircleShape
                        )
                        .clickable { onModeSelected(mode) }
                        .padding(horizontal = TAB_HORIZONTAL_PADDING),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = getModeIcon(mode),
                            contentDescription = null,
                            tint = animatedIconColor,
                            modifier = Modifier.size(iconSize)
                        )

                        Spacer(modifier = Modifier.width(TAB_ICON_LABEL_SPACING))

                        Text(
                            text = mode.title,
                            fontSize = TAB_LABEL_FONT_SIZE,
                            fontWeight = textWeight,
                            color = animatedTextColor,
                            maxLines = 1,
                            softWrap = false
                        )

                        if (mode == StudioRackViewMode.PRESETS && activeSlot.presetCount > 1) {
                            Spacer(modifier = Modifier.width(TAB_BADGE_SPACING))

                            val badgeBg = if (isSelected) {
                                SproutGreen.copy(alpha = TAB_ACTIVE_BADGE_BG_ALPHA)
                            } else {
                                StudioPanelBorder.copy(alpha = TAB_INACTIVE_BADGE_BG_ALPHA)
                            }

                            val badgeTextColor = if (isSelected) {
                                SproutGreen
                            } else {
                                TextSecondary
                            }

                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(badgeBg)
                                    .padding(
                                        horizontal = TAB_BADGE_HORIZONTAL_PADDING,
                                        vertical = TAB_BADGE_VERTICAL_PADDING
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = activeSlot.presetCount.toString(),
                                    fontSize = TAB_BADGE_FONT_SIZE,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeTextColor,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
