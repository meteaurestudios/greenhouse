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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.greenhouse.core.AapHostEngine
import org.androidaudioplugin.greenhouse.data.SlotHostSettings
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

    private val loadErrorEvents = Channel<String>(Channel.BUFFERED)

    /** One message per plugin that failed to load into a slot, for the UI to show once. */
    val loadErrors: Flow<String> = loadErrorEvents.receiveAsFlow()

    /** Serializes preset changes per slot so they reach the plugin in tap order. */
    private val presetLocks = List(NUM_RACK_SLOTS) { Mutex() }

    /** Latest preset request per slot; read-backs from superseded requests are dropped. */
    private val presetRequestIds = IntArray(NUM_RACK_SLOTS)

    var activeSlotIndex by mutableIntStateOf(INSTRUMENT_SLOT_INDEX)
        private set

    var currentViewMode by mutableStateOf(StudioRackViewMode.PARAMETERS)
        private set

    var isInstantiating by mutableStateOf(false)
        private set

    /** Bumped by every edit to the rack's content, so the session can tell whether it has unsaved changes. */
    var revision by mutableIntStateOf(0)
        private set

    val activeSlot: RackSlotData
        get() = slots[activeSlotIndex]

    val isEmpty: Boolean
        get() = slots.all { it.pluginInfo == null }

    init {
        hostEngine.onSlotPluginDied = { slotIndex, instance ->
            // Right away: the main thread may be about to query the dead instance (e.g. an autosave),
            // and aap-core waits for plugin replies without a timeout
            PluginSlotLoader.abandon(instance)

            scope.launch(Dispatchers.Main) {
                onPluginProcessDied(slotIndex, instance)
            }
        }
    }

    fun isValidSlot(slotIndex: Int): Boolean {
        return slotIndex in 0 until NUM_RACK_SLOTS
    }

    fun hasPluginsPreparedAtOtherRate(sampleRate: Int): Boolean {
        return slots.any { it.isLoaded && it.preparedSampleRate != sampleRate }
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
        // A newly added plugin starts from the default level, fully wet
        applyHostSettings(slotIndex, SlotHostSettings.DEFAULT_LEVEL_DB, SlotHostSettings.INITIAL_MIX)

        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    PluginSlotLoader.instantiate(hostEngine, slotIndex, plugin, audio.sampleRate, audio.framesPerCallback)
                }

                if (loaded == null) {
                    reportLoadFailure(slotIndex, plugin)
                    return@launch
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
                markChanged()
                audio.ensureRunning()
                postStatus("Loaded ${plugin.displayName} into ${slots[slotIndex].title} (${slots[slotIndex].slotType})")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load plugin ${plugin.displayName}", e)
                reportLoadFailure(slotIndex, plugin)
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

            if (loaded == null) {
                reportLoadFailure(slotIndex, plugin)
                return false
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
                    audio.engine.setParameterValue(slotIndex, param, value)
                }
            }

            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to instantiate plugin for slot $slotIndex during session restore", e)
            reportLoadFailure(slotIndex, plugin)
            return false
        }
    }

    /** Takes [slotIndex] out of its loading state and tells the user [plugin] couldn't be loaded. */
    private fun reportLoadFailure(slotIndex: Int, plugin: PluginInformation) {
        val message = "${plugin.displayName} failed to load"
        postStatus(message)
        loadErrorEvents.trySend(message)
        slots[slotIndex] = slots[slotIndex].copy(isLoading = false, loadingPluginName = null)
    }

    fun unloadSlot(slotIndex: Int) {
        if (!isValidSlot(slotIndex)) {
            return
        }

        releaseSlot(slotIndex, loadingPluginName = null)
        coerceViewModeToActiveSlot()
        markChanged()
        postStatus("Cleared ${slots[slotIndex].title}")
    }

    /** Empties every slot and puts its host level and mix back to where a new slot starts. */
    fun unloadAll() {
        for (i in 0 until NUM_RACK_SLOTS) {
            unloadSlot(i)
            applyHostSettings(i, SlotHostSettings.DEFAULT_LEVEL_DB, SlotHostSettings.INITIAL_MIX)
        }
    }

    /** Applies saved host levels and mixes, including to slots saved without a plugin. */
    fun restoreHostSettings(states: List<SlotState>) {
        for (state in states) {
            if (isValidSlot(state.slotIndex)) {
                applyHostSettings(state.slotIndex, state.levelDb, state.mix)
            }
        }
    }

    /**
     * Re-creates a crashed slot's plugin with the parameter values it had. Its opaque state died with
     * its process. Returns false if the slot is not crashed or the rack is busy.
     */
    fun reloadCrashedSlot(slotIndex: Int): Boolean {
        if (!isValidSlot(slotIndex) || isInstantiating) {
            return false
        }

        val slot = slots[slotIndex]
        val plugin = slot.pluginInfo

        if (!slot.isCrashed || plugin == null) {
            return false
        }

        // Captured before releaseSlot() resets the slot's UI state
        val state = captureSlotState(slot)
        isInstantiating = true
        postStatus("Reloading ${plugin.displayName}...")
        releaseSlot(slotIndex, loadingPluginName = plugin.displayName)

        scope.launch {
            try {
                if (restoreSlot(state, plugin)) {
                    audio.ensureRunning()
                    postStatus("Reloaded ${plugin.displayName} into ${slots[slotIndex].title}")
                } else {
                    postStatus("Could not reload ${plugin.displayName}")
                }
            } finally {
                isInstantiating = false
            }
        }

        return true
    }

    fun toggleSlotBypass(slotIndex: Int) {
        if (!isValidSlot(slotIndex) || slots[slotIndex].pluginInfo == null) {
            return
        }

        val newBypass = !slots[slotIndex].isBypassed
        slots[slotIndex] = slots[slotIndex].copy(isBypassed = newBypass)
        audio.engine.setSlotBypassed(slotIndex, newBypass)
        markChanged()

        val state = if (newBypass) {
            "BYPASSED"
        } else {
            "ACTIVE"
        }

        postStatus("${slots[slotIndex].title} $state")
    }

    fun setSlotLevel(slotIndex: Int, levelDb: Float) {
        if (!isValidSlot(slotIndex)) {
            return
        }

        val newLevel = levelDb.coerceIn(SlotHostSettings.MIN_LEVEL_DB, SlotHostSettings.MAX_LEVEL_DB)

        if (newLevel == slots[slotIndex].levelDb) {
            return
        }

        applyHostSettings(slotIndex, newLevel, slots[slotIndex].mix)
        markChanged()
    }

    fun setSlotMix(slotIndex: Int, mix: Float) {
        if (!isValidSlot(slotIndex)) {
            return
        }

        val newMix = mix.coerceIn(SlotHostSettings.DRY_ONLY_MIX, SlotHostSettings.WET_ONLY_MIX)

        if (newMix == slots[slotIndex].mix) {
            return
        }

        applyHostSettings(slotIndex, slots[slotIndex].levelDb, newMix)
        markChanged()
    }

    fun setParameterValue(slotIndex: Int, parameter: ParameterInformation, value: Double) {
        if (!isValidSlot(slotIndex)) {
            return
        }

        val ui = slotUi[slotIndex]
        ui.lastHostEditTimestamps[parameter.id] = System.currentTimeMillis()
        ui.parameterValues[parameter.id] = value
        audio.engine.setParameterValue(slotIndex, parameter, value)
        markChanged()
    }

    fun setPreset(slotIndex: Int, nativeIndex: Int) {
        if (!isValidSlot(slotIndex)) {
            return
        }

        val slot = slots[slotIndex]

        if (slot.presets.isEmpty()) {
            return
        }

        val instance = slot.instance
        val plugin = slot.pluginInfo

        if (instance == null || plugin == null) {
            return
        }

        val targetPreset = slot.presets.find { it.nativeIndex == nativeIndex } ?: slot.presets.first()
        slots[slotIndex] = slot.copy(selectedPresetIndex = targetPreset.nativeIndex)
        markChanged()
        postStatus("${slot.title} Preset: ${targetPreset.name}")

        val request = ++presetRequestIds[slotIndex]

        scope.launch {
            // FIFO lock: rapid preset taps reach the plugin in the order they were made.
            val values = presetLocks[slotIndex].withLock {
                withContext(Dispatchers.IO) {
                    PluginSlotLoader.queryIfAlive(instance, Unit) {
                        instance.setCurrentPresetIndex(targetPreset.nativeIndex)
                    }
                    PluginSlotLoader.readParameterValues(plugin, instance)
                }
            }

            // A newer preset was picked, or the slot was reloaded, while this one was applying.
            if (request != presetRequestIds[slotIndex] || slots[slotIndex].instance != instance) {
                return@launch
            }

            // Display only: the preset already set these values in the plugin. Echoing them back
            // would clobber the preset if it applied after the read-back.
            val ui = slotUi[slotIndex]

            for (param in plugin.parameters) {
                val value = values[param.id] ?: continue
                ui.parameterValues[param.id] = value
                ui.lastPluginValues[param.id] = value
            }
        }
    }

    /** Snapshot of every slot for session persistence, including each plugin's opaque state chunk. */
    fun captureSlotStates(): List<SlotState> {
        return slots.map { captureSlotState(it) }
    }

    /** A crashed slot keeps its plugin and parameter values, without a state chunk. */
    private fun captureSlotState(slot: RackSlotData): SlotState {
        val instance = slot.instance
        val plugin = slot.pluginInfo ?: return SlotState(
            slotIndex = slot.index,
            slotType = slot.slotType,
            pluginId = null,
            packageName = null,
            displayName = null,
            isBypassed = slot.isBypassed,
            levelDb = slot.levelDb,
            mix = slot.mix,
            selectedPresetIndex = NO_PRESET_SELECTED,
            stateDataBase64 = null,
            parameters = emptyMap()
        )

        return SlotState(
            slotIndex = slot.index,
            slotType = slot.slotType,
            pluginId = plugin.pluginId,
            packageName = plugin.packageName,
            displayName = plugin.displayName,
            isBypassed = slot.isBypassed,
            levelDb = slot.levelDb,
            mix = slot.mix,
            selectedPresetIndex = slot.selectedPresetIndex,
            stateDataBase64 = instance?.let { captureStateChunk(slot.index, it) },
            parameters = slotUi[slot.index].parameterValues.toMap()
        )
    }

    /**
     * The process of [instance], loaded in the slot, died. The slot goes silent and keeps its plugin
     * and parameter values, so the user can reload it.
     */
    private fun onPluginProcessDied(slotIndex: Int, instance: NativeRemotePluginInstance) {
        val slot = slots[slotIndex]

        // Already unloaded or replaced while the notification was on its way
        if (slot.instance !== instance) {
            return
        }

        Log.w(TAG, "Plugin process died for ${slot.pluginInfo?.displayName} in slot $slotIndex")
        audio.engine.clearSlot(slotIndex)
        hostEngine.unloadSlot(slotIndex)

        slots[slotIndex] = slot.copy(
            instance = null,
            presetCount = 0,
            presets = emptyList(),
            isLoadingPresets = false,
            isCrashed = true
        )
        coerceViewModeToActiveSlot()
        postStatus("${slot.pluginInfo?.displayName} crashed. Tap CRASHED on its slot to reload it.")
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
            // Not counted as an edit: plugins also change values on their own (e.g. after a session load)
            for ((slotIndex, paramId, value) in updates) {
                slotUi[slotIndex].parameterValues[paramId] = value
            }
        }
    }

    /** Detaches and destroys whatever is in the slot and resets its UI state. */
    private fun releaseSlot(slotIndex: Int, loadingPluginName: String?) {
        val currentInstance = slots[slotIndex].instance
        audio.engine.setSlotBypassed(slotIndex, false)
        audio.engine.clearSlot(slotIndex)

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
            loadingPluginName = null,
            preparedSampleRate = loaded.sampleRate,
            isCrashed = false
        )

        audio.engine.setSlotPlugin(slotIndex, loaded.instance, loaded.sampleRate)
        audio.engine.setSlotBypassed(slotIndex, isBypassed)

        if (hasPresetList) {
            fetchPresetNames(slotIndex, loaded.instance, loaded.presetCount)
        }
    }

    private fun applyHostSettings(slotIndex: Int, levelDb: Float, mix: Float) {
        slots[slotIndex] = slots[slotIndex].copy(levelDb = levelDb, mix = mix)
        audio.engine.setSlotGain(slotIndex, SlotHostSettings.levelDbToGain(levelDb))
        audio.engine.setSlotMix(slotIndex, mix)
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
            PluginSlotLoader.queryUnlessDied<String?>(instance, null) {
                val stateSize = instance.getStateSize()

                if (stateSize > 0) {
                    val stateBuffer = ByteArray(stateSize)
                    instance.getState(stateBuffer)
                    Base64.encodeToString(stateBuffer, Base64.NO_WRAP)
                } else {
                    null
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to capture getState for slot $slotIndex", e)
            null
        }
    }

    private fun markChanged() {
        revision++
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
