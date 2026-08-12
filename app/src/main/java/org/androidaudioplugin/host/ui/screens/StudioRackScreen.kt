package org.androidaudioplugin.host.ui.screens

import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.composeaudiocontrols.DiatonicKeyboard
import org.androidaudioplugin.composeaudiocontrols.DiatonicKeyboardMoveAction
import org.androidaudioplugin.host.ui.HostViewModel
import org.androidaudioplugin.host.ui.RackSlotData
import org.androidaudioplugin.host.ui.StudioRackViewMode
import org.androidaudioplugin.host.ui.theme.*
import org.androidaudioplugin.hosting.GuiHelper
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioRackScreen(
    viewModel: HostViewModel,
    onNavigateToBrowser: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val activeSlot = viewModel.activeSlot
    val activePlugin = activeSlot.pluginInfo

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(12.dp)
    ) {
        // Master Control Banner
        MasterControlBanner(
            slots = viewModel.slots,
            isProcessing = viewModel.isProcessing,
            isSamplePressed = viewModel.isSamplePressed,
            onToggleProcessing = { viewModel.toggleAudioPlayback() },
            onTriggerAudioSample = { viewModel.triggerSampleAudio() },
            onOpenSettings = onNavigateToSettings
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 3-Slot Audio Signal Chain Rack Header
        SignalRackHeader(
            slots = viewModel.slots,
            activeSlotIndex = viewModel.activeSlotIndex,
            onSelectSlot = { viewModel.selectActiveSlot(it) },
            onAddPlugin = { slotIndex ->
                viewModel.openBrowserForSlot(slotIndex)
                onNavigateToBrowser()
            },
            onToggleBypass = { viewModel.toggleSlotBypass(it) },
            onUnloadSlot = { viewModel.unloadSlot(it) }
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
            if (activePlugin == null) {
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
                            plugin = activePlugin
                        )
                    }
                    StudioRackViewMode.SPECS -> {
                        PluginPortsAndSpecsView(plugin = activePlugin)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // On-screen Live Interactive MIDI Keyboard (Routes to Slot 0: Instrument)
        MidiKeyboardSection(
            onNoteOn = { note -> viewModel.sendNoteOn(note) },
            onNoteOff = { note -> viewModel.sendNoteOff(note) }
        )
    }
}

@Composable
private fun MasterControlBanner(
    slots: List<RackSlotData>,
    isProcessing: Boolean,
    isSamplePressed: Boolean,
    onToggleProcessing: () -> Unit,
    onTriggerAudioSample: () -> Unit,
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
                // Left: Status Dot, Title & Settings Cog
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
                            imageVector = Icons.Default.Info,
                            contentDescription = "Diagnostics & Info",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Right: TEST Button & Engine Power Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val activeColor = if (isPressed) AccentGold.copy(alpha = 0.5f) else AccentGold

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(activeColor)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) { onTriggerAudioSample() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = StudioBackground
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "TEST",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = StudioBackground
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (isProcessing) SignalGreen.copy(alpha = 0.15f) else DangerRed.copy(alpha = 0.15f))
                            .border(1.dp, if (isProcessing) SignalGreen else DangerRed, CircleShape)
                            .clickable { onToggleProcessing() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Toggle Engine",
                            tint = if (isProcessing) SignalGreen else DangerRed,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalRackHeader(
    slots: List<RackSlotData>,
    activeSlotIndex: Int,
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
            val borderColor = when {
                isSelected -> AccentGold
                isLoaded -> NeonCyan.copy(alpha = 0.5f)
                else -> StudioPanelBorder
            }

            Card(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
                    .clickable { onSelectSlot(slot.index) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) StudioSurfaceVariant else StudioSurface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (slot.index == 0) AccentViolet.copy(alpha = 0.2f) else AccentCyan.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = slot.slotType.uppercase(Locale.US),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (slot.index == 0) AccentViolet else AccentCyan
                            )
                        }

                        if (isLoaded) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = "Bypass",
                                    tint = if (slot.isBypassed) DangerRed else SignalGreen,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onToggleBypass(slot.index) }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = TextMuted,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onUnloadSlot(slot.index) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = slot.pluginInfo?.displayName ?: "Empty Slot",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isLoaded) TextPrimary else TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!isLoaded) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF282D3B))
                                .clickable { onAddPlugin(slot.index) }
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "+ ADD",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = NeonCyan
                            )
                        }
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
                onClick = { if (activeSlot.selectedPresetIndex > 0) onPresetSelected(activeSlot.selectedPresetIndex - 1) },
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
private fun NativePluginSurfaceContainer(
    viewModel: HostViewModel,
    slot: RackSlotData,
    plugin: PluginInformation
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val instance = slot.instance

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && instance != null) {
        var surfaceHost by remember { mutableStateOf<GuiHelper.NativeEmbeddedSurfaceControlHost?>(null) }

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

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            surfaceHost?.let { host ->
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            val surfaceView = host.surfaceView
                            if (surfaceView.parent != null) {
                                (surfaceView.parent as ViewGroup).removeView(surfaceView)
                            }
                            addView(surfaceView)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
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
                    text = "Native Surface GUI container requires Android 11+ (API 30+).",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Text(
                    text = "Use the Parameter Controls tab to tweak plugin values.",
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
            Divider(modifier = Modifier.padding(vertical = 8.dp), color = StudioPanelBorder)
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

@Composable
private fun MidiKeyboardSection(
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit
) {
    val noteOnStates = remember { mutableStateListOf<Long>().apply { addAll(List(128) { 0L }) } }
    var octave by remember { mutableIntStateOf(4) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = StudioSurface)
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
                    text = "MIDI KEYBOARD (INSTRUMENT SLOT 1)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "[\u25C0]",
                        fontSize = 14.sp,
                        color = NeonCyan,
                        modifier = Modifier
                            .clickable { if (octave > 0) octave-- }
                            .padding(horizontal = 6.dp)
                    )
                    Text(
                        text = "Octave: $octave",
                        fontSize = 12.sp,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "[\u25B6]",
                        fontSize = 14.sp,
                        color = NeonCyan,
                        modifier = Modifier
                            .clickable { if (octave < 8) octave++ }
                            .padding(horizontal = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                DiatonicKeyboard(
                    noteOnStates = noteOnStates.toList(),
                    octaveZeroBased = octave,
                    moveAction = DiatonicKeyboardMoveAction.NoteChange,
                    onNoteOn = { note, _ ->
                        if (note in 0..127) {
                            noteOnStates[note] = 1L
                            onNoteOn(note)
                        }
                    },
                    onNoteOff = { note, _ ->
                        if (note in 0..127) {
                            noteOnStates[note] = 0L
                            onNoteOff(note)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun NoPluginInSlotView(
    slot: RackSlotData,
    onOpenBrowser: () -> Unit
) {
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
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = ElectricBlue
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "${slot.title} (${slot.slotType}) is Empty",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Load an AAP ${slot.slotType.lowercase(Locale.US)} plugin from the catalog to build your audio signal chain.",
                fontSize = 13.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onOpenBrowser,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = StudioBackground),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("BROWSE PLUGINS FOR ${slot.slotType.uppercase(Locale.US)}", fontWeight = FontWeight.Bold)
            }
        }
    }
}

