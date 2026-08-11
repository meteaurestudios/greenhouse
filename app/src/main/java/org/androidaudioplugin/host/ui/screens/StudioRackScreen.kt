package org.androidaudioplugin.host.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.composeaudiocontrols.DiatonicKeyboard
import org.androidaudioplugin.composeaudiocontrols.DiatonicKeyboardMoveAction
import org.androidaudioplugin.host.ui.HostViewModel
import org.androidaudioplugin.host.ui.theme.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioRackScreen(
    viewModel: HostViewModel,
    onNavigateToBrowser: () -> Unit
) {
    val activePlugin = viewModel.selectedPlugin

    if (activePlugin == null) {
        NoPluginLoadedView(onNavigateToBrowser = onNavigateToBrowser)
        return
    }

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
            onToggleProcessing = { viewModel.toggleAudioPlayback() },
            onTriggerAudioSample = { viewModel.triggerSampleAudio() }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Status & Presets Strip
        StatusAndPresetBar(
            statusMessage = viewModel.statusMessage ?: "",
            selectedPresetIndex = viewModel.selectedPresetIndex,
            onPresetSelected = { viewModel.setPreset(it) }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Parameters Control Rack Grid
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(StudioSurface)
                .border(1.dp, StudioPanelBorder, RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            if (activePlugin.parameters.isEmpty()) {
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
                    items(activePlugin.parameters, key = { it.id }) { param ->
                        val currentValue = viewModel.parameterValues[param.id] ?: param.defaultValue
                        ParameterCard(
                            parameter = param,
                            value = currentValue,
                            onValueChange = { newValue ->
                                viewModel.setParameterValue(param, newValue)
                            }
                        )
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
    plugin: PluginInformation,
    isProcessing: Boolean,
    onToggleProcessing: () -> Unit,
    onTriggerAudioSample: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isProcessing) SignalGreen else DangerRed)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = plugin.displayName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${plugin.developer ?: "Unknown Vendor"} • ${plugin.category ?: "Audio Plugin"}",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Audio Sample Test Trigger Button
                Button(
                    onClick = onTriggerAudioSample,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricBlue,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("SAMPLE TEST", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // Processing Power Switch Button
                IconButton(
                    onClick = onToggleProcessing,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isProcessing) SignalGreen.copy(alpha = 0.2f) else DangerRed.copy(alpha = 0.2f))
                        .border(1.dp, if (isProcessing) SignalGreen else DangerRed, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Toggle Audio Engine",
                        tint = if (isProcessing) SignalGreen else DangerRed
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusAndPresetBar(
    statusMessage: String,
    selectedPresetIndex: Int,
    onPresetSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = statusMessage,
            fontSize = 11.sp,
            color = NeonCyan,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Simple preset stepper
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(StudioSurface)
                .border(1.dp, StudioPanelBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text("Preset: #$selectedPresetIndex", fontSize = 11.sp, color = TextPrimary)
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = { if (selectedPresetIndex > 0) onPresetSelected(selectedPresetIndex - 1) },
                modifier = Modifier.size(20.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Prev Preset", tint = NeonCyan)
            }
            IconButton(
                onClick = { onPresetSelected(selectedPresetIndex + 1) },
                modifier = Modifier.size(20.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Next Preset", tint = NeonCyan)
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
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = StudioSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            // Keyboard Header Control Row
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

                // Octave Selector Buttons
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

            Spacer(modifier = Modifier.height(8.dp))

            // Diatonic Piano Component
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
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = TextMuted
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
                text = "Open the Plugin Browser catalog to select and instantiate a synth or audio effect plugin.",
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onNavigateToBrowser,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = StudioBackground),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("BROWSE PLUGINS", fontWeight = FontWeight.Bold)
            }
        }
    }
}
