package org.androidaudioplugin.greenhouse.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.core.MidiControllerManager
import org.androidaudioplugin.greenhouse.core.OutputStreamMode
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.components.CONTROL_LABEL_FONT_SIZE
import org.androidaudioplugin.greenhouse.ui.components.CONTROL_SPACING
import org.androidaudioplugin.greenhouse.ui.components.ControlButton
import org.androidaudioplugin.greenhouse.ui.components.MidiDin5Icon
import org.androidaudioplugin.greenhouse.ui.components.controlContentColor
import org.androidaudioplugin.greenhouse.ui.components.controlSurface
import org.androidaudioplugin.greenhouse.ui.components.panelSurface
import org.androidaudioplugin.greenhouse.ui.host.AudioEngineController
import org.androidaudioplugin.greenhouse.ui.theme.*
import java.util.Locale

// Like the rack's
private val SCREEN_PADDING = 12.dp
private val SECTION_SPACING = 10.dp
private val SECTION_PADDING = 16.dp
private val SECTION_TITLE_SPACING = 12.dp
private val SECTION_ICON_SIZE = 18.dp
private val SECTION_ICON_SPACING = 8.dp
private val SECTION_TITLE_FONT_SIZE = 14.sp
private val SUBSECTION_SPACING = 14.dp
private val SUBSECTION_TITLE_SPACING = 6.dp
private val HEADER_BUTTON_SIZE = 38.dp
private val HEADER_ICON_SIZE = 18.dp
private val HEADER_TITLE_SPACING = 12.dp
private val HEADER_TITLE_FONT_SIZE = 16.sp
private val HEADER_SUBTITLE_FONT_SIZE = 11.sp
private val ROW_VERTICAL_PADDING = 5.dp
private val ROW_FONT_SIZE = 12.sp
private val ROW_LABEL_VALUE_SPACING = 8.dp
private const val ROW_VALUE_MAX_FRACTION = 0.6f
private val HINT_FONT_SIZE = 11.sp
private val HINT_LINE_HEIGHT = 16.sp
private val BUFFER_OPTION_HEIGHT = 48.dp
private val BUFFER_OPTION_SPACING = 6.dp
private val BUFFER_LATENCY_FONT_SIZE = 9.sp
private val BUFFER_LABEL_OFFSET = 4.dp
private val DEVICE_ROW_PADDING_HORIZONTAL = 12.dp
private val DEVICE_ROW_PADDING_VERTICAL = 8.dp
private val DEVICE_DETAILS_FONT_SIZE = 10.sp
private val CHIP_PADDING_HORIZONTAL = 8.dp
private val CHIP_PADDING_VERTICAL = 3.dp
private val CHIP_FONT_SIZE = 10.sp
private val WELL_PADDING_HORIZONTAL = 12.dp
private val WELL_PADDING_VERTICAL = 10.dp
private val MONOSPACE_FONT_SIZE = 11.sp
private val ACTION_HEIGHT = 40.dp
private const val MILLIS_PER_SECOND = 1000f
private const val CPU_HIGH_PERCENT = 80f
private const val CPU_MEDIUM_PERCENT = 50f
// The buffer profiles, by how many hardware bursts the render block holds
private const val LOW_LATENCY_MAX_BURSTS = 2
private const val BALANCED_MAX_BURSTS = 4
private const val SAFE_MAX_BURSTS = 8
private const val HIGH_HEADROOM_MAX_BURSTS = 16

@Composable
fun EngineSettingsScreen(
    viewModel: HostViewModel,
    onNavigateBack: () -> Unit
) {
    BackHandler {
        onNavigateBack()
    }

    val audio = viewModel.audio
    val isProcessing = audio.isProcessing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .verticalScroll(rememberScrollState())
            .padding(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)
    ) {
        // Header, like the plugin browser's
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(HEADER_BUTTON_SIZE)
                    .clip(CircleShape)
                    .background(StudioSurfaceElevated)
                    .clickable { onNavigateBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Studio Rack",
                    tint = TextPrimary,
                    modifier = Modifier.size(HEADER_ICON_SIZE)
                )
            }

            Spacer(modifier = Modifier.width(HEADER_TITLE_SPACING))

            Column {
                Text(
                    text = "Audio engine",
                    fontSize = HEADER_TITLE_FONT_SIZE,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                Text(
                    text = "Audio output, buffer size, plugins and MIDI devices",
                    fontSize = HEADER_SUBTITLE_FONT_SIZE,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Audio output and buffer size
        SettingsSection(title = "Audio output", icon = { SectionIcon(Icons.Default.GraphicEq) }) {
            val streamMode = audio.streamMode

            InfoRow(label = "Sample rate", value = "${audio.sampleRate} Hz")
            InfoRow(label = "Hardware burst", value = "${audio.actualBurstSize} frames")
            InfoRow(
                label = "Stream mode",
                value = streamModeLabel(streamMode),
                valueColor = if (streamMode.isMMapUsed) {
                    SproutGreen
                } else {
                    WarningOrange
                }
            )

            Subsection(title = "Render block size") {
                val burstMultiplier = if (audio.actualBurstSize > 0) {
                    audio.framesPerCallback / audio.actualBurstSize
                } else {
                    AudioEngineController.DEFAULT_BURST_MULTIPLIER
                }

                Row(horizontalArrangement = Arrangement.spacedBy(BUFFER_OPTION_SPACING)) {
                    for (multiplier in audio.availableBurstMultipliers) {
                        val frames = audio.actualBurstSize * multiplier
                        val isSelected = audio.framesPerCallback == frames
                        val contentColor = controlContentColor(isActive = isSelected)

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(BUFFER_OPTION_HEIGHT)
                                .controlSurface(isActive = isSelected)
                                .clickable { audio.setBufferFramesPerCallback(frames) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "${multiplier}x",
                                fontSize = CONTROL_LABEL_FONT_SIZE,
                                fontWeight = FontWeight.SemiBold,
                                style = tabularTextStyle,
                                color = contentColor,
                                // Closer to the latency under it
                                modifier = Modifier.offset(y = BUFFER_LABEL_OFFSET)
                            )

                            Text(
                                text = formatMillis(latencyMillis(frames, audio.sampleRate), decimals = 1),
                                fontSize = BUFFER_LATENCY_FONT_SIZE,
                                style = tabularTextStyle,
                                color = contentColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(SUBSECTION_TITLE_SPACING))

                Text(
                    text = "${audio.framesPerCallback} frames (${burstMultiplier}× burst): ${bufferProfile(burstMultiplier)}",
                    fontSize = HINT_FONT_SIZE,
                    lineHeight = HINT_LINE_HEIGHT,
                    color = TextSecondary
                )
            }

            Subsection(title = "Status") {
                InfoRow(
                    label = "Buffer latency",
                    value = formatMillis(latencyMillis(audio.framesPerCallback, audio.sampleRate), decimals = 2)
                )

                InfoRow(
                    label = "Audio processing",
                    value = if (isProcessing) {
                        "Running"
                    } else {
                        "Paused"
                    },
                    valueColor = if (isProcessing) {
                        SproutGreen
                    } else {
                        WarningOrange
                    }
                )

                InfoRow(
                    label = "App state",
                    value = when {
                        audio.isBackgrounded && audio.wasPlayingBeforeBackground -> "Background, resumes on return"
                        audio.isBackgrounded -> "Background, paused"
                        isProcessing -> "Foreground, running"
                        else -> "Foreground, idle"
                    },
                    valueColor = when {
                        isProcessing -> SproutGreen
                        audio.wasPlayingBeforeBackground -> PeriwinkleBlue
                        else -> TextSecondary
                    }
                )

                val totalCpu = viewModel.meters.totalCpuLoad

                InfoRow(
                    label = "Total DSP load",
                    value = if (isProcessing) {
                        formatPercent(totalCpu)
                    } else {
                        "Paused"
                    },
                    valueColor = when {
                        !isProcessing -> TextMuted
                        totalCpu > CPU_HIGH_PERCENT -> DangerRed
                        totalCpu > CPU_MEDIUM_PERCENT -> WarningOrange
                        else -> SproutGreen
                    }
                )

                for (slot in viewModel.rack.slots) {
                    val slotCpu = viewModel.meters.slotCpuLoads.getOrNull(slot.index) ?: 0f

                    InfoRow(
                        label = "${slot.title} (${slot.slotType.lowercase(Locale.US)})",
                        value = when {
                            slot.pluginInfo == null -> "Empty"
                            slot.isBypassed -> "Bypassed"
                            !isProcessing -> "Paused"
                            else -> formatPercent(slotCpu)
                        },
                        valueColor = if (slot.isBypassed) {
                            DangerRed
                        } else {
                            TextSecondary
                        }
                    )
                }
            }
        }

        // The active slot's plugin
        SettingsSection(title = "Active plugin", icon = { SectionIcon(Icons.Default.Info) }) {
            val activeSlot = viewModel.rack.activeSlot
            val activePlugin = activeSlot.pluginInfo

            if (activePlugin == null) {
                Text(
                    text = "No plugin loaded in ${activeSlot.title}.",
                    fontSize = ROW_FONT_SIZE,
                    color = TextMuted
                )
            } else {
                InfoRow(label = "Slot", value = "${activeSlot.title} (${activeSlot.slotType.lowercase(Locale.US)})")
                InfoRow(label = "Plugin", value = activePlugin.displayName)
                InfoRow(label = "Developer", value = activePlugin.developer ?: "Unknown")
                InfoRow(label = "Category", value = activePlugin.category ?: "Unspecified")
                InfoRow(label = "Plugin ID", value = activePlugin.pluginId ?: "N/A", isMonospace = true)
                InfoRow(label = "Package", value = activePlugin.packageName, isMonospace = true)
                InfoRow(label = "Parameters", value = "${activePlugin.parameters.size}")
                InfoRow(label = "Factory presets", value = "${activeSlot.presets.size}")

                if (activePlugin.ports.isNotEmpty()) {
                    Subsection(title = "Audio and MIDI ports (${activePlugin.ports.size})") {
                        activePlugin.ports.forEach { port ->
                            val direction = if (port.direction == 0) {
                                "In"
                            } else {
                                "Out"
                            }

                            InfoRow(label = port.name, value = "$direction · ${port.content}", valueColor = TextSecondary)
                        }
                    }
                }
            }
        }

        // Hardware MIDI controllers
        SettingsSection(
            title = "MIDI controllers",
            icon = { MidiDin5Icon(tint = SproutGreen, modifier = Modifier.size(SECTION_ICON_SIZE)) },
            trailing = {
                StatusChip(
                    text = if (viewModel.midi.isDeviceConnected) {
                        "Connected"
                    } else {
                        "Standby"
                    },
                    isOn = viewModel.midi.isDeviceConnected
                )
            }
        ) {
            val midi = viewModel.midi
            val activeDevice = midi.activeDevice

            if (activeDevice != null && midi.isDeviceConnected) {
                InfoRow(label = "Controller", value = MidiControllerManager.getDeviceDisplayName(activeDevice), valueColor = SproutGreen)
                InfoRow(label = "Manufacturer", value = MidiControllerManager.getDeviceManufacturer(activeDevice))
                InfoRow(label = "Output ports (to Greenhouse)", value = "${activeDevice.outputPortCount}")
                InfoRow(label = "Input ports", value = "${activeDevice.inputPortCount}")
            } else {
                Text(
                    text = "No MIDI keyboard or controller connected.",
                    fontSize = ROW_FONT_SIZE,
                    color = TextMuted
                )
            }

            Subsection(title = "Devices (${midi.availableDevices.size})") {
                if (midi.availableDevices.isEmpty()) {
                    Text(
                        text = "No USB, Bluetooth or virtual MIDI devices found. Connect a USB MIDI keyboard to play plugins live.",
                        fontSize = HINT_FONT_SIZE,
                        lineHeight = HINT_LINE_HEIGHT,
                        color = TextSecondary
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(BUFFER_OPTION_SPACING)) {
                        midi.availableDevices.forEach { device ->
                            val isInUse = device.id == activeDevice?.id && midi.isDeviceConnected

                            // Tap to use it, or to stop using it
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .controlSurface(isActive = isInUse)
                                    .clickable {
                                        if (isInUse) {
                                            midi.disconnect()
                                        } else {
                                            midi.selectDevice(device)
                                        }
                                    }
                                    .padding(horizontal = DEVICE_ROW_PADDING_HORIZONTAL, vertical = DEVICE_ROW_PADDING_VERTICAL),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = MidiControllerManager.getDeviceDisplayName(device),
                                        fontSize = ROW_FONT_SIZE,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isInUse) {
                                            SproutGreen
                                        } else {
                                            TextPrimary
                                        }
                                    )

                                    Text(
                                        text = "${MidiControllerManager.getDeviceManufacturer(device)} · ${device.outputPortCount} output port(s)",
                                        fontSize = DEVICE_DETAILS_FONT_SIZE,
                                        color = TextSecondary
                                    )
                                }

                                if (isInUse) {
                                    StatusChip(text = "In use", isOn = true)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(SUBSECTION_TITLE_SPACING))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { midi.updateShowVirtualDevices(!midi.showVirtualDevices) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Show virtual MIDI devices",
                        fontSize = ROW_FONT_SIZE,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )

                    Switch(
                        checked = midi.showVirtualDevices,
                        onCheckedChange = { midi.updateShowVirtualDevices(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StudioBackground,
                            checkedTrackColor = SproutGreen,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = StudioSurfaceVariant,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
            }

            Subsection(title = "Last MIDI event") {
                val lastEvent = midi.lastEventText

                Text(
                    text = lastEvent ?: "None yet",
                    fontSize = MONOSPACE_FONT_SIZE,
                    fontFamily = FontFamily.Monospace,
                    color = if (lastEvent != null) {
                        SproutGreen
                    } else {
                        TextMuted
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .controlSurface()
                        .padding(horizontal = WELL_PADDING_HORIZONTAL, vertical = WELL_PADDING_VERTICAL)
                )
            }

            Spacer(modifier = Modifier.height(SUBSECTION_SPACING))

            Row(horizontalArrangement = Arrangement.spacedBy(CONTROL_SPACING)) {
                ControlButton(
                    label = "Rescan devices",
                    icon = Icons.Default.Refresh,
                    height = ACTION_HEIGHT,
                    onClick = { midi.rescan() },
                    modifier = Modifier.weight(1f)
                )

                if (activeDevice != null && midi.isDeviceConnected) {
                    ControlButton(
                        label = "Disconnect",
                        icon = Icons.Default.LinkOff,
                        isActive = true,
                        accent = DangerRed,
                        height = ACTION_HEIGHT,
                        onClick = { midi.disconnect() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Plugins and the last status
        SettingsSection(title = "Plugins and status", icon = { SectionIcon(Icons.Default.Terminal) }) {
            Text(
                text = "> ${viewModel.statusMessage}",
                fontSize = MONOSPACE_FONT_SIZE,
                fontFamily = FontFamily.Monospace,
                color = SproutGreen,
                modifier = Modifier
                    .fillMaxWidth()
                    .controlSurface()
                    .padding(horizontal = WELL_PADDING_HORIZONTAL, vertical = WELL_PADDING_VERTICAL)
            )

            Spacer(modifier = Modifier.height(SUBSECTION_SPACING))

            ControlButton(
                label = "Rescan plugins",
                icon = Icons.Default.Refresh,
                isActive = true,
                height = ACTION_HEIGHT,
                onClick = { viewModel.browser.refresh() },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** A rack panel holding one group of settings, under its icon and title. */
@Composable
private fun SettingsSection(
    title: String,
    icon: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .panelSurface()
            .padding(SECTION_PADDING)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()

            Spacer(modifier = Modifier.width(SECTION_ICON_SPACING))

            Text(
                text = title,
                fontSize = SECTION_TITLE_FONT_SIZE,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )

            trailing?.invoke()
        }

        Spacer(modifier = Modifier.height(SECTION_TITLE_SPACING))

        content()
    }
}

@Composable
private fun SectionIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = SproutGreen,
        modifier = Modifier.size(SECTION_ICON_SIZE)
    )
}

/** A group inside a section, under a small title. */
@Composable
private fun Subsection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Spacer(modifier = Modifier.height(SUBSECTION_SPACING))

    Text(
        text = title,
        fontSize = CONTROL_LABEL_FONT_SIZE,
        fontWeight = FontWeight.Medium,
        color = TextMuted
    )

    Spacer(modifier = Modifier.height(SUBSECTION_TITLE_SPACING))

    Column(content = content)
}

/** A small status label, tinted while on. */
@Composable
private fun StatusChip(text: String, isOn: Boolean) {
    Text(
        text = text,
        fontSize = CHIP_FONT_SIZE,
        fontWeight = FontWeight.SemiBold,
        color = controlContentColor(isActive = isOn),
        modifier = Modifier
            .controlSurface(isActive = isOn)
            .padding(horizontal = CHIP_PADDING_HORIZONTAL, vertical = CHIP_PADDING_VERTICAL)
    )
}

/** A label, and its value flush right. A long value wraps within at most [ROW_VALUE_MAX_FRACTION] of the row. */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    isMonospace: Boolean = false
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ROW_VERTICAL_PADDING)
    ) {
        val valueMaxWidth = maxWidth * ROW_VALUE_MAX_FRACTION

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Takes what the value leaves
            Text(
                text = label,
                fontSize = ROW_FONT_SIZE,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(ROW_LABEL_VALUE_SPACING))

            Text(
                text = value,
                fontSize = ROW_FONT_SIZE,
                fontWeight = FontWeight.Medium,
                fontFamily = if (isMonospace) {
                    FontFamily.Monospace
                } else {
                    null
                },
                style = tabularTextStyle,
                color = valueColor,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = valueMaxWidth)
            )
        }
    }
}

private fun streamModeLabel(mode: OutputStreamMode): String {
    val performance = if (mode.isLowLatency) {
        "Low latency"
    } else {
        "Default"
    }
    val sharing = if (mode.isExclusive) {
        "exclusive"
    } else {
        "shared"
    }
    val path = if (mode.isMMapUsed) {
        "MMAP"
    } else {
        "legacy"
    }

    return "$performance, $sharing ($path)"
}

/** What a render block of [bursts] hardware bursts suits. */
private fun bufferProfile(bursts: Int): String {
    return when {
        bursts <= LOW_LATENCY_MAX_BURSTS -> "low latency, for a single synth"
        bursts <= BALANCED_MAX_BURSTS -> "balanced, the recommended default"
        bursts <= SAFE_MAX_BURSTS -> "safe, for effect chains"
        bursts <= HIGH_HEADROOM_MAX_BURSTS -> "high headroom, for heavy effects"
        else -> "maximum stability, for slower devices"
    }
}

private fun latencyMillis(frames: Int, sampleRate: Int): Float {
    return frames.toFloat() / sampleRate.toFloat() * MILLIS_PER_SECOND
}

private fun formatMillis(millis: Float, decimals: Int): String {
    return String.format(Locale.US, "%.${decimals}f ms", millis)
}

private fun formatPercent(percent: Float): String {
    return String.format(Locale.US, "%.1f%%", percent)
}
