package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.ui.RackSlotData
import org.androidaudioplugin.greenhouse.ui.SlotLevel
import org.androidaudioplugin.greenhouse.ui.components.SlotStereoLevelMeter
import org.androidaudioplugin.greenhouse.ui.theme.BlossomCoral
import org.androidaudioplugin.greenhouse.ui.theme.DangerRed
import org.androidaudioplugin.greenhouse.ui.theme.PeriwinkleBlue
import org.androidaudioplugin.greenhouse.ui.theme.SignalGreen
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceElevated
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceVariant
import org.androidaudioplugin.greenhouse.ui.theme.TextMuted
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary

private val SIGNAL_CONNECTOR_WIDTH = 10.dp
private val SIGNAL_CONNECTOR_HEIGHT = 104.dp
private val SLOT_CARD_HEIGHT = 104.dp
private val SLOT_CARD_CORNER_RADIUS = 12.dp
private val SLOT_CARD_PADDING = 9.dp

@Composable
fun SignalFlowConnector(
    isActive: Boolean,
    color: Color = SproutGreen,
    modifier: Modifier = Modifier
) {
    val arrowColor = if (isActive) {
        color
    } else {
        StudioPanelBorder
    }

    Box(
        modifier = modifier
            .width(SIGNAL_CONNECTOR_WIDTH)
            .height(SIGNAL_CONNECTOR_HEIGHT),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(3.5.dp)
                    .clip(CircleShape)
                    .background(arrowColor)
            )

            Spacer(modifier = Modifier.height(3.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Signal Flow",
                tint = arrowColor,
                modifier = Modifier.size(12.dp)
            )

            Spacer(modifier = Modifier.height(3.dp))

            Box(
                modifier = Modifier
                    .size(3.5.dp)
                    .clip(CircleShape)
                    .background(arrowColor)
            )
        }
    }
}

@Composable
fun SignalRackHeader(
    slots: List<RackSlotData>,
    activeSlotIndex: Int,
    isProcessing: Boolean,
    slotCpuLoadsProvider: () -> List<Float>,
    slotLevelsProvider: () -> List<SlotLevel>,
    onSelectSlot: (Int) -> Unit,
    onAddPlugin: (Int) -> Unit,
    onToggleBypass: (Int) -> Unit,
    onUnloadSlot: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val slotCpuLoads = slotCpuLoadsProvider()
    val slotLevels = slotLevelsProvider()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        slots.forEachIndexed { index, slot ->
            val isSelected = slot.index == activeSlotIndex
            val isLoaded = slot.pluginInfo != null
            val isLoading = slot.isLoading
            val slotLevel = slotLevels.getOrNull(slot.index) ?: SlotLevel()

            val badgeColor = if (slot.index == 0) {
                BlossomCoral
            } else {
                PeriwinkleBlue
            }

            val borderColor = when {
                isSelected -> badgeColor
                isLoaded -> SproutGreen.copy(alpha = 0.5f)
                else -> StudioPanelBorder
            }

            val borderWidth = if (isSelected) {
                1.5.dp
            } else {
                1.dp
            }

            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(SLOT_CARD_HEIGHT)
                    .border(borderWidth, borderColor, RoundedCornerShape(SLOT_CARD_CORNER_RADIUS))
                    .clip(RoundedCornerShape(SLOT_CARD_CORNER_RADIUS))
                    .clickable { onSelectSlot(slot.index) },
                shape = RoundedCornerShape(SLOT_CARD_CORNER_RADIUS),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        StudioSurfaceVariant
                    } else {
                        StudioSurface
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(SLOT_CARD_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            // Top Header Row: Bypass power icon or Mini Loader with Slot Type Capsule Label Box next to it
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {

                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = badgeColor,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    // Bypass power icon (always shown)
                                    Icon(
                                        imageVector = Icons.Default.PowerSettingsNew,
                                        contentDescription = "Bypassed",
                                        tint = when {
                                            slot.isBypassed -> DangerRed
                                            isLoaded -> SignalGreen
                                            else -> TextMuted.copy(alpha = 0.4f)
                                        },
                                        modifier = Modifier
                                            .size(15.dp)
                                            .clip(CircleShape)
                                            .clickable(
                                                enabled = isLoaded,
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                onToggleBypass(slot.index)
                                            }
                                    )
                                }

                                val slotTag = when (slot.index) {
                                    0 -> "01 · INST"
                                    1 -> "02 · FX 1"
                                    else -> "03 · FX 2"
                                }

                                // Slot Type 2D Flat Industrial Badge
                                Box(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .border(1.dp, badgeColor.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(badgeColor.copy(alpha = 0.12f))
                                        .padding(horizontal = 5.dp, vertical = 1.5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = slotTag,
                                        fontSize = 8.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.6.sp,
                                        color = badgeColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            if (isLoading) {
                                Text(
                                    text = slot.loadingPluginName ?: "Loading...",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = "LOADING...",
                                    fontSize = 8.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = TextMuted,
                                    letterSpacing = 0.5.sp
                                )
                            } else if (isLoaded) {
                                // Plugin Name with Close Cross Button next to it
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = slot.pluginInfo?.displayName ?: "Empty Slot",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )

                                    Spacer(modifier = Modifier.width(4.dp))

                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(StudioSurfaceElevated)
                                            .border(1.dp, StudioPanelBorder, RoundedCornerShape(4.dp))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { onUnloadSlot(slot.index) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove Plugin",
                                            tint = TextPrimary,
                                            modifier = Modifier.size(11.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(2.dp))

                                val slotCpu = slotCpuLoads.getOrNull(slot.index) ?: 0f
                                val statusText = when {
                                    slot.isBypassed -> "BYPASSED"
                                    isProcessing -> "DSP ${slotCpu.toInt()}%"
                                    else -> "ACTIVE"
                                }

                                Text(
                                    text = statusText,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.5.sp,
                                    color = if (slot.isBypassed) DangerRed else TextMuted
                                )
                            } else {
                                Text(
                                    text = "Empty Slot",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(28.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(StudioSurfaceElevated)
                                        .border(1.dp, StudioPanelBorder, RoundedCornerShape(6.dp))
                                        .clickable { onAddPlugin(slot.index) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+ ADD",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = SproutGreen,
                                        letterSpacing = 0.8.sp
                                    )
                                }
                            }
                        }

                        // Stereo VU / Level Meter on the right of the slot card (only when a plugin is loaded)
                        if (isLoaded) {
                            SlotStereoLevelMeter(
                                levelLeft = slotLevel.left,
                                levelRight = slotLevel.right,
                                isBypassed = slot.isBypassed,
                                isProcessing = isProcessing,
                                meterWidth = 12.dp,
                                meterHeight = 86.dp
                            )
                        }
                    }
                }

            if (index < slots.lastIndex) {
                val isUpstreamActive = isProcessing && (slots.firstOrNull()?.let { slot0 ->
                    slot0.pluginInfo != null && !slot0.isBypassed
                } ?: false)
                val hasDownstreamLoaded = slots.drop(index + 1).any { nextSlot ->
                    nextSlot.pluginInfo != null
                }
                val isCurrentSlotActive = isProcessing && isLoaded && !slot.isBypassed
                val isFlowActive = isCurrentSlotActive || (isUpstreamActive && hasDownstreamLoaded)

                val connectorColor = if (slot.index == 0) {
                    BlossomCoral
                } else {
                    PeriwinkleBlue
                }

                SignalFlowConnector(
                    isActive = isFlowActive,
                    color = connectorColor
                )
            }
        }
    }
}
