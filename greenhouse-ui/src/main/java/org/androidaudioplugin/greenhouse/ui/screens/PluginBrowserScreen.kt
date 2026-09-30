package org.androidaudioplugin.greenhouse.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.greenhouse.data.PluginCategory
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.host.PluginBrowserController
import org.androidaudioplugin.greenhouse.ui.components.ControlButton
import org.androidaudioplugin.greenhouse.ui.theme.*
import androidx.activity.compose.BackHandler
import java.util.Locale

@Composable
fun PluginBrowserScreen(
    viewModel: HostViewModel,
    onNavigateToRack: () -> Unit
) {
    BackHandler {
        onNavigateToRack()
    }

    val targetSlot = viewModel.rack.slots[viewModel.browser.targetSlotIndex.coerceIn(0, viewModel.rack.slots.lastIndex)]
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }

    // Edge to edge: the list scrolls under the navigation bar, its content inset above it
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomClearance = GET_PLUGINS_CLEARANCE + navigationBarPadding

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(start = SCREEN_PADDING, top = SCREEN_PADDING, end = SCREEN_PADDING)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(StudioSurfaceElevated)
                        .clickable { onNavigateToRack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Rack",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Plugins for ${targetSlot.title}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "${targetSlot.slotType} Slot • ${viewModel.browser.filteredPlugins.size} plugin(s) available",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(StudioSurfaceElevated)
                    .clickable { viewModel.browser.refresh() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Rescan Plugins",
                    tint = SproutGreen,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Search Bar with Frosted Botanical Pill Styling
        OutlinedTextField(
            value = viewModel.browser.searchQuery,
            onValueChange = { viewModel.browser.updateSearchQuery(it) },
            placeholder = { Text("Search plugins by name or vendor...", color = TextMuted, fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = SproutGreen, modifier = Modifier.size(20.dp)) },
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(StudioSurface),
            // Filled, without an outline, like the rack's controls
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
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
            items(viewModel.browser.availableDevelopers) { dev ->
                val isSelected = viewModel.browser.selectedDeveloper == dev

                ControlButton(
                    label = if (dev == PluginBrowserController.ALL_DEVELOPERS) "All Developers" else dev,
                    isActive = isSelected,
                    onClick = { viewModel.browser.selectDeveloper(dev) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Available Plugins List
        val isFiltering = viewModel.browser.searchQuery.isNotBlank() ||
                viewModel.browser.selectedDeveloper != PluginBrowserController.ALL_DEVELOPERS

        // Nothing for this slot: the Google Play button goes with the explanation instead of floating
        val isNothingInstalled = viewModel.browser.filteredPlugins.isEmpty() && !isFiltering

        // The list, with the Google Play button floating over its bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (isNothingInstalled) {
                NoPluginsInstalled(
                    hasAnyPlugin = viewModel.browser.plugins.isNotEmpty(),
                    slotType = targetSlot.slotType,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = navigationBarPadding)
                )
            } else if (viewModel.browser.filteredPlugins.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = bottomClearance),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Search,
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
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    // The last card scrolls clear of the floating button
                    contentPadding = PaddingValues(bottom = bottomClearance),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(viewModel.browser.filteredPlugins, key = { it.pluginId ?: it.displayName }) { plugin ->
                        val isSlotLoading = targetSlot.isLoading || viewModel.rack.isInstantiating

                        PluginCard(
                            plugin = plugin,
                            isEnabled = !isSlotLoading,
                            onLoad = {
                                viewModel.loadPluginIntoSlot(viewModel.browser.targetSlotIndex, plugin)
                                onNavigateToRack()
                            }
                        )
                    }
                }
            }

            if (!isNothingInstalled) {
                GetPluginsLink(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navigationBarPadding + GET_PLUGINS_BOTTOM_MARGIN)
                )
            }
        }
    }
}

/** Where to get AAP plugins. */
private const val AAP_PLUGINS_STORE_URL = "https://play.google.com/store/apps/developer?id=atsushieno"
private val GET_PLUGINS_SHAPE = RoundedCornerShape(12.dp)
private val GET_PLUGINS_ELEVATION = 8.dp
private val SCREEN_PADDING = 16.dp
// Between the explanation and the button under it, when no plugins are installed, both this wide at most
private val EMPTY_STATE_MAX_WIDTH = 300.dp
private val GET_PLUGINS_EMPTY_STATE_SPACING = 20.dp
// Above the navigation bar, as far as the screen's side padding
private val GET_PLUGINS_BOTTOM_MARGIN = 16.dp
// Room left under the list for the floating button: its height and margin, and a gap above it
private val GET_PLUGINS_CLEARANCE = 76.dp
private const val GET_PLUGINS_TINT_ALPHA = 0.15f

/** Empty state when no installed AAP plugin fits the slot: Greenhouse ships none, so point to where to get them. */
@Composable
private fun NoPluginsInstalled(
    hasAnyPlugin: Boolean,
    slotType: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Extension,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = TextMuted
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (hasAnyPlugin) {
                    "No ${slotType.lowercase(Locale.US)} plugins yet"
                } else {
                    "Let's find you some sounds"
                },
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // The explanation and the button as wide as each other
            Column(
                modifier = Modifier.widthIn(max = EMPTY_STATE_MAX_WIDTH),
                verticalArrangement = Arrangement.spacedBy(GET_PLUGINS_EMPTY_STATE_SPACING)
            ) {
                Text(
                    text = "Greenhouse hosts Audio Plugins for Android (AAP): synths and effects " +
                            "that you install as separate apps. Grab a few from the link below, " +
                            "then tap refresh and they'll show up here.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )

                GetPluginsLink(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Link to the AAP plugins catalog, so people can come back for more plugins: floating over the plugin
 * list, or under the explanation when none are installed. It fits its label unless modifier sizes it.
 */
@Composable
private fun GetPluginsLink(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current

    // Opaque, and lifted by a shadow: it floats over the cards scrolling under it
    Row(
        modifier = modifier
            .shadow(GET_PLUGINS_ELEVATION, GET_PLUGINS_SHAPE)
            .clip(GET_PLUGINS_SHAPE)
            .background(StudioSurfaceElevated)
            .background(SproutGreen.copy(alpha = GET_PLUGINS_TINT_ALPHA))
            .clickable { uriHandler.openUri(AAP_PLUGINS_STORE_URL) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = SproutGreen,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "Get AAP plugins on Google Play",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = SproutGreen
        )
    }
}

@Composable
private fun PluginCard(
    plugin: PluginInformation,
    isEnabled: Boolean = true,
    onLoad: () -> Unit
) {
    val catLower = plugin.category?.lowercase() ?: ""
    val tagColor = when {
        catLower.contains("synth") || catLower.contains("instrument") -> BlossomCoral
        catLower.contains("effect") || catLower.contains("delay") || catLower.contains("reverb") || catLower.contains("chorus") -> PeriwinkleBlue
        else -> SproutGreen
    }
    val tagBg = tagColor.copy(alpha = 0.15f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = StudioSurface
        )
    ) {
        // The plugin's name and details, and the load button centered against them
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
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
                            .clip(CircleShape)
                            .background(tagBg)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = (plugin.category ?: "plugin").lowercase(Locale.US),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = tagColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = plugin.developer ?: "Unknown Vendor",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            ControlButton(
                label = "Load",
                isActive = true,
                isEnabled = isEnabled,
                onClick = onLoad
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
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
