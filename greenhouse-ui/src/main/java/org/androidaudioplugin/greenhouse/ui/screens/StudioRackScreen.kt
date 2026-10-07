package org.androidaudioplugin.greenhouse.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.androidaudioplugin.greenhouse.device.aap.AapSlotDevice
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.StudioRackViewMode
import org.androidaudioplugin.greenhouse.ui.screens.rack.*
import org.androidaudioplugin.greenhouse.ui.theme.StudioBackground

private val RACK_OUTER_PADDING = 12.dp
private val RACK_ELEMENT_SPACING = 10.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioRackScreen(
    viewModel: HostViewModel,
    onNavigateToBrowser: () -> Unit,
    onNavigateToSettings: () -> Unit,
    /** Reports the header banner's height, so the load-error snackbar can cover it exactly. */
    onBannerHeightChanged: (Dp) -> Unit = {}
) {
    val density = LocalDensity.current
    val currentSlotIndex = viewModel.rack.activeSlotIndex
    val activeSlot = viewModel.rack.slots[currentSlotIndex]
    val activeDevice = activeSlot.device
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
                onOpenSessionDialog = { showSessionDialog = true },
                modifier = Modifier.onSizeChanged { size ->
                    onBannerHeightChanged(with(density) { size.height.toDp() })
                }
            )

            Spacer(modifier = Modifier.height(RACK_ELEMENT_SPACING))

            // Multi-Slot Audio Signal Chain Rack Header
            SignalRackHeader(
                slots = viewModel.rack.slots,
                activeSlotIndex = currentSlotIndex,
                isProcessing = viewModel.audio.isProcessing,
                slotCpuLoadsProvider = { viewModel.meters.slotCpuLoads },
                slotInvalidOutputProvider = { viewModel.meters.slotHasInvalidOutput },
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
                },
                onReloadSlot = { slotIdx ->
                    viewModel.rack.reloadCrashedSlot(slotIdx)
                }
            )

            Spacer(modifier = Modifier.height(RACK_ELEMENT_SPACING))
        }

        // Dynamic Main Panel (Parameter Controls / Native Embedded GUI / Presets), under the tabs of
        // the active slot's views. Folded (plugin UI full screen), the plugin UI stays alone.
        ModeTabbedPanel(
            activeSlot = activeSlot,
            currentMode = viewModel.rack.currentViewMode,
            showTabs = !isRackFolded,
            onModeSelected = { viewModel.rack.updateViewMode(it) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            key(activeSlot.index, activeDevice?.info?.key, activeSlot.parameters.size, activeSlot.isLoading) {

                if (activeSlot.isLoading) {
                    PluginLoadingView(
                        slot = activeSlot,
                        pluginName = activeSlot.loadingPluginName
                    )
                } else if (activeDevice == null) {
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
                                pluginId = activeDevice.info.key,
                                parameters = activeDevice.parameters,
                                parameterValues = viewModel.rack.slotUi[activeSlot.index].parameterValues,
                                gridState = viewModel.rack.slotUi[activeSlot.index].parameterGridState,
                                onValueChange = { param, valDouble ->
                                    viewModel.rack.setParameterValue(activeSlot.index, param, valDouble)
                                },
                                hostControl = { isCompact ->
                                    HostControl(
                                        slot = activeSlot,
                                        onLevelChange = { levelDb ->
                                            viewModel.rack.setSlotLevel(activeSlot.index, levelDb)
                                        },
                                        onMixChange = { mix ->
                                            viewModel.rack.setSlotMix(activeSlot.index, mix)
                                        },
                                        isCompact = isCompact
                                    )
                                }
                            )
                        }

                        StudioRackViewMode.NATIVE_SURFACE -> {
                            // Only AAP plugins have a UI to show here for now
                            if (activeDevice is AapSlotDevice) {
                                NativePluginSurfaceContainer(
                                    viewModel = viewModel,
                                    slot = activeSlot,
                                    device = activeDevice,
                                    isRackFolded = isRackFolded,
                                    onToggleFoldRack = { isRackFolded = !isRackFolded }
                                )
                            }
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
