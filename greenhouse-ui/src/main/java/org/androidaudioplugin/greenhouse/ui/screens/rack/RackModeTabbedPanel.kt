package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.ui.RackSlotData
import org.androidaudioplugin.greenhouse.ui.StudioRackViewMode
import org.androidaudioplugin.greenhouse.ui.components.CONTROL_LABEL_FONT_SIZE
import org.androidaudioplugin.greenhouse.ui.components.PANEL_BORDER_WIDTH
import org.androidaudioplugin.greenhouse.ui.components.PANEL_CORNER_RADIUS
import org.androidaudioplugin.greenhouse.ui.components.toggleClickable
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary

private val TAB_HEIGHT = 40.dp
private val TAB_HORIZONTAL_PADDING = 12.dp
private val TAB_ICON_SIZE = 16.dp
private val TAB_PRESET_ICON_SIZE = 15.dp
private val TAB_CONTENT_SPACING = 6.dp
private val TAB_BADGE_HORIZONTAL_PADDING = 6.dp
private val TAB_BADGE_VERTICAL_PADDING = 1.dp
private val TAB_BADGE_FONT_SIZE = 10.sp
// The selected tab: its top corners, and the curves joining its sides to the panel's top edge
private val TAB_CORNER_RADIUS = 12.dp
private val TAB_JOIN_RADIUS = 10.dp
private val PANEL_INNER_PADDING = 12.dp
private const val TAB_ACTIVE_BADGE_BG_ALPHA = 0.2f
private const val TAB_INACTIVE_BADGE_BG_ALPHA = 0.6f
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

/**
 * The main panel, with the tabs of the active slot's views above it, like folder tabs: the
 * selected one is part of the panel (its fill and outline, joined to it by curves), the others sit
 * plain above it. Without tabs (no plugin, loading, or the plugin UI full screen), the panel alone.
 * [content] fills the panel, inside its padding.
 */
@Composable
fun ModeTabbedPanel(
    activeSlot: RackSlotData,
    currentMode: StudioRackViewMode,
    showTabs: Boolean,
    onModeSelected: (StudioRackViewMode) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
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
    val hasTabs = showTabs && activeSlot.pluginInfo != null && !activeSlot.isLoading

    // The selected tab, relative to the panel: the outline is drawn around it
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }
    var selectedTabInRoot by remember { mutableStateOf<Rect?>(null) }

    Column(
        modifier = modifier
            .onGloballyPositioned { panelOrigin = it.positionInRoot() }
            // The shape is rebuilt only when the size or the selected tab changes, not on every
            // redraw of the panel's content
            .drawWithCache {
                // The tab row is above the panel even before the selected tab is placed
                val panelTop = if (hasTabs) {
                    TAB_HEIGHT.toPx()
                } else {
                    0f
                }
                val selectedTab = if (hasTabs && currentMode in modes) {
                    selectedTabInRoot?.translate(-panelOrigin)
                } else {
                    null
                }
                val shape = tabbedPanelShape(selectedTab, panelTop)
                val stroke = Stroke(width = PANEL_BORDER_WIDTH.toPx())

                onDrawBehind {
                    drawPath(shape, StudioSurface)
                    drawPath(shape, StudioPanelBorder, style = stroke)
                }
            }
    ) {
        if (hasTabs) {
            // Centered as a group; wider than the panel, they scroll instead
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TAB_HEIGHT),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    modes.forEach { mode ->
                        val isSelected = currentMode == mode

                        ModeTab(
                            mode = mode,
                            isSelected = isSelected,
                            presetCount = activeSlot.presetCount,
                            onClick = { onModeSelected(mode) },
                            modifier = if (isSelected) {
                                Modifier.onGloballyPositioned { selectedTabInRoot = it.boundsInRoot() }
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(PANEL_INNER_PADDING),
            content = content
        )
    }
}

/**
 * The panel below [panelTop], with [tab] (if any) rising from its top edge as one shape: the tab's
 * top corners rounded, curves where its sides meet the panel.
 */
private fun CacheDrawScope.tabbedPanelShape(tab: Rect?, panelTop: Float): Path {
    val stroke = PANEL_BORDER_WIDTH.toPx()
    // The outline is drawn inside the shape's edges, as a border would be
    val inset = stroke / 2
    val panelRadius = PANEL_CORNER_RADIUS.toPx()
    var shape = Path().apply {
        addRoundRect(
            RoundRect(
                left = inset,
                top = panelTop + inset,
                right = size.width - inset,
                bottom = size.height - inset,
                cornerRadius = CornerRadius(panelRadius, panelRadius)
            )
        )
    }

    if (tab != null) {
        val tabRadius = TAB_CORNER_RADIUS.toPx()
        val edge = panelTop + inset
        // A curve fits between the tab and the panel's rounded corner: smaller near a corner
        val roomLeft = tab.left - inset - panelRadius
        val roomRight = size.width - inset - panelRadius - tab.right
        val joinLeft = TAB_JOIN_RADIUS.toPx().coerceAtMost(roomLeft).coerceAtLeast(0f)
        val joinRight = TAB_JOIN_RADIUS.toPx().coerceAtMost(roomRight).coerceAtLeast(0f)
        val tabShape = Path().apply {
            addRoundRect(
                RoundRect(
                    left = tab.left,
                    top = inset,
                    right = tab.right,
                    // Into the panel, so no outline is left across the tab's foot
                    bottom = edge + panelRadius,
                    topLeftCornerRadius = CornerRadius(tabRadius, tabRadius),
                    topRightCornerRadius = CornerRadius(tabRadius, tabRadius),
                    bottomRightCornerRadius = CornerRadius.Zero,
                    bottomLeftCornerRadius = CornerRadius.Zero
                )
            )
        }

        shape = Path.combine(PathOperation.Union, shape, tabShape)
        shape = Path.combine(PathOperation.Union, shape, joinCurve(tab.left - joinLeft, edge, joinLeft, isOnLeft = true))
        shape = Path.combine(PathOperation.Union, shape, joinCurve(tab.right, edge, joinRight, isOnLeft = false))
    }

    return shape
}

/**
 * The filled inner curve between a side of the tab and the panel's top edge [edge]: a square of
 * [radius] starting at [left], less the quarter circle away from the tab.
 */
private fun joinCurve(left: Float, edge: Float, radius: Float, isOnLeft: Boolean): Path {
    val square = Path().apply {
        addRect(Rect(left, edge - radius, left + radius, edge))
    }
    val centerX = if (isOnLeft) {
        left
    } else {
        left + radius
    }
    val circle = Path().apply {
        addOval(Rect(center = Offset(centerX, edge - radius), radius = radius))
    }

    return Path.combine(PathOperation.Difference, square, circle)
}

@Composable
private fun ModeTab(
    mode: StudioRackViewMode,
    isSelected: Boolean,
    presetCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) {
            TextPrimary
        } else {
            TextSecondary
        },
        animationSpec = tween(durationMillis = TAB_ANIMATION_DURATION_MS),
        label = "tabContent"
    )

    val iconColor by animateColorAsState(
        targetValue = if (isSelected) {
            SproutGreen
        } else {
            TextSecondary
        },
        animationSpec = tween(durationMillis = TAB_ANIMATION_DURATION_MS),
        label = "tabIcon"
    )

    val iconSize = if (mode == StudioRackViewMode.PRESETS) {
        TAB_PRESET_ICON_SIZE
    } else {
        TAB_ICON_SIZE
    }

    Row(
        modifier = modifier
            .fillMaxHeight()
            // No ripple: the selected tab's shape moves to it as it is tapped
            .toggleClickable(onClick = onClick)
            .padding(horizontal = TAB_HORIZONTAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TAB_CONTENT_SPACING, Alignment.CenterHorizontally)
    ) {
        Icon(
            imageVector = getModeIcon(mode),
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )

        Text(
            text = mode.title,
            fontSize = CONTROL_LABEL_FONT_SIZE,
            fontWeight = if (isSelected) {
                FontWeight.SemiBold
            } else {
                FontWeight.Medium
            },
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )

        if (mode == StudioRackViewMode.PRESETS && presetCount > 1) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (isSelected) {
                            SproutGreen.copy(alpha = TAB_ACTIVE_BADGE_BG_ALPHA)
                        } else {
                            StudioPanelBorder.copy(alpha = TAB_INACTIVE_BADGE_BG_ALPHA)
                        }
                    )
                    .padding(
                        horizontal = TAB_BADGE_HORIZONTAL_PADDING,
                        vertical = TAB_BADGE_VERTICAL_PADDING
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = presetCount.toString(),
                    fontSize = TAB_BADGE_FONT_SIZE,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) {
                        SproutGreen
                    } else {
                        TextSecondary
                    },
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
