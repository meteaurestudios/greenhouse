package org.androidaudioplugin.host.ui.screens

import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import org.androidaudioplugin.host.ui.StudioRackViewMode
import org.androidaudioplugin.host.ui.theme.*
import org.androidaudioplugin.hosting.GuiHelper
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioRackScreen(
    viewModel: HostViewModel,
    onNavigateToBrowser: () -> Unit
) {
    val activePlugin = viewModel.selectedPlugin

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(12.dp)
    ) {
        // Master Control Banner
        MasterControlBanner(
            plugin = activePlugin,
            isProcessing = viewModel.isProcessing,
            isSamplePressed = viewModel.isSamplePressed,
            onToggleProcessing = { viewModel.toggleAudioPlayback() },
            onTriggerAudioSample = { viewModel.triggerSampleAudio() }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // View Mode Selector & Preset Bar
        StatusAndModeSelectorBar(
            currentMode = viewModel.currentViewMode,
            onModeSelected = { viewModel.updateViewMode(it) },
            selectedPresetIndex = viewModel.selectedPresetIndex,
            onPresetSelected = { viewModel.setPreset(it) }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Dynamic Main Panel (Parameter Control Rack / Native Embedded GUI / Ports Spec)
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
                NoPluginLoadedView(onNavigateToBrowser = onNavigateToBrowser)
            } else {
                when (viewModel.currentViewMode) {
                    StudioRackViewMode.PARAMETERS -> {
                        ParameterControlRack(
                            parameters = activePlugin.parameters,
                            parameterValues = viewModel.parameterValues,
                            onValueChange = { param, valDouble ->
                                viewModel.setParameterValue(param, valDouble)
                            }
                        )
                    }
                    StudioRackViewMode.NATIVE_SURFACE -> {
                        NativePluginSurfaceContainer(
                            viewModel = viewModel,
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

        // On-screen Live Interactive MIDI Keyboard
        MidiKeyboardSection(
            onNoteOn = { note -> viewModel.sendNoteOn(note) },
            onNoteOff = { note -> viewModel.sendNoteOff(note) }
        )
    }
}

@Composable
private fun MasterControlBanner(
    plugin: PluginInformation?,
    isProcessing: Boolean,
    isSamplePressed: Boolean,
    onToggleProcessing: () -> Unit,
    onTriggerAudioSample: () -> Unit
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
                .padding(14.dp)
        ) {
            // Header Info: Status Badge, Full Plugin Title & Developer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isProcessing && plugin != null) SignalGreen else DangerRed)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (plugin != null) AccentGold.copy(alpha = 0.15f) else StudioSurface)
                            .border(1.dp, if (plugin != null) AccentGold.copy(alpha = 0.4f) else StudioPanelBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = plugin?.category?.uppercase(Locale.US) ?: "NO PLUGIN",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (plugin != null) AccentGold else TextMuted
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isEffect = plugin != null &&
                            (plugin.category?.contains("Synth", ignoreCase = true) != true &&
                             plugin.category?.contains("Instrument", ignoreCase = true) != true)

                    if (isEffect) {
                        val activeColor = if (isSamplePressed) AccentGold.copy(alpha = 0.65f) else AccentGold
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(activeColor)
                                .border(
                                    width = 1.dp,
                                    color = activeColor,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { onTriggerAudioSample() }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = StudioBackground
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "SAMPLE TEST",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = StudioBackground,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(20.dp))
                    }

                    if (plugin != null) {
                        IconButton(
                            onClick = onToggleProcessing,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (isProcessing) SignalGreen.copy(alpha = 0.15f) else DangerRed.copy(alpha = 0.15f))
                                .border(1.dp, if (isProcessing) SignalGreen else DangerRed, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "Toggle Audio Engine",
                                tint = if (isProcessing) SignalGreen else DangerRed,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Plugin Display Name
            Text(
                text = plugin?.displayName ?: "No AAP Plugin Loaded",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Developer / Vendor / Package ID
            Text(
                text = if (plugin != null)
                    "${plugin.developer ?: "Unknown Vendor"} • ${plugin.packageName}"
                else
                    "Select a plugin from the Catalog tab",
                fontSize = 11.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StatusAndModeSelectorBar(
    currentMode: StudioRackViewMode,
    onModeSelected: (StudioRackViewMode) -> Unit,
    selectedPresetIndex: Int,
    onPresetSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mode Selector Chips (Scrollable so it never squeezes the preset box)
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
                        .padding(horizontal = 12.dp, vertical = 7.dp)
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

        // Preset Stepper Box (Guaranteed un-squeezed layout)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(StudioSurface)
                .border(1.dp, StudioPanelBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text("Preset #$selectedPresetIndex", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = { if (selectedPresetIndex > 0) onPresetSelected(selectedPresetIndex - 1) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Prev Preset", tint = AccentGold, modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick = { onPresetSelected(selectedPresetIndex + 1) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Next Preset", tint = AccentGold, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ParameterControlRack(
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
            items(parameters, key = { it.id }) { param ->
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
    plugin: PluginInformation
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val activeInstance = viewModel.activeInstance

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activeInstance != null) {
        var surfaceHost by remember { mutableStateOf<GuiHelper.NativeEmbeddedSurfaceControlHost?>(null) }

        DisposableEffect(activeInstance.instanceId) {
            val host = GuiHelper.NativeEmbeddedSurfaceControlHost(
                context = context,
                pluginPackageName = plugin.packageName,
                pluginId = plugin.pluginId ?: "",
                instanceId = activeInstance.instanceId
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
                    text = "MIDI KEYBOARD",
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
                    .height(130.dp)
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
private fun NoPluginLoadedView(onNavigateToBrowser: () -> Unit) {
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
                modifier = Modifier.size(64.dp),
                tint = ElectricBlue
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No AAP Plugin Selected",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Explore system-wide Audio Plugin Services (Instruments & Effects) from the catalog.",
                fontSize = 13.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onNavigateToBrowser,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = StudioBackground),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("OPEN PLUGIN CATALOG", fontWeight = FontWeight.Bold)
            }
        }
    }
}
