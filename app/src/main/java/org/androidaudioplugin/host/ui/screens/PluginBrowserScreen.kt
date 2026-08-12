package org.androidaudioplugin.host.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.host.data.PluginCategory
import java.util.Locale
import org.androidaudioplugin.host.ui.HostViewModel
import org.androidaudioplugin.host.ui.theme.*

import androidx.compose.material.icons.automirrored.filled.ArrowBack

@Composable
fun PluginBrowserScreen(
    viewModel: HostViewModel,
    onNavigateToRack: () -> Unit
) {
    val targetSlot = viewModel.slots[viewModel.targetBrowserSlotIndex.coerceIn(0, 2)]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onNavigateToRack,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(StudioSurfaceVariant)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Rack",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "SELECT PLUGIN FOR ${targetSlot.title.uppercase(Locale.US)}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentGold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "${targetSlot.slotType} Slot • ${viewModel.filteredPlugins.size} AAP ${targetSlot.slotType.lowercase(Locale.US)} plugin(s) available",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            IconButton(
                onClick = { viewModel.refreshPluginList() },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(StudioSurfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Rescan Plugins",
                    tint = NeonCyan
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Search Bar
        OutlinedTextField(
            value = viewModel.searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            placeholder = { Text("Search by name, developer, or ID...", color = TextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = NeonCyan) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(StudioSurface),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = StudioPanelBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Manufacturer / Developer Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(viewModel.availableDevelopers) { dev ->
                val isSelected = viewModel.selectedDeveloper == dev
                Box(
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = if (isSelected) AccentGold else StudioPanelBorder,
                            shape = CircleShape
                        )
                        .clip(CircleShape)
                        .background(if (isSelected) Color(0xFF282D3B) else StudioSurface)
                        .clickable { viewModel.selectDeveloper(dev) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (dev == "ALL") "All Developers" else dev,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) TextPrimary else TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Plugin Catalog List
        if (viewModel.filteredPlugins.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = TextMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No plugins found matching criteria.",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(viewModel.filteredPlugins, key = { it.pluginId ?: it.displayName }) { plugin ->
                    val isSlotLoaded = targetSlot.pluginInfo?.pluginId == plugin.pluginId
                    PluginCard(
                        plugin = plugin,
                        isSelected = isSlotLoaded,
                        isInstantiating = viewModel.isInstantiating && isSlotLoaded,
                        onLoad = {
                            viewModel.loadPluginIntoSlot(viewModel.targetBrowserSlotIndex, plugin)
                            onNavigateToRack()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: PluginInformation,
    isSelected: Boolean,
    isInstantiating: Boolean,
    onLoad: () -> Unit
) {
    val catLower = plugin.category?.lowercase() ?: ""
    val tagColor = when {
        catLower.contains("synth") || catLower.contains("instrument") -> AccentViolet
        catLower.contains("effect") || catLower.contains("delay") || catLower.contains("reverb") || catLower.contains("chorus") -> AccentCyan
        else -> AccentGold
    }
    val tagBg = tagColor.copy(alpha = 0.12f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) AccentGold else StudioPanelBorder,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) StudioSurfaceVariant else StudioSurface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // Main Row: Title & Dev + Category & Load Action Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = plugin.displayName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .border(1.dp, tagColor.copy(alpha = 0.8f), CircleShape)
                                .clip(CircleShape)
                                .background(tagBg)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = (plugin.category ?: "plugin").lowercase(Locale.US),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = tagColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "${plugin.developer ?: "Unknown Vendor"} • ${plugin.parameters.size} params • ${plugin.ports.size} ports",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) SignalGreen.copy(alpha = 0.15f) else Color(0xFF252A36)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) SignalGreen else Color(0xFF3B4356),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable(enabled = !isInstantiating) { onLoad() }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isInstantiating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = if (isSelected) SignalGreen else TextPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = SignalGreen
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = if (isSelected) "LOADED" else "LOAD",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) SignalGreen else TextPrimary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Sub-row: Package Identifier
            Text(
                text = plugin.packageName,
                fontSize = 9.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SpecBadge(label: String, color: Color = NeonCyan) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}
