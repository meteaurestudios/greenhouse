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
import org.androidaudioplugin.greenhouse.data.SlotHostSettings
import org.androidaudioplugin.greenhouse.data.SlotState
import org.androidaudioplugin.greenhouse.data.SlotType
import org.androidaudioplugin.greenhouse.device.DeviceInfo
import org.androidaudioplugin.greenhouse.device.SavedDeviceState
import org.androidaudioplugin.greenhouse.device.SlotDevice
import org.androidaudioplugin.greenhouse.device.SlotDeviceSource
import org.androidaudioplugin.greenhouse.ui.RackSlotData
import org.androidaudioplugin.greenhouse.ui.SlotUiState
import org.androidaudioplugin.greenhouse.ui.StudioRackViewMode
import kotlin.math.abs

/**
 * The rack's slots (slot 0: instrument, slots 1..N-1: effects): device lifecycle per slot (AAP
 * plugins or devices from the app's other sources), parameter / preset edits, and which slot and view
 * the rack is showing.
 */
class RackController(
    sources: List<SlotDeviceSource>,
    private val audio: AudioEngineController,
    private val scope: CoroutineScope,
    private val postStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "RackController"
        const val NUM_RACK_SLOTS = 3
        const val INSTRUMENT_SLOT_INDEX = 0
        const val NO_PRESET_SELECTED = -1
        // AAP plugins report host edits back right away (see SlotDevice.onHostParameterChange()); this
        // only covers plugins that echo the values they processed while a gesture is still going on
        const val HOST_EDIT_IGNORE_MS = 200L
        const val PARAM_SYNC_EPSILON = 1e-4
    }

    private val sources = sources.associateBy { it.id }

    val slots = mutableStateListOf<RackSlotData>().apply {
        for (i in 0 until NUM_RACK_SLOTS) {
            val slotType = if (i == INSTRUMENT_SLOT_INDEX) {
                SlotType.INSTRUMENT
            } else {
                SlotType.EFFECT
            }

            add(RackSlotData(i, "Slot ${i + 1}", slotType))
        }
    }

    val slotUi = List(NUM_RACK_SLOTS) { SlotUiState() }

    /** Role of each slot, in rack order. */
    val slotTypes: List<String>
        get() = slots.map { it.slotType }

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
        get() = slots.all { it.device == null }

    fun isValidSlot(slotIndex: Int): Boolean {
        return slotIndex in 0 until NUM_RACK_SLOTS
    }

    /** Whether a loaded device has to be created again to play at [sampleRate]. */
    fun hasDevicesToReloadAt(sampleRate: Int): Boolean {
        return slots.any { it.isLoaded && it.device?.needsReloadAt(sampleRate) == true }
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

    /** Starts loading [info] into the slot; returns false if the rack is busy and nothing was started. */
    fun loadDevice(slotIndex: Int, info: DeviceInfo): Boolean {
        if (!isValidSlot(slotIndex)) {
            return false
        }

        if (slots[slotIndex].isLoading || isInstantiating) {
            return false
        }

        activeSlotIndex = slotIndex
        isInstantiating = true
        postStatus("Instantiating ${info.displayName} in ${slots[slotIndex].title}...")
        releaseSlot(slotIndex, loadingPluginName = info.displayName)
        // A newly added device starts from the default level, fully wet
        applyHostSettings(slotIndex, SlotHostSettings.DEFAULT_LEVEL_DB, SlotHostSettings.INITIAL_MIX)

        scope.launch {
            try {
                val created = createDevice(slotIndex, info, saved = null)

                if (created == null) {
                    reportLoadFailure(slotIndex, info)
                    return@launch
                }

                val (device, values) = created

                attachDevice(
                    slotIndex = slotIndex,
                    device = device,
                    readValues = values,
                    isBypassed = false,
                    selectedPresetIndex = NO_PRESET_SELECTED,
                    displayedValues = values
                )

                activeSlotIndex = slotIndex
                coerceViewModeToActiveSlot()
                markChanged()
                audio.ensureRunning()
                postStatus("Loaded ${info.displayName} into ${slots[slotIndex].title} (${slots[slotIndex].slotType})")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load ${info.displayName}", e)
                reportLoadFailure(slotIndex, info)
            } finally {
                isInstantiating = false
            }
        }

        return true
    }

    /**
     * Re-creates a saved slot: creates [info] with its saved state or, without one (e.g. reloading a
     * crashed plugin), with the saved preset and parameter values. Returns false if the device
     * couldn't be created.
     */
    suspend fun restoreSlot(state: SlotState, info: DeviceInfo): Boolean {
        val slotIndex = state.slotIndex
        slots[slotIndex] = slots[slotIndex].copy(isLoading = true, loadingPluginName = info.displayName)

        try {
            // A saved state holds everything, and the saved preset and values may be stale: aap-core does
            // not always learn of changes made inside a plugin (e.g. by its presets), and selecting the
            // preset again would undo the edits made since it was picked
            val savedState = state.stateDataBase64?.let { decodeState(slotIndex, it) }
            val presetIndex = if (savedState == null) {
                state.selectedPresetIndex
            } else {
                NO_PRESET_SELECTED
            }

            val created = createDevice(slotIndex, info, SavedDeviceState(savedState, presetIndex))

            if (created == null) {
                reportLoadFailure(slotIndex, info)
                return false
            }

            val (device, values) = created

            attachDevice(
                slotIndex = slotIndex,
                device = device,
                readValues = values,
                isBypassed = state.isBypassed,
                selectedPresetIndex = state.selectedPresetIndex,
                displayedValues = state.parameters.ifEmpty { values }
            )

            if (savedState == null) {
                pushSavedValues(slotIndex, device, state.parameters)
            }

            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create ${info.displayName} for slot $slotIndex during session restore", e)
            reportLoadFailure(slotIndex, info)
            return false
        }
    }

    private fun pushSavedValues(slotIndex: Int, device: SlotDevice, savedParameters: Map<Int, Double>) {
        val savedValues = savedParameters.mapNotNull { (paramId, value) ->
            device.parameters.find { it.id == paramId }?.let { it to value }
        }

        for ((param, value) in savedValues) {
            device.onHostParameterChange(param, value)
        }

        // One send per parameter would overflow aap-core's input queue on plugins with many parameters
        device.setParameterValues(audio.engine, slotIndex, savedValues)
    }

    /** Creates the device and reads its parameter values; null if its source is gone or fails to create it. */
    private suspend fun createDevice(slotIndex: Int, info: DeviceInfo, saved: SavedDeviceState?): Pair<SlotDevice, Map<Int, Double>>? {
        val source = sources[info.sourceId]

        if (source == null) {
            Log.w(TAG, "No source ${info.sourceId} for ${info.displayName}")
            return null
        }

        return withContext(Dispatchers.IO) {
            source.instantiate(info, slotIndex, audio.sampleRate, saved)?.let { device ->
                device to device.readParameterValues()
            }
        }
    }

    /** Takes [slotIndex] out of its loading state and tells the user [info] couldn't be loaded. */
    private fun reportLoadFailure(slotIndex: Int, info: DeviceInfo) {
        val message = "${info.displayName} failed to load"
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
        val info = slot.deviceInfo

        if (!slot.isCrashed || info == null) {
            return false
        }

        // Captured before releaseSlot() resets the slot's UI state
        val state = captureSlotState(slot)
        isInstantiating = true
        postStatus("Reloading ${info.displayName}...")
        releaseSlot(slotIndex, loadingPluginName = info.displayName)

        scope.launch {
            try {
                if (restoreSlot(state, info)) {
                    audio.ensureRunning()
                    postStatus("Reloaded ${info.displayName} into ${slots[slotIndex].title}")
                } else {
                    postStatus("Could not reload ${info.displayName}")
                }
            } finally {
                isInstantiating = false
            }
        }

        return true
    }

    fun toggleSlotBypass(slotIndex: Int) {
        if (!isValidSlot(slotIndex) || slots[slotIndex].device == null) {
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

        val device = slots[slotIndex].device

        if (device != null && slots[slotIndex].isLoaded) {
            device.onHostParameterChange(parameter, value)
            device.setParameterValues(audio.engine, slotIndex, listOf(parameter to value))
        }

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

        val device = slot.device

        if (device == null || !slot.isLoaded) {
            return
        }

        val targetPreset = slot.presets.find { it.nativeIndex == nativeIndex } ?: slot.presets.first()
        slots[slotIndex] = slot.copy(selectedPresetIndex = targetPreset.nativeIndex)
        markChanged()
        postStatus("${slot.title} Preset: ${targetPreset.name}")

        val request = ++presetRequestIds[slotIndex]

        scope.launch {
            // FIFO lock: rapid preset taps reach the device in the order they were made.
            val values = presetLocks[slotIndex].withLock {
                withContext(Dispatchers.IO) {
                    device.selectPreset(targetPreset.nativeIndex)
                }
            }

            // A newer preset was picked, or the slot was reloaded, while this one was applying.
            if (request != presetRequestIds[slotIndex] || slots[slotIndex].device !== device) {
                return@launch
            }

            // Display only: the preset already set these values in the device. Echoing them back
            // would clobber the preset if it applied after the read-back.
            val ui = slotUi[slotIndex]

            for (param in device.parameters) {
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

    /** A crashed slot keeps its device and parameter values, without a state chunk. */
    private fun captureSlotState(slot: RackSlotData): SlotState {
        val device = slot.device
        val info = device?.info ?: return SlotState(
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

        val state = if (slot.isCrashed) {
            null
        } else {
            captureStateChunk(slot.index, device)
        }

        return SlotState(
            slotIndex = slot.index,
            slotType = slot.slotType,
            source = info.sourceId,
            pluginId = info.id,
            packageName = info.packageName,
            displayName = info.displayName,
            isBypassed = slot.isBypassed,
            levelDb = slot.levelDb,
            mix = slot.mix,
            selectedPresetIndex = slot.selectedPresetIndex,
            stateDataBase64 = state,
            parameters = slotUi[slot.index].parameterValues.toMap()
        )
    }

    /**
     * [device], loaded in the slot, died on its own (e.g. its plugin's process). The slot goes silent
     * and keeps the device and parameter values, so the user can reload it.
     */
    private fun onDeviceDied(slotIndex: Int, device: SlotDevice) {
        val slot = slots[slotIndex]

        // Already unloaded or replaced while the notification was on its way
        if (slot.device !== device || slot.isCrashed) {
            return
        }

        Log.w(TAG, "${device.info.displayName} died in slot $slotIndex")
        audio.engine.clearSlot(slotIndex)
        releaseDevice(slotIndex, device)

        slots[slotIndex] = slot.copy(
            presetCount = 0,
            presets = emptyList(),
            isLoadingPresets = false,
            isCrashed = true
        )
        coerceViewModeToActiveSlot()
        postStatus("${device.info.displayName} crashed. Tap CRASHED on its slot to reload it.")
    }

    /**
     * Picks up parameter changes made on the device side (a plugin's own UI, presets, automation).
     * Reads the devices on the calling thread; publishes changed values on Main.
     */
    suspend fun pollDeviceParameterChanges() {
        val now = System.currentTimeMillis()
        val updates = mutableListOf<Triple<Int, Int, Double>>()

        for (slot in slots.toList()) {
            val device = slot.device

            if (device == null || !slot.isLoaded) {
                continue
            }

            val ui = slotUi[slot.index]

            for ((i, param) in device.parameters.withIndex()) {
                val lastEdit = ui.lastHostEditTimestamps[param.id] ?: 0L

                if (now - lastEdit < HOST_EDIT_IGNORE_MS) {
                    continue
                }

                val currentVal = device.readParameterValue(i) ?: continue
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

    /** Detaches and releases whatever is in the slot and resets its UI state. */
    private fun releaseSlot(slotIndex: Int, loadingPluginName: String?) {
        val device = slots[slotIndex].device
        audio.engine.setSlotBypassed(slotIndex, false)
        audio.engine.clearSlot(slotIndex)

        if (device != null && !slots[slotIndex].isCrashed) {
            releaseDevice(slotIndex, device)
        }

        slots[slotIndex] = slots[slotIndex].cleared(loadingPluginName)
        slotUi[slotIndex].reset()
    }

    /** Once the slot is cleared in the native rack. */
    private fun releaseDevice(slotIndex: Int, device: SlotDevice) {
        device.onDied = null

        try {
            device.release()
        } catch (e: Throwable) {
            Log.e(TAG, "Error releasing ${device.info.displayName} in slot $slotIndex", e)
        }
    }

    private fun attachDevice(
        slotIndex: Int,
        device: SlotDevice,
        readValues: Map<Int, Double>,
        isBypassed: Boolean,
        selectedPresetIndex: Int,
        displayedValues: Map<Int, Double>
    ) {
        val ui = slotUi[slotIndex]
        ui.lastPluginValues.clear()
        ui.lastPluginValues.putAll(readValues)
        ui.parameterValues.clear()
        ui.parameterValues.putAll(displayedValues)

        val hasPresetList = device.presetCount > 1

        slots[slotIndex] = slots[slotIndex].copy(
            device = device,
            isBypassed = isBypassed,
            selectedPresetIndex = selectedPresetIndex,
            presetCount = device.presetCount,
            presets = emptyList(),
            isLoadingPresets = hasPresetList,
            isLoading = false,
            loadingPluginName = null,
            isCrashed = false
        )

        device.onDied = {
            scope.launch(Dispatchers.Main) {
                onDeviceDied(slotIndex, device)
            }
        }

        device.attach(audio.engine, slotIndex)
        audio.engine.setSlotBypassed(slotIndex, isBypassed)

        if (hasPresetList) {
            fetchPresetNames(slotIndex, device)
        }
    }

    private fun applyHostSettings(slotIndex: Int, levelDb: Float, mix: Float) {
        slots[slotIndex] = slots[slotIndex].copy(levelDb = levelDb, mix = mix)
        audio.engine.setSlotGain(slotIndex, SlotHostSettings.levelDbToGain(levelDb))
        audio.engine.setSlotMix(slotIndex, mix)
    }

    /** Preset names can be slow to enumerate, so they fill in after the device is already playing. */
    private fun fetchPresetNames(slotIndex: Int, device: SlotDevice) {
        scope.launch {
            val presets = withContext(Dispatchers.IO) {
                device.readPresets()
            }

            // The slot may have been reloaded while names were being fetched.
            if (slots[slotIndex].device === device) {
                slots[slotIndex] = slots[slotIndex].copy(presets = presets, isLoadingPresets = false)
            }
        }
    }

    private fun decodeState(slotIndex: Int, stateData: String): ByteArray? {
        return try {
            Base64.decode(stateData, Base64.DEFAULT).takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Saved state of slot $slotIndex is not valid Base64", e)
            null
        }
    }

    private fun captureStateChunk(slotIndex: Int, device: SlotDevice): String? {
        return try {
            device.captureState()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to capture the state of slot $slotIndex", e)
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
