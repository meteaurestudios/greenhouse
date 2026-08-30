package org.androidaudioplugin.host.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.host.ui.HostViewModel
import org.androidaudioplugin.host.ui.theme.*
import java.util.Locale

@Composable
fun EngineSettingsScreen(
    viewModel: HostViewModel,
    onNavigateBack: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Header with Back Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(StudioSurfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Studio Rack",
                    tint = TextPrimary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "AUDIO ENGINE & DIAGNOSTICS",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    letterSpacing = 1.2.sp
                )
                Text(
                    text = "System audio specs, Oboe buffer configuration & AAP status",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Hardware Audio Performance Card
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
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = NeonCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AUDIO HARDWARE SPECIFICATIONS",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                InfoRow(label = "Output Sample Rate", value = "${viewModel.sampleRate} Hz")
                InfoRow(label = "Hardware Burst Quantum", value = "${viewModel.actualBurstSize} frames")
                InfoRow(label = "Audio Stream Mode", value = "LowLatency Exclusive", valueColor = NeonCyan)

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(StudioSurfaceVariant)
                        .border(1.dp, StudioPanelBorder, RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "FIFO Render Block Size",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        val activeMultiplier = if (viewModel.actualBurstSize > 0) {
                            viewModel.framesPerCallback / viewModel.actualBurstSize
                        } else {
                            4
                        }
                        Text(
                            text = "${viewModel.framesPerCallback} frames (${activeMultiplier}x Burst)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ElectricBlue
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (multiplier in viewModel.availableBurstMultipliers) {
                            val frames = viewModel.actualBurstSize * multiplier
                            val isSelected = (viewModel.framesPerCallback == frames)
                            val latencyMs = (frames.toFloat() / viewModel.sampleRate.toFloat()) * 1000.0f
                            val label = "${multiplier}x"

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) ElectricBlue else StudioSurface)
                                    .border(
                                        1.dp,
                                        if (isSelected) ElectricBlue else StudioPanelBorder,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        viewModel.setBufferFramesPerCallback(frames)
                                    }
                                    .padding(vertical = 8.dp, horizontal = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) TextPrimary else TextSecondary
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.1fms", latencyMs),
                                        fontSize = 9.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) TextPrimary.copy(alpha = 0.9f) else TextMuted
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    val profileDesc = when {
                        viewModel.framesPerCallback <= viewModel.actualBurstSize * 2 -> "Low latency (recommended for single synth)"
                        viewModel.framesPerCallback <= viewModel.actualBurstSize * 4 -> "Balanced (recommended default - responsive & glitch-free)"
                        viewModel.framesPerCallback <= viewModel.actualBurstSize * 8 -> "Safe (great for multi-FX rack chains)"
                        viewModel.framesPerCallback <= viewModel.actualBurstSize * 16 -> "High Headroom (optimal for heavy DSP & reverbs)"
                        else -> "Maximum Stability (minimal CPU overhead / budget devices)"
                    }

                    Text(
                        text = "• Profile: $profileDesc",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                val estimatedLatencyMs = (viewModel.framesPerCallback.toFloat() / viewModel.sampleRate.toFloat()) * 1000.0f
                InfoRow(label = "Estimated Buffer Latency", value = String.format(Locale.US, "%.2f ms", estimatedLatencyMs))
                InfoRow(label = "Audio Processing Active", value = if (viewModel.isProcessing) "YES (Oboe Active)" else "NO (Paused)", valueColor = if (viewModel.isProcessing) SignalGreen else WarningOrange)

                val cpuVal = viewModel.totalCpuLoad
                val cpuColor = when {
                    !viewModel.isProcessing -> TextMuted
                    cpuVal > 80f -> DangerRed
                    cpuVal > 50f -> WarningOrange
                    else -> SignalGreen
                }
                InfoRow(
                    label = "Total Engine DSP Load",
                    value = if (viewModel.isProcessing) "${String.format("%.1f", cpuVal)}%" else "0.0% (Engine Paused)",
                    valueColor = cpuColor
                )

                for (slot in viewModel.slots) {
                    val slotCpu = viewModel.slotCpuLoads.getOrNull(slot.index) ?: 0f
                    val slotText = if (!viewModel.isProcessing) {
                        "0.0%"
                    } else if (slot.pluginInfo == null) {
                        "Empty Slot"
                    } else if (slot.isBypassed) {
                        "Bypassed"
                    } else {
                        "${String.format("%.1f", slotCpu)}%"
                    }

                    InfoRow(
                        label = "└─ ${slot.title} (${slot.slotType}) DSP",
                        value = slotText,
                        valueColor = if (slot.isBypassed) DangerRed else TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Active Plugin Info Card
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
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = ElectricBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ACTIVE INSTANCE STATUS",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                val activeSlot = viewModel.activeSlot
                val activePlugin = activeSlot.pluginInfo

                if (activePlugin != null) {
                    InfoRow(label = "Active Slot Focus", value = "${activeSlot.title} (${activeSlot.slotType})")
                    InfoRow(label = "Loaded Plugin", value = activePlugin.displayName)
                    InfoRow(label = "Developer", value = activePlugin.developer ?: "Unknown")
                    InfoRow(label = "Plugin ID", value = activePlugin.pluginId ?: "N/A")
                    InfoRow(label = "Package Name", value = activePlugin.packageName)
                    InfoRow(label = "Parameters Count", value = "${activePlugin.parameters.size}")
                    InfoRow(label = "Ports Count", value = "${activePlugin.ports.size}")
                } else {
                    Text(
                        text = "No plugin currently loaded in ${activeSlot.title} (${activeSlot.slotType}).",
                        fontSize = 13.sp,
                        color = TextMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // System Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { viewModel.refreshPluginList() },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = TextPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("RESCAN PLUGINS", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Diagnostic Console Output
        Text(
            text = "SYSTEM LOG CONSOLE",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextSecondary,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(StudioSurfaceVariant)
                .border(1.dp, StudioPanelBorder, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        text = "> ${viewModel.statusMessage ?: "System ready."}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = SignalGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = TextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.5f, fill = false)
        )
    }
}
