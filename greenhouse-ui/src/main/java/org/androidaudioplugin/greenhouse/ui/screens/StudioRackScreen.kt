package org.androidaudioplugin.greenhouse.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.StudioRackViewMode
import org.androidaudioplugin.greenhouse.ui.screens.rack.*
import org.androidaudioplugin.greenhouse.ui.theme.StudioBackground
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface

private val RACK_OUTER_PADDING = 12.dp
private val RACK_ELEMENT_SPACING = 10.dp
private val MAIN_PANEL_CORNER_RADIUS = 20.dp
private val MAIN_PANEL_BORDER_WIDTH = 1.dp
private val MAIN_PANEL_INNER_PADDING = 12.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioRackScreen(
    viewModel: HostViewModel,
    onNavigateToBrowser: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val currentSlotIndex = viewModel.rack.activeSlotIndex
    val activeSlot = viewModel.rack.slots[currentSlotIndex]
    val activePlugin = activeSlot.pluginInfo
    var isRackFolded by remember { mutableStateOf(false) }
    var showSessionDialog by remember { mutableStateOf(false) }

    val importPresetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.sessions.importSessionFromUri(uri)
        }
    }

    BackHandler(enabled = isRackFolded) {
        isRackFolded = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(RACK_OUTER_PADDING)
    ) {
        if (!isRackFolded) {
            // Master Control Banner
            MasterControlBanner(
                viewModel = viewModel,
                slots = viewModel.rack.slots,
                isProcessing = viewModel.audio.isProcessing,
                totalCpuLoadProvider = { viewModel.meters.totalCpuLoad },
                onToggleProcessing = { viewModel.audio.togglePlayback() },
                onOpenSettings = onNavigateToSettings,
                onOpenSessionDialog = { showSessionDialog = true }
            )

            Spacer(modifier = Modifier.height(RACK_ELEMENT_SPACING))

            // Multi-Slot Audio Signal Chain Rack Header
            SignalRackHeader(
                slots = viewModel.rack.slots,
                activeSlotIndex = currentSlotIndex,
                isProcessing = viewModel.audio.isProcessing,
                slotCpuLoadsProvider = { viewModel.meters.slotCpuLoads },
                slotLevelsProvider = { viewModel.meters.slotLevels },
                onSelectSlot = { slotIdx ->
                    viewModel.rack.selectActiveSlot(slotIdx)
                },
                onAddPlugin = { slotIndex ->
                    viewModel.openBrowserForSlot(slotIndex)
                    onNavigateToBrowser()
                },
                onToggleBypass = { slotIdx ->
                    viewModel.rack.toggleSlotBypass(slotIdx)
                },
                onUnloadSlot = { slotIdx ->
                    viewModel.rack.unloadSlot(slotIdx)
                }
            )

            if (activeSlot.pluginInfo != null && !activeSlot.isLoading) {
                Spacer(modifier = Modifier.height(RACK_ELEMENT_SPACING))

                // View Mode Selector Bar for Active Slot
                StatusAndModeSelectorBar(
                    activeSlot = activeSlot,
                    currentMode = viewModel.rack.currentViewMode,
                    onModeSelected = { viewModel.rack.updateViewMode(it) }
                )
            }

            Spacer(modifier = Modifier.height(RACK_ELEMENT_SPACING))
        }

        // Dynamic Main Panel (Parameter Controls / Native Embedded GUI / Presets)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(MAIN_PANEL_CORNER_RADIUS))
                .background(StudioSurface)
                .border(MAIN_PANEL_BORDER_WIDTH, StudioPanelBorder, RoundedCornerShape(MAIN_PANEL_CORNER_RADIUS))
                .padding(MAIN_PANEL_INNER_PADDING)
        ) {
            key(activeSlot.index, activePlugin?.pluginId, activePlugin?.parameters?.size, activeSlot.isLoading) {

                if (activeSlot.isLoading) {
                    PluginLoadingView(
                        slot = activeSlot,
                        pluginName = activeSlot.loadingPluginName
                    )
                } else if (activePlugin == null) {
                    NoPluginInSlotView(
                        slot = activeSlot,
                        isProcessing = viewModel.audio.isProcessing,
                        onOpenBrowser = {
                            viewModel.openBrowserForSlot(activeSlot.index)
                            onNavigateToBrowser()
                        }
                    )
                } else {
                    when (viewModel.rack.currentViewMode) {
                        StudioRackViewMode.PARAMETERS -> {
                            ParameterControlRack(
                                slotIndex = activeSlot.index,
                                pluginId = activePlugin.pluginId ?: "",
                                parameters = activePlugin.parameters,
                                parameterValues = viewModel.rack.slotUi[activeSlot.index].parameterValues,
                                gridState = viewModel.rack.slotUi[activeSlot.index].parameterGridState,
                                onValueChange = { param, valDouble ->
                                    viewModel.rack.setParameterValue(activeSlot.index, param, valDouble)
                                }
                            )
                        }

                        StudioRackViewMode.NATIVE_SURFACE -> {
                            NativePluginSurfaceContainer(
                                viewModel = viewModel,
                                slot = activeSlot,
                                plugin = activePlugin,
                                isRackFolded = isRackFolded,
                                onToggleFoldRack = { isRackFolded = !isRackFolded }
                            )
                        }

                        StudioRackViewMode.PRESETS -> {
                            PluginPresetsView(
                                slot = activeSlot,
                                gridState = viewModel.rack.slotUi[activeSlot.index].presetGridState,
                                onPresetSelected = { idx ->
                                    viewModel.rack.setPreset(activeSlot.index, idx)
                                }
                            )
                        }
                    }
                }

            }
        }

        Spacer(modifier = Modifier.height(RACK_ELEMENT_SPACING))

        // On-screen Live Interactive MIDI Keyboard (Routes to Slot 0: Instrument)
        MidiKeyboardSection(viewModel = viewModel)
    }

    if (showSessionDialog) {
        RackSessionDialog(
            viewModel = viewModel,
            onDismissRequest = { showSessionDialog = false },
            onImportRequested = {
                showSessionDialog = false
                importPresetLauncher.launch(arrayOf("*/*", "application/json"))
            }
        )
    }
}
