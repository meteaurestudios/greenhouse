package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.androidaudioplugin.greenhouse.core.MidiControllerManager
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.RackSlotData
import org.androidaudioplugin.greenhouse.ui.components.MidiDin5Icon
import org.androidaudioplugin.greenhouse.ui.theme.DangerRed
import org.androidaudioplugin.greenhouse.ui.theme.NeonCyan
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioBackground
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceElevated
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceVariant
import org.androidaudioplugin.greenhouse.ui.theme.TextMuted
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary
import org.androidaudioplugin.greenhouse.ui.theme.WarningOrange

private const val CPU_HIGH_THRESHOLD_PERCENT = 80f
private const val CPU_MEDIUM_THRESHOLD_PERCENT = 50f
private val DSP_PILL_PADDING_HORIZONTAL = 10.dp
private val DSP_PILL_PADDING_VERTICAL = 5.dp
private val DSP_PILL_SPACING = 6.dp
private val DSP_LED_SIZE = 6.dp
private val DSP_METER_BAR_WIDTH = 32.dp
private val DSP_METER_BAR_HEIGHT = 5.dp
private val DSP_PERCENT_TEXT_WIDTH = 28.dp

private val BANNER_LOGO_SIZE = 28.dp
private val BANNER_LOGO_ICON_SIZE = 16.dp
private val BANNER_SETTINGS_BUTTON_SIZE = 30.dp
private val BANNER_SETTINGS_ICON_SIZE = 16.dp
private val BANNER_CORNER_RADIUS = 20.dp
private val BANNER_SPACING = 10.dp

@Composable
fun MasterControlBanner(
    viewModel: HostViewModel,
    slots: List<RackSlotData>,
    isProcessing: Boolean,
    totalCpuLoadProvider: () -> Float,
    onToggleProcessing: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
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
                        cpuPercentProvider = totalCpuLoadProvider,
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

@Composable
fun DspCpuMeter(
    cpuPercentProvider: () -> Float,
    isProcessing: Boolean,
    onToggleProcessing: () -> Unit
) {
    val cpuPercent = cpuPercentProvider()

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

@Composable
fun MidiDeviceSelectionDialog(
    viewModel: HostViewModel,
    onDismiss: () -> Unit
) {
    val activeDev = viewModel.activeMidiDevice
    val isConnected = viewModel.isMidiDeviceConnected

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, StudioPanelBorder, RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = StudioSurface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(StudioSurfaceElevated)
                                .border(1.dp, SproutGreen.copy(alpha = 0.45f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            MidiDin5Icon(
                                tint = SproutGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "MIDI CONTROLLERS",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = SproutGreen,
                                letterSpacing = 0.5.sp
                            )

                            Spacer(modifier = Modifier.height(1.dp))

                            Text(
                                text = if (isConnected && activeDev != null) {
                                    "1 controller active"
                                } else {
                                    "${viewModel.availableMidiDevices.size} device(s) detected"
                                },
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(StudioSurfaceElevated)
                            .border(1.dp, StudioPanelBorder, CircleShape)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                HorizontalDivider(color = StudioPanelBorder, thickness = 1.dp)

                Spacer(modifier = Modifier.height(14.dp))

                // Active Controller Card
                if (activeDev != null && isConnected) {
                    val activeName = MidiControllerManager.getDeviceDisplayName(activeDev)
                    val activeDevMan = MidiControllerManager.getDeviceManufacturer(activeDev)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SproutGreen.copy(alpha = 0.08f))
                            .border(1.dp, SproutGreen.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ACTIVE CONTROLLER",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = SproutGreen,
                                    letterSpacing = 0.5.sp
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = activeName,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )

                                Text(
                                    text = "$activeDevMan • ${activeDev.outputPortCount} output port(s)",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }

                            Button(
                                onClick = { viewModel.disconnectMidiDevice() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DangerRed.copy(alpha = 0.15f),
                                    contentColor = DangerRed
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "DISCONNECT",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Show Virtual MIDI Devices Checkbox Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(StudioSurfaceVariant)
                        .border(1.dp, StudioPanelBorder, RoundedCornerShape(10.dp))
                        .clickable { viewModel.updateShowVirtualMidiDevices(!viewModel.showVirtualMidiDevices) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Show Virtual MIDI Devices",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )

                        Text(
                            text = "Include virtual & loopback MIDI endpoints",
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }

                    Checkbox(
                        checked = viewModel.showVirtualMidiDevices,
                        onCheckedChange = { viewModel.updateShowVirtualMidiDevices(it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = SproutGreen,
                            checkmarkColor = StudioBackground,
                            uncheckedColor = TextMuted
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "AVAILABLE MIDI INPUT DEVICES (${viewModel.availableMidiDevices.size})",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (viewModel.availableMidiDevices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(StudioSurfaceVariant)
                            .border(1.dp, StudioPanelBorder, RoundedCornerShape(10.dp))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No MIDI devices found.\nConnect a USB or Bluetooth MIDI keyboard to begin.",
                            fontSize = 12.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        viewModel.availableMidiDevices.forEach { dev ->
                            val isThisSelected = (dev.id == activeDev?.id) && isConnected
                            val dName = MidiControllerManager.getDeviceDisplayName(dev)
                            val dMan = MidiControllerManager.getDeviceManufacturer(dev)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isThisSelected) {
                                            SproutGreen.copy(alpha = 0.12f)
                                        } else {
                                            StudioSurfaceVariant
                                        }
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isThisSelected) {
                                            SproutGreen.copy(alpha = 0.6f)
                                        } else {
                                            StudioPanelBorder
                                        },
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        if (isThisSelected) {
                                            viewModel.disconnectMidiDevice()
                                        } else {
                                            viewModel.selectMidiDevice(dev)
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = dName,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isThisSelected) {
                                                SproutGreen
                                            } else {
                                                TextPrimary
                                            }
                                        )

                                        Spacer(modifier = Modifier.height(1.dp))

                                        Text(
                                            text = "$dMan • ${dev.outputPortCount} output port(s)",
                                            fontSize = 10.5.sp,
                                            color = TextSecondary
                                        )
                                    }

                                    if (isThisSelected) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(SproutGreen.copy(alpha = 0.2f))
                                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = "IN USE",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = SproutGreen
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { viewModel.rescanMidiDevices() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioSurfaceElevated,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = SproutGreen
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Text("RESCAN", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SproutGreen,
                            contentColor = StudioBackground
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("DONE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
