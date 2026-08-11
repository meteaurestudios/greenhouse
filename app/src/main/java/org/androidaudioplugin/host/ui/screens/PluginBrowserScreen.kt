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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.host.data.PluginCategory
import org.androidaudioplugin.host.ui.HostViewModel
import org.androidaudioplugin.host.ui.theme.*

@Composable
fun PluginBrowserScreen(
    viewModel: HostViewModel,
    onNavigateToRack: () -> Unit
) {
    val categories = listOf(
        PluginCategory.ALL,
        PluginCategory.SYNTH,
        PluginCategory.EFFECT,
        PluginCategory.OTHER
    )

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
            Column {
                Text(
                    text = "PLUGIN BROWSER",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "${viewModel.filteredPlugins.size} AAP plugin(s) available",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
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
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
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

        // Category Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(categories) { category ->
                val isSelected = viewModel.selectedCategory.id == category.id
                FilterChip(
                    selected = isSelected,
                    onClick = { viewModel.selectCategory(category) },
                    label = { Text(category.title, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ElectricBlue,
                        selectedLabelColor = TextPrimary,
                        containerColor = StudioSurfaceVariant,
                        labelColor = TextSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = StudioPanelBorder,
                        selectedBorderColor = NeonCyan
                    )
                )
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(viewModel.filteredPlugins, key = { it.pluginId ?: it.displayName }) { plugin ->
                    PluginCard(
                        plugin = plugin,
                        isSelected = viewModel.selectedPlugin?.pluginId == plugin.pluginId,
                        isInstantiating = viewModel.isInstantiating && viewModel.selectedPlugin?.pluginId == plugin.pluginId,
                        onLoad = {
                            viewModel.loadPlugin(plugin)
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) NeonCyan else StudioPanelBorder,
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) StudioSurfaceVariant else StudioSurface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.displayName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = plugin.developer ?: "Unknown Developer",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                // Category Tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (plugin.category?.contains("Synth", ignoreCase = true) == true) NeonPurple.copy(alpha = 0.2f)
                            else ElectricBlue.copy(alpha = 0.2f)
                        )
                        .border(
                            1.dp,
                            if (plugin.category?.contains("Synth", ignoreCase = true) == true) NeonPurple else ElectricBlue,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = plugin.category ?: "Plugin",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Badges Row (Parameters, Ports, Package)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpecBadge(label = "${plugin.parameters.size} Params")
                SpecBadge(label = "${plugin.ports.size} Ports")
                if (plugin.extensions.isNotEmpty()) {
                    SpecBadge(label = "${plugin.extensions.size} Exts", color = SignalGreen)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = plugin.packageName,
                    fontSize = 10.sp,
                    color = TextMuted,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = onLoad,
                    enabled = !isInstantiating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) SignalGreen else NeonCyan,
                        contentColor = StudioBackground
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (isInstantiating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = StudioBackground,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.Check else Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSelected) "ACTIVE IN RACK" else "LOAD PLUGIN",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
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
