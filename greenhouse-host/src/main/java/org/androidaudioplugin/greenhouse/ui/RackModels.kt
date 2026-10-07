package org.androidaudioplugin.greenhouse.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.greenhouse.data.SlotHostSettings
import org.androidaudioplugin.greenhouse.device.DeviceInfo
import org.androidaudioplugin.greenhouse.device.DevicePreset
import org.androidaudioplugin.greenhouse.device.SlotDevice

enum class StudioRackViewMode(val title: String) {
    PARAMETERS("Parameters"),
    NATIVE_SURFACE("Plugin UI"),
    PRESETS("Presets")
}

class SlotNativeUiZoomState {
    var isFitMode by mutableStateOf(true)
    var currentScale by mutableFloatStateOf(1.0f)
    var panOffsetX by mutableFloatStateOf(0f)
    var panOffsetY by mutableFloatStateOf(0f)
    var isMoveMode by mutableStateOf(false)

    fun reset() {
        isFitMode = true
        currentScale = 1.0f
        panOffsetX = 0f
        panOffsetY = 0f
        isMoveMode = false
    }
}

data class RackSlotData(
    val index: Int,
    val title: String,
    val slotType: String,
    /** What fills the slot. Kept when it crashed ([isCrashed]), already released, so it can be reloaded. */
    val device: SlotDevice? = null,
    val isBypassed: Boolean = false,
    /** Host level applied to the slot's output; back to its default when a new plugin is added. */
    val levelDb: Float = SlotHostSettings.DEFAULT_LEVEL_DB,
    /** Host dry / wet mix of the slot; back to fully wet when a new plugin is added. */
    val mix: Float = SlotHostSettings.INITIAL_MIX,
    val selectedPresetIndex: Int = -1,
    val presetCount: Int = 0,
    val presets: List<DevicePreset> = emptyList(),
    val isLoadingPresets: Boolean = false,
    val isLoading: Boolean = false,
    val loadingPluginName: String? = null,
    /** The device died (e.g. its plugin's process): [device] and the parameter values are kept so it can be reloaded. */
    val isCrashed: Boolean = false,
    /** Bumped when the device's parameter list changes, so views showing it update. */
    val parametersRevision: Int = 0
) {
    val presetNames: List<String>
        get() = presets.map { it.name }

    val deviceInfo: DeviceInfo?
        get() = device?.info

    val parameters: List<ParameterInformation>
        get() = device?.parameters ?: emptyList()

    /** A crashed device has nothing left to show its UI. */
    val hasCustomUi: Boolean
        get() = device?.hasCustomUi == true && !isCrashed

    /** Holds a working device (not crashed). */
    val isLoaded: Boolean
        get() = device != null && !isCrashed

    /** The same slot with no plugin in it (host level and mix kept, e.g. to reload a crashed plugin), optionally showing [loadingPluginName] as being loaded. */
    fun cleared(loadingPluginName: String? = null): RackSlotData {
        return RackSlotData(
            index = index,
            title = title,
            slotType = slotType,
            levelDb = levelDb,
            mix = mix,
            isLoading = loadingPluginName != null,
            loadingPluginName = loadingPluginName
        )
    }
}

data class SlotLevel(
    val left: Float = 0f,
    val right: Float = 0f
)

/** Per-slot UI and parameter-sync state that lives beside [RackSlotData] but outside of it. */
class SlotUiState {
    /** Parameter values shown by the UI, keyed by parameter ID. */
    val parameterValues = mutableStateMapOf<Int, Double>()

    /** Last values read back from the plugin, used to detect changes made from the plugin's own UI. */
    val lastPluginValues = mutableMapOf<Int, Double>()

    /** When the host last edited each parameter, so plugin read-back doesn't fight the user's gesture. */
    val lastHostEditTimestamps = mutableMapOf<Int, Long>()

    val nativeUiZoom = SlotNativeUiZoomState()

    var parameterGridState by mutableStateOf(LazyGridState())
        private set

    var presetGridState by mutableStateOf(LazyGridState())
        private set

    fun reset() {
        parameterValues.clear()
        lastPluginValues.clear()
        lastHostEditTimestamps.clear()
        nativeUiZoom.reset()
        parameterGridState = LazyGridState()
        presetGridState = LazyGridState()
    }
}
