package org.androidaudioplugin.greenhouse.ui.host

import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.greenhouse.core.AapHostEngine
import org.androidaudioplugin.greenhouse.data.SlotState
import org.androidaudioplugin.greenhouse.ui.RackSlotData
import org.androidaudioplugin.greenhouse.ui.SlotUiState
import org.androidaudioplugin.greenhouse.ui.StudioRackViewMode
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import kotlin.math.abs

/**
 * The rack's slots (slot 0: instrument, slots 1..N-1: effects): plugin lifecycle per slot,
 * parameter / preset edits, and which slot and view the rack is showing.
 */
class RackController(
    private val hostEngine: AapHostEngine,
    private val audio: AudioEngineController,
    private val scope: CoroutineScope,
    private val postStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "RackController"
        const val NUM_RACK_SLOTS = 3
        const val INSTRUMENT_SLOT_INDEX = 0
        const val NO_PRESET_SELECTED = -1
        const val HOST_EDIT_IGNORE_MS = 600L
        const val PARAM_SYNC_EPSILON = 1e-4
    }

    val slots = mutableStateListOf<RackSlotData>().apply {
        for (i in 0 until NUM_RACK_SLOTS) {
            val slotType = if (i == INSTRUMENT_SLOT_INDEX) {
                "Instrument"
            } else {
                "Effect"
            }

            add(RackSlotData(i, "Slot ${i + 1}", slotType))
        }
    }

    val slotUi = List(NUM_RACK_SLOTS) { SlotUiState() }

    var activeSlotIndex by mutableIntStateOf(INSTRUMENT_SLOT_INDEX)
        private set

    var currentViewMode by mutableStateOf(StudioRackViewMode.PARAMETERS)
        private set

    var isInstantiating by mutableStateOf(false)
        private set

    val activeSlot: RackSlotData
        get() = slots[activeSlotIndex]

    val isEmpty: Boolean
        get() = slots.all { it.pluginInfo == null }

    fun isValidSlot(slotIndex: Int): Boolean {
        return slotIndex in 0 until NUM_RACK_SLOTS
    }

    fun selectActiveSlot(slotIndex: Int) {
        if (!isValidSlot(slotIndex)) {
            return
        }

        activeSlotIndex = slotIndex
        coerceViewModeToActiveSlot()
    }

    fun updateViewMode(mode: StudioRackViewMode) {
        currentViewMode = mode
    }

    /** Starts loading [plugin] into the slot; returns false if the rack is busy and nothing was started. */
    fun loadPlugin(slotIndex: Int, plugin: PluginInformation): Boolean {
        if (!isValidSlot(slotIndex)) {
            return false
        }

        if (slots[slotIndex].isLoading || isInstantiating) {
            return false
        }

        activeSlotIndex = slotIndex
        isInstantiating = true
        postStatus("Instantiating ${plugin.displayName} in ${slots[slotIndex].title}...")
        releaseSlot(slotIndex, loadingPluginName = plugin.displayName)

        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    PluginSlotLoader.instantiate(hostEngine, slotIndex, plugin, audio.sampleRate, audio.framesPerCallback)
                }

                attachLoadedPlugin(
                    slotIndex = slotIndex,
                    plugin = plugin,
                    loaded = loaded,
                    isBypassed = false,
                    selectedPresetIndex = NO_PRESET_SELECTED,
                    displayedValues = loaded.parameterValues
                )

                activeSlotIndex = slotIndex
                coerceViewModeToActiveSlot()
                audio.ensureRunning()
                postStatus("Loaded ${plugin.displayName} into ${slots[slotIndex].title} (${slots[slotIndex].slotType})")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load plugin ${plugin.displayName}", e)
                postStatus("Error loading plugin: ${e.localizedMessage ?: e.message}")
                slots[slotIndex] = slots[slotIndex].copy(isLoading = false, loadingPluginName = null)
            } finally {
                isInstantiating = false
            }
        }

        return true
    }

    /**
     * Re-creates a saved slot: instantiates [plugin], applies the saved state chunk and preset,
     * then pushes the saved parameter values. Returns false if the plugin couldn't be instantiated.
     */
    suspend fun restoreSlot(state: SlotState, plugin: PluginInformation): Boolean {
        val slotIndex = state.slotIndex
        slots[slotIndex] = slots[slotIndex].copy(isLoading = true, loadingPluginName = plugin.displayName)

        try {
            val loaded = withContext(Dispatchers.IO) {
                PluginSlotLoader.instantiate(hostEngine, slotIndex, plugin, audio.sampleRate, audio.framesPerCallback) { instance ->
                    applySavedState(slotIndex, instance, state)
                }
            }

            attachLoadedPlugin(
                slotIndex = slotIndex,
                plugin = plugin,
                loaded = loaded,
                isBypassed = state.isBypassed,
                selectedPresetIndex = state.selectedPresetIndex,
                displayedValues = state.parameters.ifEmpty { loaded.parameterValues }
            )

            for ((paramId, value) in state.parameters) {
                val param = plugin.parameters.find { it.id == paramId }

                if (param != null) {
                    audio.player.setParameterValue(slotIndex, param, value)
                }
            }

            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to instantiate plugin for slot $slotIndex during session restore", e)
            slots[slotIndex] = slots[slotIndex].copy(isLoading = false, loadingPluginName = null)
            return false
        }
    }

    fun unloadSlot(slotIndex: Int) {
        if (!isValidSlot(slotIndex)) {
            return
        }

        releaseSlot(slotIndex, loadingPluginName = null)
        coerceViewModeToActiveSlot()
        postStatus("Cleared ${slots[slotIndex].title}")
    }

    fun unloadAll() {
        for (i in 0 until NUM_RACK_SLOTS) {
            unloadSlot(i)
        }
    }

    fun toggleSlotBypass(slotIndex: Int) {
        if (!isValidSlot(slotIndex) || slots[slotIndex].pluginInfo == null) {
            return
        }

        val newBypass = !slots[slotIndex].isBypassed
        slots[slotIndex] = slots[slotIndex].copy(isBypassed = newBypass)
        audio.player.setSlotBypassed(slotIndex, newBypass)

        val state = if (newBypass) {
            "BYPASSED"
        } else {
            "ACTIVE"
        }

        postStatus("${slots[slotIndex].title} $state")
    }

    fun setParameterValue(slotIndex: Int, parameter: ParameterInformation, value: Double) {
        if (!isValidSlot(slotIndex)) {
            return
        }

        val ui = slotUi[slotIndex]
        ui.lastHostEditTimestamps[parameter.id] = System.currentTimeMillis()
        ui.parameterValues[parameter.id] = value
        audio.player.setParameterValue(slotIndex, parameter, value)
    }

    fun setPreset(slotIndex: Int, nativeIndex: Int) {
        if (!isValidSlot(slotIndex)) {
            return
        }

        val slot = slots[slotIndex]

        if (slot.presets.isEmpty()) {
            return
        }

        val targetPreset = slot.presets.find { it.nativeIndex == nativeIndex } ?: slot.presets.first()
        slots[slotIndex] = slot.copy(selectedPresetIndex = targetPreset.nativeIndex)
        audio.player.setPresetIndex(slotIndex, targetPreset.nativeIndex)
        syncParametersFromPlugin(slotIndex)
        postStatus("${slot.title} Preset: ${targetPreset.name}")
    }

    /** Snapshot of every slot for session persistence, including each plugin's opaque state chunk. */
    fun captureSlotStates(): List<SlotState> {
        return slots.map { slot ->
            val instance = slot.instance
            val plugin = slot.pluginInfo

            if (instance != null && plugin != null) {
                SlotState(
                    slotIndex = slot.index,
                    slotType = slot.slotType,
                    pluginId = plugin.pluginId,
                    packageName = plugin.packageName,
                    displayName = plugin.displayName,
                    isBypassed = slot.isBypassed,
                    selectedPresetIndex = slot.selectedPresetIndex,
                    stateDataBase64 = captureStateChunk(slot.index, instance),
                    parameters = slotUi[slot.index].parameterValues.toMap()
                )
            } else {
                SlotState(
                    slotIndex = slot.index,
                    slotType = slot.slotType,
                    pluginId = null,
                    packageName = null,
                    displayName = null,
                    isBypassed = slot.isBypassed,
                    selectedPresetIndex = NO_PRESET_SELECTED,
                    stateDataBase64 = null,
                    parameters = emptyMap()
                )
            }
        }
    }

    /**
     * Picks up parameter changes made on the plugin side (its own UI, presets, automation).
     * Reads the instances on the calling thread; publishes changed values on Main.
     */
    suspend fun pollPluginParameterChanges() {
        val now = System.currentTimeMillis()
        val updates = mutableListOf<Triple<Int, Int, Double>>()

        for (slot in slots.toList()) {
            val instance = slot.instance
            val plugin = slot.pluginInfo

            if (instance == null || plugin == null) {
                continue
            }

            val ui = slotUi[slot.index]

            for ((i, param) in plugin.parameters.withIndex()) {
                val lastEdit = ui.lastHostEditTimestamps[param.id] ?: 0L

                if (now - lastEdit < HOST_EDIT_IGNORE_MS) {
                    continue
                }

                val currentVal = try {
                    PluginSlotLoader.queryIfAlive<Double?>(instance, null) { instance.getParameterValue(i) }
                } catch (e: Throwable) {
                    null
                } ?: continue

                val previousVal = ui.lastPluginValues[param.id]

                if (previousVal == null) {
                    ui.lastPluginValues[param.id] = currentVal
                } else if (abs(currentVal - previousVal) > PARAM_SYNC_EPSILON) {
                    ui.lastPluginValues[param.id] = currentVal
                    updates.add(Triple(slot.index, param.id, currentVal))
                }
            }
        }

        if (updates.isEmpty()) {
            return
        }

        withContext(Dispatchers.Main) {
            for ((slotIndex, paramId, value) in updates) {
                slotUi[slotIndex].parameterValues[paramId] = value
            }
        }
    }

    /** Detaches and destroys whatever is in the slot and resets its UI state. */
    private fun releaseSlot(slotIndex: Int, loadingPluginName: String?) {
        val currentInstance = slots[slotIndex].instance
        audio.player.setSlotBypassed(slotIndex, false)
        audio.player.setSlotPlugin(slotIndex, null)

        if (currentInstance != null) {
            try {
                PluginSlotLoader.destroy(currentInstance)
            } catch (e: Throwable) {
                Log.e(TAG, "Error destroying plugin instance in slot $slotIndex", e)
            }
        }

        hostEngine.unloadSlot(slotIndex)
        slots[slotIndex] = slots[slotIndex].cleared(loadingPluginName)
        slotUi[slotIndex].reset()
    }

    private fun attachLoadedPlugin(
        slotIndex: Int,
        plugin: PluginInformation,
        loaded: LoadedPlugin,
        isBypassed: Boolean,
        selectedPresetIndex: Int,
        displayedValues: Map<Int, Double>
    ) {
        val ui = slotUi[slotIndex]
        ui.lastPluginValues.clear()
        ui.lastPluginValues.putAll(loaded.parameterValues)
        ui.parameterValues.clear()
        ui.parameterValues.putAll(displayedValues)

        val hasPresetList = loaded.presetCount > 1

        slots[slotIndex] = slots[slotIndex].copy(
            pluginInfo = plugin,
            instance = loaded.instance,
            isBypassed = isBypassed,
            selectedPresetIndex = selectedPresetIndex,
            presetCount = loaded.presetCount,
            presets = emptyList(),
            isLoadingPresets = hasPresetList,
            isLoading = false,
            loadingPluginName = null
        )

        audio.player.setSlotPlugin(slotIndex, loaded.instance, loaded.client)
        audio.player.setSlotBypassed(slotIndex, isBypassed)

        if (hasPresetList) {
            fetchPresetNames(slotIndex, loaded.instance, loaded.presetCount)
        }
    }

    /** Preset names can be slow to enumerate, so they fill in after the plugin is already playing. */
    private fun fetchPresetNames(slotIndex: Int, instance: NativeRemotePluginInstance, presetCount: Int) {
        scope.launch {
            val presets = withContext(Dispatchers.IO) {
                PluginSlotLoader.readPresets(instance, presetCount)
            }

            // The slot may have been reloaded while names were being fetched.
            if (slots[slotIndex].instance == instance) {
                slots[slotIndex] = slots[slotIndex].copy(presets = presets, isLoadingPresets = false)
            }
        }
    }

    private fun syncParametersFromPlugin(slotIndex: Int) {
        val slot = slots[slotIndex]
        val instance = slot.instance
        val plugin = slot.pluginInfo

        if (instance == null || plugin == null) {
            return
        }

        scope.launch {
            val values = withContext(Dispatchers.IO) {
                PluginSlotLoader.readParameterValues(plugin, instance)
            }

            val ui = slotUi[slotIndex]

            for (param in plugin.parameters) {
                val value = values[param.id] ?: continue
                ui.parameterValues[param.id] = value
                ui.lastPluginValues[param.id] = value
                audio.player.setParameterValue(slotIndex, param, value)
            }
        }
    }

    private fun applySavedState(slotIndex: Int, instance: NativeRemotePluginInstance, state: SlotState) {
        val stateData = state.stateDataBase64

        if (!stateData.isNullOrBlank()) {
            try {
                val stateBytes = Base64.decode(stateData, Base64.DEFAULT)

                if (stateBytes.isNotEmpty()) {
                    instance.setState(stateBytes)
                    Log.d(TAG, "Restored setState (${stateBytes.size} bytes) for slot $slotIndex")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to apply setState for slot $slotIndex", e)
            }
        }

        if (state.selectedPresetIndex >= 0) {
            try {
                instance.setCurrentPresetIndex(state.selectedPresetIndex)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to restore presetIndex for slot $slotIndex", e)
            }
        }
    }

    private fun captureStateChunk(slotIndex: Int, instance: NativeRemotePluginInstance): String? {
        return try {
            val stateSize = instance.getStateSize()

            if (stateSize > 0) {
                val stateBuffer = ByteArray(stateSize)
                instance.getState(stateBuffer)
                Base64.encodeToString(stateBuffer, Base64.NO_WRAP)
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to capture getState for slot $slotIndex", e)
            null
        }
    }

    private fun coerceViewModeToActiveSlot() {
        val slot = activeSlot
        val isModeUnsupported = when (currentViewMode) {
            StudioRackViewMode.PRESETS -> slot.presetCount <= 1
            StudioRackViewMode.NATIVE_SURFACE -> !slot.hasCustomUi
            StudioRackViewMode.PARAMETERS -> false
        }

        if (isModeUnsupported) {
            currentViewMode = StudioRackViewMode.PARAMETERS
        }
    }
}
