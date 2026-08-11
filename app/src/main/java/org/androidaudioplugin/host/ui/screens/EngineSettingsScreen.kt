package org.androidaudioplugin.host.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.host.ui.HostViewModel
import org.androidaudioplugin.host.ui.theme.*

@Composable
fun EngineSettingsScreen(
    viewModel: HostViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "AUDIO ENGINE & DIAGNOSTICS",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            letterSpacing = 1.5.sp
        )
        Text(
            text = "System audio specs, Oboe buffer configuration & AAP status",
            fontSize = 12.sp,
            color = TextSecondary
        )

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
                InfoRow(label = "Buffer Frames per Callback", value = "${viewModel.framesPerCallback} frames")
                val estimatedLatencyMs = (viewModel.framesPerCallback.toFloat() / viewModel.sampleRate.toFloat()) * 1000.0f
                InfoRow(label = "Estimated Buffer Latency", value = String.format("%.2f ms", estimatedLatencyMs))
                InfoRow(label = "Audio Processing Active", value = if (viewModel.isProcessing) "YES (Oboe Active)" else "NO (Paused)", valueColor = if (viewModel.isProcessing) SignalGreen else WarningOrange)
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

                val activePlugin = viewModel.selectedPlugin
                if (activePlugin != null) {
                    InfoRow(label = "Loaded Plugin", value = activePlugin.displayName)
                    InfoRow(label = "Developer", value = activePlugin.developer ?: "Unknown")
                    InfoRow(label = "Plugin ID", value = activePlugin.pluginId ?: "N/A")
                    InfoRow(label = "Package Name", value = activePlugin.packageName)
                    InfoRow(label = "Parameters Count", value = "${activePlugin.parameters.size}")
                    InfoRow(label = "Ports Count", value = "${activePlugin.ports.size}")
                } else {
                    Text(
                        text = "No plugin currently loaded in session.",
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
                .weight(1f)
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
            color = TextSecondary
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}
