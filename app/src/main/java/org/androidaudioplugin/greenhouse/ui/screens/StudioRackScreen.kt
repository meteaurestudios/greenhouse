package org.androidaudioplugin.greenhouse.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.RackSlotData
import org.androidaudioplugin.greenhouse.ui.SlotLevel
import org.androidaudioplugin.greenhouse.ui.StudioRackViewMode
import org.androidaudioplugin.greenhouse.ui.components.*
import org.androidaudioplugin.greenhouse.ui.theme.*
import org.androidaudioplugin.hosting.GuiHelper
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioRackScreen(
    viewModel: HostViewModel,
    onNavigateToBrowser: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val currentSlotIndex = viewModel.activeSlotIndex
    val activeSlot = viewModel.slots[currentSlotIndex]
    val activePlugin = activeSlot.pluginInfo
    var isRackFolded by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = isRackFolded) {
        isRackFolded = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(12.dp)
    ) {
        if (!isRackFolded) {
            // Master Control Banner
            MasterControlBanner(
                slots = viewModel.slots,
                isProcessing = viewModel.isProcessing,
                totalCpuLoad = viewModel.totalCpuLoad,
                onToggleProcessing = { viewModel.toggleAudioPlayback() },
                onOpenSettings = onNavigateToSettings
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Multi-Slot Audio Signal Chain Rack Header
            SignalRackHeader(
                slots = viewModel.slots,
                activeSlotIndex = currentSlotIndex,
                isProcessing = viewModel.isProcessing,
                slotCpuLoads = viewModel.slotCpuLoads,
                slotLevels = viewModel.slotLevels,
                onSelectSlot = { slotIdx ->
                    viewModel.selectActiveSlot(slotIdx)
                },
                onAddPlugin = { slotIndex ->
                    viewModel.openBrowserForSlot(slotIndex)
                    onNavigateToBrowser()
                },
                onToggleBypass = { slotIdx ->
                    viewModel.toggleSlotBypass(slotIdx)
                },
                onUnloadSlot = { slotIdx ->
                    viewModel.unloadSlot(slotIdx)
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // View Mode Selector Bar for Active Slot
            StatusAndModeSelectorBar(
                activeSlot = activeSlot,
                currentMode = viewModel.currentViewMode,
                onModeSelected = { viewModel.updateViewMode(it) }
            )

            Spacer(modifier = Modifier.height(10.dp))
        }

        // Dynamic Main Panel (Parameter Controls / Native Embedded GUI / Presets / Ports Spec)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(StudioSurface)
                .border(1.dp, StudioPanelBorder, RoundedCornerShape(20.dp))
                .padding(12.dp)
        ) {
            key(activeSlot.index, activePlugin?.pluginId, activePlugin?.parameters?.size, activeSlot.isLoading) {

                if (activeSlot.isLoading) {
                    PluginLoadingView(
                        slot = activeSlot,
                        pluginName = activeSlot.loadingPluginName
                    )
                } else if (activePlugin == null) {
                    NoPluginInSlotView(
                        slot = activeSlot,
                        isProcessing = viewModel.isProcessing,
                        onOpenBrowser = {
                            viewModel.openBrowserForSlot(activeSlot.index)
                            onNavigateToBrowser()
                        }
                    )
                } else {
                    when (viewModel.currentViewMode) {
                        StudioRackViewMode.PARAMETERS -> {
                            ParameterControlRack(
                                slotIndex = activeSlot.index,
                                pluginId = activePlugin.pluginId ?: "",
                                parameters = activePlugin.parameters,
                                parameterValues = viewModel.slotParameterValues[activeSlot.index],
                                gridState = viewModel.slotParameterGridStates[activeSlot.index],
                                onValueChange = { param, valDouble ->
                                    viewModel.setParameterValue(activeSlot.index, param, valDouble)
                                }
                            )
                        }
                        StudioRackViewMode.NATIVE_SURFACE -> {
                            NativePluginSurfaceContainer(
                                viewModel = viewModel,
                                slot = activeSlot,
                                plugin = activePlugin,
                                isRackFolded = isRackFolded,
                                onToggleFoldRack = { isRackFolded = !isRackFolded }
                            )
                        }
                        StudioRackViewMode.PRESETS -> {
                            PluginPresetsView(
                                slot = activeSlot,
                                gridState = viewModel.slotPresetGridStates[activeSlot.index],
                                onPresetSelected = { idx ->
                                    viewModel.setPreset(activeSlot.index, idx)
                                }
                            )
                        }
                    }
                }

            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // On-screen Live Interactive MIDI Keyboard (Routes to Slot 0: Instrument)
        MidiKeyboardSection(viewModel = viewModel)
    }
}

private const val CPU_HIGH_THRESHOLD_PERCENT = 80f
private const val CPU_MEDIUM_THRESHOLD_PERCENT = 50f
private val DSP_PILL_PADDING_HORIZONTAL = 10.dp
private val DSP_PILL_PADDING_VERTICAL = 5.dp
private val DSP_PILL_SPACING = 6.dp
private val DSP_LED_SIZE = 6.dp
private val DSP_METER_BAR_WIDTH = 32.dp
private val DSP_METER_BAR_HEIGHT = 5.dp
private val DSP_PERCENT_TEXT_WIDTH = 28.dp

@Composable
private fun DspCpuMeter(
    cpuPercent: Float,
    isProcessing: Boolean,
    onToggleProcessing: () -> Unit
) {
    val meterColor = when {
        !isProcessing -> TextMuted
        cpuPercent > CPU_HIGH_THRESHOLD_PERCENT -> DangerRed
        cpuPercent > CPU_MEDIUM_THRESHOLD_PERCENT -> WarningOrange
        else -> SproutGreen
    }

    val backgroundColor = if (isProcessing) {
        StudioSurfaceElevated
    } else {
        DangerRed.copy(alpha = 0.08f)
    }

    val borderColor = if (isProcessing) {
        StudioPanelBorder
    } else {
        DangerRed.copy(alpha = 0.4f)
    }

    val ledColor = if (isProcessing) {
        SproutGreen
    } else {
        DangerRed
    }

    val labelColor = if (isProcessing) {
        SproutGreen
    } else {
        TextMuted
    }

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = CircleShape
            )
            .clickable { onToggleProcessing() }
            .padding(horizontal = DSP_PILL_PADDING_HORIZONTAL, vertical = DSP_PILL_PADDING_VERTICAL),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DSP_PILL_SPACING)
    ) {
        Box(
            modifier = Modifier
                .size(DSP_LED_SIZE)
                .clip(CircleShape)
                .background(ledColor)
        )

        Text(
            text = "DSP",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = labelColor
        )

        Box(
            modifier = Modifier
                .width(DSP_METER_BAR_WIDTH)
                .height(DSP_METER_BAR_HEIGHT)
                .clip(CircleShape)
                .background(StudioBackground)
        ) {
            val fillFraction = if (isProcessing) {
                (cpuPercent / 100f).coerceIn(0f, 1f)
            } else {
                0f
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fillFraction)
                    .clip(CircleShape)
                    .background(meterColor)
            )
        }

        val dspValueText = if (isProcessing) {
            "${cpuPercent.toInt()}%"
        } else {
            "OFF"
        }

        Text(
            text = dspValueText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.width(DSP_PERCENT_TEXT_WIDTH),
            maxLines = 1,
            color = meterColor
        )
    }
}

private val BANNER_LOGO_SIZE = 28.dp
private val BANNER_LOGO_ICON_SIZE = 16.dp
private val BANNER_SETTINGS_BUTTON_SIZE = 30.dp
private val BANNER_SETTINGS_ICON_SIZE = 16.dp
private val BANNER_CORNER_RADIUS = 20.dp
private val BANNER_SPACING = 10.dp

@Composable
private fun MasterControlBanner(
    slots: List<RackSlotData>,
    isProcessing: Boolean,
    totalCpuLoad: Float,
    onToggleProcessing: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BANNER_CORNER_RADIUS))
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(BANNER_CORNER_RADIUS)),
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Minimalist Botanical Emblem & Brand Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BANNER_SPACING)
                ) {
                    Box(
                        modifier = Modifier
                            .size(BANNER_LOGO_SIZE)
                            .clip(CircleShape)
                            .background(StudioSurfaceElevated)
                            .border(1.dp, SproutGreen.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Spa,
                            contentDescription = "Greenhouse Emblem",
                            tint = SproutGreen,
                            modifier = Modifier.size(BANNER_LOGO_ICON_SIZE)
                        )
                    }

                    Text(
                        text = "Greenhouse",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary,
                        letterSpacing = 0.4.sp
                    )
                }

                // Right: DSP CPU Meter & Settings Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DspCpuMeter(
                        cpuPercent = totalCpuLoad,
                        isProcessing = isProcessing,
                        onToggleProcessing = onToggleProcessing
                    )

                    Box(
                        modifier = Modifier
                            .size(BANNER_SETTINGS_BUTTON_SIZE)
                            .clip(CircleShape)
                            .background(StudioSurfaceElevated)
                            .border(1.dp, StudioPanelBorder, CircleShape)
                            .clickable { onOpenSettings() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Audio Engine Settings & Diagnostics",
                            tint = TextSecondary,
                            modifier = Modifier.size(BANNER_SETTINGS_ICON_SIZE)
                        )
                    }
                }
            }
        }
    }
}

private val SIGNAL_CONNECTOR_WIDTH = 10.dp
private val SIGNAL_CONNECTOR_HEIGHT = 104.dp
private val SLOT_CARD_HEIGHT = 104.dp
private val SLOT_CARD_CORNER_RADIUS = 12.dp
private val SLOT_TOP_ACCENT_BAR_HEIGHT = 2.5.dp

@Composable
private fun SignalFlowConnector(
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
private fun SignalRackHeader(
    slots: List<RackSlotData>,
    activeSlotIndex: Int,
    isProcessing: Boolean,
    slotCpuLoads: List<Float>,
    slotLevels: List<SlotLevel>,
    onSelectSlot: (Int) -> Unit,
    onAddPlugin: (Int) -> Unit,
    onToggleBypass: (Int) -> Unit,
    onUnloadSlot: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(SLOT_TOP_ACCENT_BAR_HEIGHT)
                                .background(badgeColor)
                                .align(Alignment.TopCenter)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = 9.dp,
                                end = 9.dp,
                                top = if (isSelected) 10.dp else 9.dp,
                                bottom = 9.dp
                            ),
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
            }

            if (index < slots.lastIndex) {
                val isFlowActive = isProcessing && isLoaded && !slot.isBypassed
                SignalFlowConnector(
                    isActive = isFlowActive,
                    color = if (slot.index == 0) BlossomCoral else PeriwinkleBlue
                )
            }
        }
    }
}

@Composable
private fun StatusAndModeSelectorBar(
    activeSlot: RackSlotData,
    currentMode: StudioRackViewMode,
    onModeSelected: (StudioRackViewMode) -> Unit
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
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(modes) { mode ->
                val isSelected = currentMode == mode
                val badgeText = if (mode == StudioRackViewMode.PRESETS && activeSlot.presetCount > 1) {
                    " (${activeSlot.presetCount})"
                } else {
                    ""
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) StudioSurfaceElevated else StudioSurface)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) SproutGreen.copy(alpha = 0.8f) else StudioPanelBorder,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onModeSelected(mode) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "${mode.title}$badgeText",
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) TextPrimary else TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginPresetsView(
    slot: RackSlotData,
    gridState: LazyGridState,
    onPresetSelected: (Int) -> Unit
) {
    if (slot.isLoadingPresets) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
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
            modifier = Modifier.fillMaxSize(),
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

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 130.dp),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(presets.size, key = { idx -> "${slot.index}_${slot.pluginInfo?.pluginId ?: ""}_preset_${presets[idx].nativeIndex}" }) { idx ->
            val preset = presets[idx]
            val isPresetSelected = slot.selectedPresetIndex >= 0 && preset.nativeIndex == slot.selectedPresetIndex

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isPresetSelected) StudioSurfaceElevated else StudioSurface)
                    .border(
                        width = 1.dp,
                        color = if (isPresetSelected) SproutGreen.copy(alpha = 0.8f) else StudioPanelBorder,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable {
                        onPresetSelected(preset.nativeIndex)
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "${idx + 1}.",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isPresetSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isPresetSelected) AccentGold else TextMuted
                )

                Text(
                    text = preset.name,
                    fontSize = 11.sp,
                    fontWeight = if (isPresetSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isPresetSelected) TextPrimary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ParameterControlRack(
    slotIndex: Int,
    pluginId: String,
    parameters: List<ParameterInformation>,
    parameterValues: Map<Int, Double>,
    gridState: LazyGridState,
    onValueChange: (ParameterInformation, Double) -> Unit
) {
    if (parameters.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "This plugin exposes no adjustable parameters.",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(parameters, key = { param -> "${slotIndex}_${pluginId}_${param.id}" }) { param ->
                val currentValue = parameterValues[param.id] ?: param.defaultValue
                ParameterCard(
                    parameter = param,
                    value = currentValue,
                    slotIndex = slotIndex,
                    onValueChange = { newValue -> onValueChange(param, newValue) }
                )
            }
        }
    }
}

@Composable
private fun NativeSurfaceZoomToolbar(
    isFitMode: Boolean,
    displayedScale: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    showFullscreenButton: Boolean,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(StudioSurfaceVariant.copy(alpha = 0.95f))
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Zoom Out (-) Button (Disabled when in FIT mode)
        val canZoomOut = !isFitMode
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (canZoomOut) StudioSurfaceElevated else StudioSurface)
                .clickable(
                    enabled = canZoomOut,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onZoomOut() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Zoom Out",
                tint = if (canZoomOut) TextPrimary else TextMuted.copy(alpha = 0.35f),
                modifier = Modifier.size(14.dp)
            )
        }

        // Zoom Percentage readout
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isFitMode) StudioSurfaceElevated else Color.Transparent)
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            val zoomPercent = "${(displayedScale * 100).toInt()}%"
            Text(
                text = zoomPercent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (isFitMode) NeonCyan else AccentGold
            )
        }

        // Zoom In (+) Button
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(StudioSurfaceElevated)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onZoomIn() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Zoom In",
                tint = TextPrimary,
                modifier = Modifier.size(14.dp)
            )
        }

        if (showFullscreenButton) {
            Spacer(modifier = Modifier.width(2.dp))

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(StudioSurfaceElevated)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onToggleFullscreen() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = if (isFullscreen) "Exit Fullscreen" else "Fullscreen",
                    tint = TextPrimary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun NativeSurfaceInteractionToggle(
    isMoveMode: Boolean,
    onSelectTweakMode: () -> Unit,
    onSelectMoveMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // TWEAK Button (Individual background, white TextPrimary active)
        val isTweakActive = !isMoveMode
        val tweakInteractionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isTweakActive) StudioSurfaceElevated else StudioSurface.copy(alpha = 0.6f))
                .border(1.dp, if (isTweakActive) StudioPanelBorder else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = tweakInteractionSource,
                    indication = androidx.compose.material3.ripple(bounded = true, color = Color.White)
                ) { onSelectTweakMode() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = "Tweak Mode",
                    tint = if (isTweakActive) TextPrimary else TextSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "TWEAK",
                    fontSize = 9.sp,
                    fontWeight = if (isTweakActive) FontWeight.Bold else FontWeight.Medium,
                    color = if (isTweakActive) TextPrimary else TextSecondary
                )
            }
        }

        // MOVE Button (Individual background, white TextPrimary active)
        val isMoveActive = isMoveMode
        val moveInteractionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isMoveActive) StudioSurfaceElevated else StudioSurface.copy(alpha = 0.6f))
                .border(1.dp, if (isMoveActive) StudioPanelBorder else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = moveInteractionSource,
                    indication = androidx.compose.material3.ripple(bounded = true, color = Color.White)
                ) { onSelectMoveMode() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.OpenWith,
                    contentDescription = "Move Mode",
                    tint = if (isMoveActive) TextPrimary else TextSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "MOVE",
                    fontSize = 9.sp,
                    fontWeight = if (isMoveActive) FontWeight.Bold else FontWeight.Medium,
                    color = if (isMoveActive) TextPrimary else TextSecondary
                )
            }
        }
    }
}

private const val DEFAULT_NATIVE_UI_WIDTH = 800
private const val DEFAULT_NATIVE_UI_HEIGHT = 600
private const val NATIVE_UI_MIN_DIMENSION_PX = 100f
private const val NATIVE_UI_STABILIZATION_DELAY_MS = 180L
private const val NATIVE_UI_FADE_ANIMATION_MS = 350
private const val NATIVE_UI_ZOOM_STEP = 0.15f
private const val NATIVE_UI_ZOOM_ROUNDING_SCALE = 20.0
private const val NATIVE_UI_MIN_SCALE = 0.05f
private const val NATIVE_UI_MAX_SCALE = 3.0f
private const val NATIVE_UI_FIT_SNAP_THRESHOLD = 0.02f

@Composable
private fun NativeUiLoadingView(
    slot: RackSlotData,
    modifier: Modifier = Modifier
) {
    val themeColor = if (slot.index == 0) {
        AccentViolet
    } else {
        AccentCyan
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(StudioBackground)
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(36.dp),
            color = themeColor,
            strokeWidth = 3.dp
        )
    }
}

@Composable
private fun NativePluginSurfaceViewer(
    host: GuiHelper.NativeEmbeddedSurfaceControlHost,
    preferredSize: GuiHelper.Size,
    isFitMode: Boolean,
    isMoveMode: Boolean,
    currentScale: Float,
    panOffsetX: Float,
    panOffsetY: Float,
    onFitScaleCalculated: (Float) -> Unit,
    onEffectiveScaleCalculated: (Float) -> Unit,
    onPanDelta: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(StudioBackground)
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        val nativeWidthPx = preferredSize.width.toFloat().coerceAtLeast(NATIVE_UI_MIN_DIMENSION_PX)
        val nativeHeightPx = preferredSize.height.toFloat().coerceAtLeast(NATIVE_UI_MIN_DIMENSION_PX)

        val fitScale = minOf(containerWidthPx / nativeWidthPx, containerHeightPx / nativeHeightPx, 1.0f).coerceAtLeast(NATIVE_UI_MIN_SCALE)

        val effectiveScale = if (isFitMode) {
            fitScale
        } else {
            currentScale.coerceAtLeast(NATIVE_UI_MIN_SCALE)
        }

        LaunchedEffect(fitScale) {
            onFitScaleCalculated(fitScale)
        }

        LaunchedEffect(effectiveScale) {
            onEffectiveScaleCalculated(effectiveScale)
        }

        val scaledContentWidth = nativeWidthPx * effectiveScale
        val scaledContentHeight = nativeHeightPx * effectiveScale

        // Strict out-of-bounds translation clamping in both axes
        val minTransX = if (scaledContentWidth <= containerWidthPx) {
            (containerWidthPx - scaledContentWidth) / 2f
        } else {
            containerWidthPx - scaledContentWidth
        }

        val maxTransX = if (scaledContentWidth <= containerWidthPx) {
            (containerWidthPx - scaledContentWidth) / 2f
        } else {
            0f
        }

        val minTransY = if (scaledContentHeight <= containerHeightPx) {
            (containerHeightPx - scaledContentHeight) / 2f
        } else {
            containerHeightPx - scaledContentHeight
        }

        val maxTransY = if (scaledContentHeight <= containerHeightPx) {
            (containerHeightPx - scaledContentHeight) / 2f
        } else {
            0f
        }

        val transX = if (isFitMode) {
            (containerWidthPx - scaledContentWidth) / 2f
        } else {
            panOffsetX.coerceIn(minTransX, maxTransX)
        }

        val transY = if (isFitMode) {
            (containerHeightPx - scaledContentHeight) / 2f
        } else {
            panOffsetY.coerceIn(minTransY, maxTransY)
        }

        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    clipChildren = true
                    clipToPadding = true
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                    setBackgroundColor(android.graphics.Color.parseColor("#121614"))
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    val surfaceView = host.surfaceView

                    if (surfaceView.parent != null) {
                        (surfaceView.parent as ViewGroup).removeView(surfaceView)
                    }

                    surfaceView.isClickable = true
                    (surfaceView as? android.view.SurfaceView)?.setZOrderMediaOverlay(true)

                    val lp = FrameLayout.LayoutParams(
                        preferredSize.width,
                        preferredSize.height
                    ).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    }
                    surfaceView.layoutParams = lp
                    surfaceView.pivotX = 0f
                    surfaceView.pivotY = 0f
                    surfaceView.scaleX = effectiveScale
                    surfaceView.scaleY = effectiveScale
                    surfaceView.translationX = transX
                    surfaceView.translationY = transY

                    addView(surfaceView)
                }
            },
            update = { frameLayout ->
                val surfaceView = host.surfaceView

                if (surfaceView.parent !== frameLayout) {
                    if (surfaceView.parent != null) {
                        (surfaceView.parent as ViewGroup).removeView(surfaceView)
                    }

                    frameLayout.removeAllViews()
                    frameLayout.addView(surfaceView)
                }

                surfaceView.isClickable = true
                (surfaceView as? android.view.SurfaceView)?.setZOrderMediaOverlay(true)

                val lp = (surfaceView.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(
                    preferredSize.width,
                    preferredSize.height
                )
                lp.width = preferredSize.width
                lp.height = preferredSize.height
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                surfaceView.layoutParams = lp

                surfaceView.pivotX = 0f
                surfaceView.pivotY = 0f
                surfaceView.scaleX = effectiveScale
                surfaceView.scaleY = effectiveScale
                surfaceView.translationX = transX
                surfaceView.translationY = transY
            },
            modifier = Modifier.fillMaxSize()
        )

        // Transparent Move Drag Overlay (captures touches to translate view in X and Y without going out of bounds)
        if (!isFitMode && isMoveMode) {
            val currentOnPanDelta by rememberUpdatedState(onPanDelta)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            currentOnPanDelta(dragAmount.x, dragAmount.y)
                        }
                    }
            )
        }
    }
}

@Composable
private fun NativePluginSurfaceContainer(
    viewModel: HostViewModel,
    slot: RackSlotData,
    plugin: PluginInformation,
    isRackFolded: Boolean,
    onToggleFoldRack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val instance = slot.instance

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && instance != null) {
        var surfaceHost by remember { mutableStateOf<GuiHelper.NativeEmbeddedSurfaceControlHost?>(null) }
        var preferredSize by remember { mutableStateOf(GuiHelper.Size(DEFAULT_NATIVE_UI_WIDTH, DEFAULT_NATIVE_UI_HEIGHT)) }
        var isUiLoading by remember { mutableStateOf(true) }

        val zoomState = viewModel.slotNativeUiZoomStates[slot.index]
        var calculatedFitScale by remember { mutableFloatStateOf(1.0f) }
        var displayedScale by remember { mutableFloatStateOf(1.0f) }

        DisposableEffect(instance.instanceId) {
            isUiLoading = true

            val host = GuiHelper.NativeEmbeddedSurfaceControlHost(
                context = context,
                pluginPackageName = plugin.packageName,
                pluginId = plugin.pluginId ?: "",
                instanceId = instance.instanceId
            )

            host.contentSizeChangedListeners.add { newWidth, newHeight ->
                if (newWidth > 0 && newHeight > 0) {
                    preferredSize = GuiHelper.Size(newWidth, newHeight)
                }
            }

            surfaceHost = host

            coroutineScope.launch {
                try {
                    val size = host.getPreferredSizeOrFallback(
                        DEFAULT_NATIVE_UI_WIDTH,
                        DEFAULT_NATIVE_UI_HEIGHT
                    )

                    preferredSize = size
                    host.connect(size.width, size.height)
                    host.show()

                    delay(NATIVE_UI_STABILIZATION_DELAY_MS)

                    isUiLoading = false
                } catch (e: Throwable) {
                    isUiLoading = false
                    viewModel.updateViewMode(StudioRackViewMode.PARAMETERS)
                }
            }

            onDispose {
                try {
                    host.close()
                } catch (e: Throwable) {
                    // Ignore disposal errors
                }
            }
        }

        surfaceHost?.let { host ->
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Dedicated Top Toolbar Row above the native surface
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NativeSurfaceZoomToolbar(
                            isFitMode = zoomState.isFitMode,
                            displayedScale = displayedScale,
                            onZoomIn = {
                                val base = if (zoomState.isFitMode) {
                                    calculatedFitScale
                                } else {
                                    zoomState.currentScale
                                }

                                val target = (Math.round((base + NATIVE_UI_ZOOM_STEP) * NATIVE_UI_ZOOM_ROUNDING_SCALE) / NATIVE_UI_ZOOM_ROUNDING_SCALE).toFloat().coerceIn(NATIVE_UI_MIN_SCALE, NATIVE_UI_MAX_SCALE)

                                zoomState.isFitMode = false
                                zoomState.currentScale = target
                            },
                            onZoomOut = {
                                val base = if (zoomState.isFitMode) {
                                    calculatedFitScale
                                } else {
                                    zoomState.currentScale
                                }

                                val target = (Math.round((base - NATIVE_UI_ZOOM_STEP) * NATIVE_UI_ZOOM_ROUNDING_SCALE) / NATIVE_UI_ZOOM_ROUNDING_SCALE).toFloat()

                                if (target <= calculatedFitScale + NATIVE_UI_FIT_SNAP_THRESHOLD) {
                                    zoomState.isFitMode = true
                                    zoomState.isMoveMode = false
                                    zoomState.panOffsetX = 0f
                                    zoomState.panOffsetY = 0f
                                } else {
                                    zoomState.isFitMode = false
                                    zoomState.currentScale = target
                                }
                            },
                            showFullscreenButton = true,
                            isFullscreen = isRackFolded,
                            onToggleFullscreen = onToggleFoldRack
                        )

                        if (!zoomState.isFitMode) {
                            NativeSurfaceInteractionToggle(
                                isMoveMode = zoomState.isMoveMode,
                                onSelectTweakMode = {
                                    zoomState.isMoveMode = false
                                },
                                onSelectMoveMode = {
                                    zoomState.isMoveMode = true
                                }
                            )
                        }
                    }

                    if (isRackFolded) {
                        Text(
                            text = plugin.displayName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                // Sizable Centered Native Surface View + Smooth Loading Overlay
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    NativePluginSurfaceViewer(
                        host = host,
                        preferredSize = preferredSize,
                        isFitMode = zoomState.isFitMode,
                        isMoveMode = zoomState.isMoveMode,
                        currentScale = zoomState.currentScale,
                        panOffsetX = zoomState.panOffsetX,
                        panOffsetY = zoomState.panOffsetY,
                        onFitScaleCalculated = { calculatedFitScale = it },
                        onEffectiveScaleCalculated = { displayedScale = it },
                        onPanDelta = { dx, dy ->
                            zoomState.panOffsetX += dx
                            zoomState.panOffsetY += dy
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    val loadingAlpha by animateFloatAsState(
                        targetValue = if (isUiLoading) 1.0f else 0.0f,
                        animationSpec = tween(durationMillis = NATIVE_UI_FADE_ANIMATION_MS),
                        label = "NativeUiLoadingAlpha"
                    )

                    if (loadingAlpha > 0.0f) {
                        NativeUiLoadingView(
                            slot = slot,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = loadingAlpha }
                        )
                    }
                }
            }
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Layers, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Plugin UI requires Android 15+ (API 35+).",
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                Text(
                    text = "Use the Parameters tab to tweak plugin values.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}


@Composable
private fun ParameterCard(
    parameter: ParameterInformation,
    value: Double,
    slotIndex: Int = 0,
    onValueChange: (Double) -> Unit
) {
    val range = parameter.minimumValue..parameter.maximumValue
    val activeAccent = when (slotIndex) {
        0 -> BlossomCoral
        else -> PeriwinkleBlue
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = StudioSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Parameter Name Header
            Text(
                text = parameter.name,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(5.dp))

            // 2. 2D Flat Rotary Encoder Knob (Vertical drag + double-tap to reset)
            FlatRotaryKnob(
                value = value,
                minimumValue = parameter.minimumValue,
                maximumValue = parameter.maximumValue,
                defaultValue = parameter.defaultValue,
                activeColor = activeAccent,
                size = 54.dp,
                onValueChange = onValueChange
            )

            // 3. Combined Value & Range Row: [MIN] [CURRENT VALUE] [MAX]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f", range.start),
                    fontSize = 7.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(activeAccent.copy(alpha = 0.12f))
                        .border(1.dp, activeAccent.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format(Locale.US, "%.2f", value),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.2.sp,
                        color = activeAccent
                    )
                }

                Text(
                    text = String.format(Locale.US, "%.1f", range.endInclusive),
                    fontSize = 7.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun getNoteName(note: Int): String {
    val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val name = noteNames[note % 12]
    val oct = (note / 12) - 1
    return "$name$oct"
}

private val KEYBOARD_HEIGHT = 90.dp
private val KEYBOARD_BLACK_KEY_HEIGHT = 52.dp
private const val KEYBOARD_NUM_WHITE_KEYS = 14
private val KEYBOARD_PANEL_CORNER_RADIUS = 12.dp
private val KEYBOARD_CONTROL_BUTTON_SIZE = 26.dp
private val KEYBOARD_CONTROL_ICON_SIZE = 14.dp

@Composable
private fun MidiKeyboardSection(
    viewModel: HostViewModel
) {
    val noteOnStates = viewModel.keyboardNoteOnStates
    val octave = viewModel.keyboardOctave
    val isHoldEnabled = viewModel.isKeyboardHoldActive
    var isKeyboardFolded by remember { mutableStateOf(false) }

    // Start & End notes covering full MIDI range 0..127 across octaves 0..9
    val startNote = octave * 12
    val endNote = minOf(127, (octave + 2) * 12 - 1)
    val startNoteName = getNoteName(startNote)
    val endNoteName = getNoteName(endNote)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KEYBOARD_PANEL_CORNER_RADIUS),
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioPanelBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Attached Control Header Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Octave Stepper (- / OCT RANGE / +)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val isOctaveDownEnabled = octave > 0
                    val isOctaveUpEnabled = octave < 9

                    // Octave Down (-)
                    Box(
                        modifier = Modifier
                            .size(KEYBOARD_CONTROL_BUTTON_SIZE)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isOctaveDownEnabled) {
                                    StudioSurfaceElevated
                                } else {
                                    StudioSurfaceElevated.copy(alpha = 0.4f)
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (isOctaveDownEnabled) {
                                    StudioPanelBorder
                                } else {
                                    Color.Transparent
                                },
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable(enabled = isOctaveDownEnabled) {
                                viewModel.keyboardOctave = octave - 1
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Octave Down",
                            tint = if (isOctaveDownEnabled) {
                                SproutGreen
                            } else {
                                TextMuted
                            },
                            modifier = Modifier.size(KEYBOARD_CONTROL_ICON_SIZE)
                        )
                    }

                    // Note Range Display Badge (e.g. C3 – B4)
                    Box(
                        modifier = Modifier
                            .height(KEYBOARD_CONTROL_BUTTON_SIZE)
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioSurfaceElevated)
                            .border(1.dp, StudioPanelBorder, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(SproutGreen)
                            )

                            Text(
                                text = "$startNoteName – $endNoteName",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    // Octave Up (+)
                    Box(
                        modifier = Modifier
                            .size(KEYBOARD_CONTROL_BUTTON_SIZE)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isOctaveUpEnabled) {
                                    StudioSurfaceElevated
                                } else {
                                    StudioSurfaceElevated.copy(alpha = 0.4f)
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (isOctaveUpEnabled) {
                                    StudioPanelBorder
                                } else {
                                    Color.Transparent
                                },
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable(enabled = isOctaveUpEnabled) {
                                viewModel.keyboardOctave = octave + 1
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Octave Up",
                            tint = if (isOctaveUpEnabled) {
                                SproutGreen
                            } else {
                                TextMuted
                            },
                            modifier = Modifier.size(KEYBOARD_CONTROL_ICON_SIZE)
                        )
                    }
                }

                // Right: HOLD Mode Toggle Button & Fold/Hide Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // HOLD Button
                    Box(
                        modifier = Modifier
                            .height(KEYBOARD_CONTROL_BUTTON_SIZE)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isHoldEnabled) {
                                    SproutGreen.copy(alpha = 0.15f)
                                } else {
                                    StudioSurfaceElevated
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (isHoldEnabled) {
                                    SproutGreen
                                } else {
                                    StudioPanelBorder
                                },
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                viewModel.toggleKeyboardHold()
                            }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isHoldEnabled) {
                                            SproutGreen
                                        } else {
                                            TextMuted
                                        }
                                    )
                            )

                            Text(
                                text = "HOLD",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (isHoldEnabled) {
                                    SproutGreen
                                } else {
                                    TextSecondary
                                },
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    // Hide / Fold Toggle Button
                    Box(
                        modifier = Modifier
                            .size(KEYBOARD_CONTROL_BUTTON_SIZE)
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioSurfaceElevated)
                            .border(1.dp, StudioPanelBorder, RoundedCornerShape(6.dp))
                            .clickable {
                                isKeyboardFolded = !isKeyboardFolded
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isKeyboardFolded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = if (isKeyboardFolded) {
                                "Show Keyboard"
                            } else {
                                "Hide Keyboard"
                            },
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Attached Keyboard Surface
            if (!isKeyboardFolded) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = StudioPanelBorder
                )

                StudioKeyboard(
                    noteOnStates = noteOnStates.toList(),
                    octaveZeroBased = octave,
                    numWhiteKeys = KEYBOARD_NUM_WHITE_KEYS,
                    totalHeight = KEYBOARD_HEIGHT,
                    blackKeyHeight = KEYBOARD_BLACK_KEY_HEIGHT,
                    whiteKeyColor = KeyboardWhiteKey,
                    blackKeyColor = KeyboardBlackKey,
                    whiteNoteOnColor = SproutGreen,
                    blackNoteOnColor = SproutGreen,
                    onNoteOn = { note ->
                        viewModel.onKeyboardNoteOn(note)
                    },
                    onNoteOff = { note ->
                        viewModel.onKeyboardNoteOff(note)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(KEYBOARD_HEIGHT)
                )
            }
        }
    }
}

@Composable
private fun NoPluginInSlotView(
    slot: RackSlotData,
    isProcessing: Boolean,
    onOpenBrowser: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(StudioBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            GreenhouseMascot(
                size = 72.dp,
                isAnimated = true,
                isListening = isProcessing
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "${slot.title} is ready",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (slot.index == 0) {
                    "Plant an instrument plugin to start playing."
                } else {
                    "Give your melody room to breathe and expand."
                },
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onOpenBrowser,
                colors = ButtonDefaults.buttonColors(containerColor = SproutGreen, contentColor = StudioBackground),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = "BROWSE PLUGINS",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun PluginLoadingView(
    slot: RackSlotData,
    pluginName: String?
) {
    val themeColor = if (slot.index == 0) {
        BlossomCoral
    } else {
        PeriwinkleBlue
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(StudioBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            GreenhouseMascot(
                size = 64.dp,
                isAnimated = true,
                isListening = true
            )

            Spacer(modifier = Modifier.height(14.dp))

            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = themeColor,
                strokeWidth = 2.5.dp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Nurturing ${pluginName ?: "Plugin"}...",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Initializing ${slot.title} (${slot.slotType.lowercase(Locale.US)})...",
                fontSize = 11.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

