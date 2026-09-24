package org.androidaudioplugin.greenhouse.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.hosting.NativeRemotePluginInstance

private const val GUI_EXTENSION_URI_PREFIX = "urn://androidaudioplugin.org/extensions/gui"

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

val PluginInformation.hasCustomUi: Boolean
    get() = !uiViewFactory.isNullOrBlank() ||
            !uiWeb.isNullOrBlank() ||
            !uiActivity.isNullOrBlank() ||
            extensions.any { it.uri?.startsWith(GUI_EXTENSION_URI_PREFIX) == true }

data class PluginPreset(
    val nativeIndex: Int,
    val name: String
)

data class RackSlotData(
    val index: Int,
    val title: String,
    val slotType: String,
    val pluginInfo: PluginInformation? = null,
    val instance: NativeRemotePluginInstance? = null,
    val isBypassed: Boolean = false,
    val selectedPresetIndex: Int = -1,
    val presetCount: Int = 0,
    val presets: List<PluginPreset> = emptyList(),
    val isLoadingPresets: Boolean = false,
    val isLoading: Boolean = false,
    val loadingPluginName: String? = null
) {
    val presetNames: List<String>
        get() = presets.map { it.name }

    val hasCustomUi: Boolean
        get() = pluginInfo?.hasCustomUi == true

    val isLoaded: Boolean
        get() = instance != null && pluginInfo != null

    /** The same slot with no plugin in it, optionally showing [loadingPluginName] as being loaded. */
    fun cleared(loadingPluginName: String? = null): RackSlotData {
        return RackSlotData(
            index = index,
            title = title,
            slotType = slotType,
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
