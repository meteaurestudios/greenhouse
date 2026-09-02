package org.androidaudioplugin.greenhouse.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
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
import kotlinx.coroutines.launch
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.RackSlotData
import org.androidaudioplugin.greenhouse.ui.SlotLevel
import org.androidaudioplugin.greenhouse.ui.StudioRackViewMode
import org.androidaudioplugin.greenhouse.ui.components.SlotStereoLevelMeter
import org.androidaudioplugin.greenhouse.ui.components.StudioKeyboard
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

            // View Mode Selector & Preset Bar for Active Slot
            StatusAndModeSelectorBar(
                activeSlot = activeSlot,
                currentMode = viewModel.currentViewMode,
                onModeSelected = { viewModel.updateViewMode(it) },
                onPresetSelected = { viewModel.setPreset(activeSlot.index, it) }
            )

            Spacer(modifier = Modifier.height(10.dp))
        }

        // Dynamic Main Panel (Parameter Controls / Native Embedded GUI / Ports Spec)
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
                        StudioRackViewMode.SPECS -> {
                            PluginPortsAndSpecsView(plugin = activePlugin)
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

@Composable
private fun DspCpuMeter(
    cpuPercent: Float,
    isProcessing: Boolean,
    onToggleProcessing: () -> Unit
) {
    val meterColor = when {
        !isProcessing -> TextMuted
        cpuPercent > 80f -> DangerRed
        cpuPercent > 50f -> WarningOrange
        else -> SignalGreen
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isProcessing) StudioSurface else DangerRed.copy(alpha = 0.08f))
            .border(
                width = 1.dp,
                color = if (isProcessing) StudioPanelBorder else DangerRed.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onToggleProcessing() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (isProcessing) SignalGreen else DangerRed)
        )

        Text(
            text = "DSP",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (isProcessing) NeonCyan else TextMuted
        )

        Box(
            modifier = Modifier
                .width(36.dp)
                .height(6.dp)
                .clip(CircleShape)
                .background(StudioBackground)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((if (isProcessing) cpuPercent / 100f else 0f).coerceIn(0f, 1f))
                    .clip(CircleShape)
                    .background(meterColor)
            )
        }

        Text(
            text = if (isProcessing) "${cpuPercent.toInt()}%" else "OFF",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp),
            maxLines = 1,
            color = meterColor
        )
    }
}

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
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Status Dot, Title & Settings Info
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isProcessing) SignalGreen else DangerRed)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = "AAP SIGNAL CHAIN",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        letterSpacing = 0.5.sp
                    )

                    Spacer(modifier = Modifier.width(2.dp))

                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable { onOpenSettings() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Audio Engine Settings & Diagnostics",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Right: Interactive Real-Time DSP CPU Meter (click to activate / deactivate engine)
                DspCpuMeter(
                    cpuPercent = totalCpuLoad,
                    isProcessing = isProcessing,
                    onToggleProcessing = onToggleProcessing
                )
            }
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
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        slots.forEach { slot ->
            val isSelected = slot.index == activeSlotIndex
            val isLoaded = slot.pluginInfo != null
            val isLoading = slot.isLoading
            val slotLevel = slotLevels.getOrNull(slot.index) ?: SlotLevel()

            val badgeColor = if (slot.index == 0) {
                AccentViolet
            } else {
                AccentCyan
            }

            val borderColor = when {
                isSelected -> AccentGold
                isLoaded -> NeonCyan.copy(alpha = 0.5f)
                else -> StudioPanelBorder
            }

            val borderWidth = if (isSelected) {
                2.dp
            } else {
                1.dp
            }

            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(104.dp)
                    .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelectSlot(slot.index) },
                shape = RoundedCornerShape(16.dp),
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
                        .padding(horizontal = 9.dp, vertical = 9.dp),
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
                                        ) { onToggleBypass(slot.index) }
                                )
                            }

                            // Slot Type Capsule Label Box
                            Box(
                                modifier = Modifier
                                    .border(1.dp, badgeColor.copy(alpha = 0.7f), CircleShape)
                                    .clip(CircleShape)
                                    .background(badgeColor.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = slot.slotType.lowercase(Locale.US),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor
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
                                text = "Loading...",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextMuted
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
                                        .clip(CircleShape)
                                        .background(Color(0xFF282D3B))
                                        .border(1.dp, StudioPanelBorder, CircleShape)
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
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            val slotCpu = slotCpuLoads.getOrNull(slot.index) ?: 0f
                            val statusText = when {
                                slot.isBypassed -> "Bypassed"
                                isProcessing -> "DSP ${slotCpu.toInt()}%"
                                else -> "ACTIVE"
                            }

                            Text(
                                text = statusText,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
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
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF282D3B))
                                    .clickable { onAddPlugin(slot.index) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "+ ADD",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NeonCyan,
                                    letterSpacing = 0.5.sp
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
    }
}

@Composable
private fun StatusAndModeSelectorBar(
    activeSlot: RackSlotData,
    currentMode: StudioRackViewMode,
    onModeSelected: (StudioRackViewMode) -> Unit,
    onPresetSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(StudioRackViewMode.values()) { mode ->
                val isSelected = currentMode == mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) Color(0xFF282D3B) else StudioSurface)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) AccentGold.copy(alpha = 0.6f) else StudioPanelBorder,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onModeSelected(mode) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = mode.title,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) TextPrimary else TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(StudioSurface)
                .border(1.dp, StudioPanelBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text("Preset #${activeSlot.selectedPresetIndex}", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = {
                    if (activeSlot.selectedPresetIndex > 0) {
                        onPresetSelected(activeSlot.selectedPresetIndex - 1)
                    }
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Prev Preset", tint = AccentGold, modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick = { onPresetSelected(activeSlot.selectedPresetIndex + 1) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Next Preset", tint = AccentGold, modifier = Modifier.size(16.dp))
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
            columns = GridCells.Adaptive(minSize = 160.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(parameters, key = { param -> "${slotIndex}_${pluginId}_${param.id}" }) { param ->
                val currentValue = parameterValues[param.id] ?: param.defaultValue
                ParameterCard(
                    parameter = param,
                    value = currentValue,
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
            .background(Color(0xD9181C26))
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
                .background(if (canZoomOut) Color(0xFF282D3B) else Color(0xFF1E212A))
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
                .background(if (isFitMode) Color(0xFF282D3B) else Color.Transparent)
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
                .background(Color(0xFF282D3B))
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
                    .background(Color(0xFF282D3B))
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
                .background(if (isTweakActive) Color(0xFF282D3B) else Color(0x80181C26))
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
                .background(if (isMoveActive) Color(0xFF282D3B) else Color(0x80181C26))
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
            .background(Color(0xFF10131A))
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        val nativeWidthPx = preferredSize.width.toFloat().coerceAtLeast(100f)
        val nativeHeightPx = preferredSize.height.toFloat().coerceAtLeast(100f)

        val fitScale = minOf(containerWidthPx / nativeWidthPx, containerHeightPx / nativeHeightPx, 1.0f).coerceAtLeast(0.05f)
        val effectiveScale = if (isFitMode) {
            fitScale
        } else {
            currentScale.coerceAtLeast(0.05f)
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
                    setBackgroundColor(android.graphics.Color.parseColor("#10131A"))
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
        var preferredSize by remember { mutableStateOf(GuiHelper.Size(800, 600)) }

        val zoomState = viewModel.slotNativeUiZoomStates[slot.index]
        var calculatedFitScale by remember { mutableFloatStateOf(1.0f) }
        var displayedScale by remember { mutableFloatStateOf(1.0f) }

        DisposableEffect(instance.instanceId) {
            val host = GuiHelper.NativeEmbeddedSurfaceControlHost(
                context = context,
                pluginPackageName = plugin.packageName,
                pluginId = plugin.pluginId ?: "",
                instanceId = instance.instanceId
            )
            surfaceHost = host

            coroutineScope.launch {
                try {
                    val size = host.getPreferredSizeOrFallback(800, 600)
                    preferredSize = size
                    host.connect(size.width, size.height)
                    host.show()
                } catch (e: Throwable) {
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
                                val target = (Math.round((base + 0.15f) * 20.0) / 20.0).toFloat().coerceIn(0.05f, 3.0f)

                                zoomState.isFitMode = false
                                zoomState.currentScale = target
                            },
                            onZoomOut = {
                                val base = if (zoomState.isFitMode) {
                                    calculatedFitScale
                                } else {
                                    zoomState.currentScale
                                }
                                val target = (Math.round((base - 0.15f) * 20.0) / 20.0).toFloat()

                                if (target <= calculatedFitScale + 0.02f) {
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

                // Sizable Centered Native Surface View
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
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
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
private fun PluginPortsAndSpecsView(plugin: PluginInformation) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text("PLUGIN SPECIFICATIONS", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NeonCyan)

            Spacer(modifier = Modifier.height(4.dp))

            Text("ID: ${plugin.pluginId ?: "N/A"}", fontSize = 12.sp, color = TextPrimary)
            Text("Package: ${plugin.packageName}", fontSize = 12.sp, color = TextSecondary)
            Text("Category: ${plugin.category ?: "Unspecified"}", fontSize = 12.sp, color = TextSecondary)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = StudioPanelBorder)

            Text("AUDIO & MIDI PORTS (${plugin.ports.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SignalGreen)
        }

        items(plugin.ports) { port ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
                colors = CardDefaults.cardColors(containerColor = StudioSurfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(port.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(
                        text = "Dir: ${if (port.direction == 0) "IN" else "OUT"} | Type: ${port.content}",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun ParameterCard(
    parameter: ParameterInformation,
    value: Double,
    onValueChange: (Double) -> Unit
) {
    val range = parameter.minimumValue..parameter.maximumValue

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = parameter.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = String.format(Locale.US, "%.2f", value),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NeonCyan
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toDouble()) },
                valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = NeonCyan,
                    activeTrackColor = NeonCyan,
                    inactiveTrackColor = KnobArcBackground
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f", range.start),
                    fontSize = 9.sp,
                    color = TextMuted
                )
                Text(
                    text = String.format(Locale.US, "%.1f", range.endInclusive),
                    fontSize = 9.sp,
                    color = TextMuted
                )
            }
        }
    }
}

private fun getNoteName(note: Int): String {
    val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val name = noteNames[note % 12]
    val oct = (note / 12) - 2
    return "$name$oct"
}

@Composable
private fun MidiKeyboardSection(
    viewModel: HostViewModel
) {
    val noteOnStates = viewModel.keyboardNoteOnStates
    val octave = viewModel.keyboardOctave
    val isHoldEnabled = viewModel.isKeyboardHoldActive

    // Start & End notes covering full MIDI range 0..127 across octaves 0..9
    val startNote = octave * 12
    val endNote = minOf(127, (octave + 2) * 12 - 1)
    val startNoteName = getNoteName(startNote)
    val endNoteName = getNoteName(endNote)

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Controls Row: Octave Stepper on Left + HOLD Button on Right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Octave Stepper (- / Note Range / +)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Octave Down (-)
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (octave > 0) StudioSurfaceVariant else StudioSurfaceVariant.copy(alpha = 0.4f))
                        .border(1.dp, if (octave > 0) StudioPanelBorder else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable(enabled = octave > 0) { viewModel.keyboardOctave = octave - 1 },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Octave Down",
                        tint = if (octave > 0) AccentGold else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Note Range Display Badge (e.g. C2 – B3, spans full MIDI range 0..127 across octaves 0..9)
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(StudioSurfaceVariant)
                        .border(1.dp, StudioPanelBorder, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(SignalGreen)
                        )
                        Text(
                            text = "$startNoteName – $endNoteName",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Octave Up (+)
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (octave < 9) StudioSurfaceVariant else StudioSurfaceVariant.copy(alpha = 0.4f))
                        .border(1.dp, if (octave < 9) StudioPanelBorder else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable(enabled = octave < 9) { viewModel.keyboardOctave = octave + 1 },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Octave Up",
                        tint = if (octave < 9) AccentGold else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Right: HOLD Mode Toggle Button
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isHoldEnabled) AccentCyan.copy(alpha = 0.2f) else StudioSurfaceVariant)
                    .border(
                        width = 1.dp,
                        color = if (isHoldEnabled) AccentCyan else StudioPanelBorder,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable {
                        viewModel.toggleKeyboardHold()
                    }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isHoldEnabled) AccentCyan else TextMuted)
                    )
                    Text(
                        text = "HOLD",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isHoldEnabled) AccentCyan else TextSecondary,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Keyboard Surface (Positioned at bottom of page)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(95.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F1117))
                .border(1.dp, StudioPanelBorder, RoundedCornerShape(8.dp))
                .padding(2.dp)
        ) {
            val availableWidth = maxWidth
            val computedWhiteKeyWidth = availableWidth / 14

            StudioKeyboard(
                noteOnStates = noteOnStates.toList(),
                octaveZeroBased = octave,
                numWhiteKeys = 14, // 2 Full Octaves
                whiteKeyWidth = computedWhiteKeyWidth,
                totalHeight = 90.dp,
                blackKeyHeight = 52.dp,
                whiteKeyColor = Color(0xFFF1F5F9), // Pristine Matte Off-White
                blackKeyColor = Color(0xFF171A21), // Deep Obsidian Matte Black
                whiteNoteOnColor = ElectricBlue,    // High-visibility Electric Blue for all key highlights
                blackNoteOnColor = ElectricBlue,    // High-visibility Electric Blue for all key highlights
                onNoteOn = { note ->
                    viewModel.onKeyboardNoteOn(note)
                },
                onNoteOff = { note ->
                    viewModel.onKeyboardNoteOff(note)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun NoPluginInSlotView(
    slot: RackSlotData,
    onOpenBrowser: () -> Unit
) {
    val slotColor = if (slot.index == 0) {
        AccentViolet
    } else {
        AccentCyan
    }

    val iconVector = if (slot.index == 0) {
        Icons.Default.MusicNote
    } else {
        Icons.Default.GraphicEq
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(slotColor.copy(alpha = 0.12f))
                    .border(1.dp, slotColor.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = slotColor
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "${slot.title} is Empty",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Select an ${slot.slotType.lowercase(Locale.US)} plugin to insert into this slot.",
                fontSize = 13.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onOpenBrowser,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = StudioBackground),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "BROWSE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
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
        AccentViolet
    } else {
        AccentCyan
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = themeColor,
                strokeWidth = 3.dp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Loading ${pluginName ?: "Plugin"}...",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Please wait while initializing ${slot.title} (${slot.slotType.lowercase(Locale.US)})...",
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
    }
}

