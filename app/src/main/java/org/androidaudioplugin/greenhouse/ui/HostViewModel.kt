package org.androidaudioplugin.greenhouse.ui

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.midi.MidiDeviceInfo
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.greenhouse.core.AapAudioPlayer
import org.androidaudioplugin.greenhouse.core.AapHostEngine
import org.androidaudioplugin.greenhouse.core.MAX_HOST_BUFFER_FRAMES
import org.androidaudioplugin.greenhouse.core.MidiControllerManager
import org.androidaudioplugin.greenhouse.data.PluginCategory
import org.androidaudioplugin.greenhouse.data.PluginRepository
import android.net.Uri
import android.util.Base64
import org.androidaudioplugin.greenhouse.data.RackPreset
import org.androidaudioplugin.greenhouse.data.RackPresetHeader
import org.androidaudioplugin.greenhouse.data.RackSessionManager
import org.androidaudioplugin.greenhouse.data.SlotState
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import java.io.File
import java.util.Locale


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
            extensions.any { it.uri?.startsWith("urn://androidaudioplugin.org/extensions/gui") == true }

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
}

data class SlotLevel(
    val left: Float = 0f,
    val right: Float = 0f
)

private data class PluginInstantiationResult(
    val client: org.androidaudioplugin.hosting.AudioPluginClientBase,
    val instance: NativeRemotePluginInstance,
    val presetCount: Int,
    val initialParamValues: Map<Int, Double>
)

class HostViewModel(application: Application) : AndroidViewModel(application), DefaultLifecycleObserver {
    companion object {
        const val NUM_RACK_SLOTS = 3
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_BURST_SIZE = 128
        const val DEFAULT_BURST_MULTIPLIER = 4
        const val CPU_MONITOR_INTERVAL_MS = 100L
        const val METER_MONITOR_INTERVAL_MS = 33L
        const val CPU_UPDATE_TICKS = 3
        const val PARAM_SYNC_INTERVAL_TICKS = 2
        const val HOST_EDIT_IGNORE_MS = 600L
        const val PARAM_SYNC_EPSILON = 1e-4
        val AVAILABLE_BURST_MULTIPLIERS = listOf(2, 4, 8, 16, 32)
    }

    private val tag = "HostViewModel"

    private val repository = PluginRepository()
    private val hostEngine = AapHostEngine(application)
    val sessionManager = RackSessionManager(application)

    var savedSessions by mutableStateOf<List<RackPresetHeader>>(emptyList())
        private set

    var currentSessionName by mutableStateOf<String?>(null)
        private set

    var currentSessionFile by mutableStateOf<File?>(null)
        private set

    val savedPresets: List<RackPresetHeader> get() = savedSessions

    var isSessionOperationInProgress by mutableStateOf(false)
        private set

    var pluginList by mutableStateOf<List<PluginInformation>>(emptyList())
        private set

    var selectedCategory by mutableStateOf(PluginCategory.ALL)
        private set

    var searchQuery by mutableStateOf("")
        private set

    // Multi-slot rack state (Slot 0: Instrument, Slot 1..N-1: Effect)
    val slots = mutableStateListOf<RackSlotData>().apply {
        add(RackSlotData(0, "Slot 1", "Instrument"))

        for (i in 1 until NUM_RACK_SLOTS) {
            add(RackSlotData(i, "Slot ${i + 1}", "Effect"))
        }
    }

    val slotLevels = mutableStateListOf<SlotLevel>().apply {
        repeat(NUM_RACK_SLOTS) {
            add(SlotLevel())
        }
    }

    var activeSlotIndex by mutableIntStateOf(0)
        private set

    var targetBrowserSlotIndex by mutableIntStateOf(0)
        private set

    var currentViewMode by mutableStateOf(StudioRackViewMode.PARAMETERS)
        private set

    var audioPlayer by mutableStateOf<AapAudioPlayer?>(null)
        private set

    var isProcessing by mutableStateOf(false)
        private set

    var wasPlayingBeforeBackground by mutableStateOf(false)
        private set

    var isBackgrounded by mutableStateOf(false)
        private set

    var totalCpuLoad by mutableFloatStateOf(0f)
        private set

    val slotCpuLoads = mutableStateListOf<Float>().apply {
        repeat(NUM_RACK_SLOTS) {
            add(0f)
        }
    }

    var isInstantiating by mutableStateOf(false)
        private set

    var statusMessage by mutableStateOf("Welcome to AAP Studio Host")
        private set

    val slotParameterValues = Array(NUM_RACK_SLOTS) { mutableStateMapOf<Int, Double>() }
    val lastPluginValues = Array(NUM_RACK_SLOTS) { mutableMapOf<Int, Double>() }
    val lastHostEditTimestamps = Array(NUM_RACK_SLOTS) { mutableMapOf<Int, Long>() }
    val slotNativeUiZoomStates = Array(NUM_RACK_SLOTS) { SlotNativeUiZoomState() }
    val slotParameterGridStates = Array(NUM_RACK_SLOTS) { LazyGridState() }
    val slotPresetGridStates = Array(NUM_RACK_SLOTS) { LazyGridState() }

    // Persistent Keyboard State (survives screen navigation and slot changes)
    val keyboardNoteOnStates = mutableStateListOf<Long>().apply {
        addAll(List(128) { 0L })
    }
    var keyboardOctave by mutableIntStateOf(4)
    var isKeyboardHoldActive by mutableStateOf(false)

    // Hardware MIDI Controller State
    var availableMidiDevices by mutableStateOf<List<MidiDeviceInfo>>(emptyList())
        private set

    var activeMidiDevice by mutableStateOf<MidiDeviceInfo?>(null)
        private set

    var isMidiDeviceConnected by mutableStateOf(false)
        private set

    var lastMidiEventText by mutableStateOf<String?>(null)
        private set

    var midiStatusMessage by mutableStateOf<String?>(null)
        private set

    private val midiEventListener = object : MidiControllerManager.MidiEventListener {
        override fun onMidiDevicesChanged(devices: List<MidiDeviceInfo>, activeDevice: MidiDeviceInfo?) {
            viewModelScope.launch(Dispatchers.Main) {
                availableMidiDevices = devices
                activeMidiDevice = activeDevice
                isMidiDeviceConnected = (activeDevice != null)
            }
        }

        override fun onMidiDeviceConnectionStateChanged(device: MidiDeviceInfo?, isConnected: Boolean, message: String) {
            viewModelScope.launch(Dispatchers.Main) {
                activeMidiDevice = device
                isMidiDeviceConnected = isConnected
                midiStatusMessage = message
                statusMessage = message
            }
        }

        override fun onNoteOn(note: Int, velocity: Float) {
            viewModelScope.launch(Dispatchers.Main) {
                if (note in 0..127) {
                    keyboardNoteOnStates[note] = 1L
                    val velInt = (velocity * 127f).toInt()
                    lastMidiEventText = "Note On: ${MidiControllerManager.getNoteName(note)} ($velInt)"
                }
            }

            audioPlayer?.sendNoteOn(note, velocity)
        }

        override fun onNoteOff(note: Int, velocity: Float) {
            viewModelScope.launch(Dispatchers.Main) {
                if (note in 0..127 && !isKeyboardHoldActive) {
                    keyboardNoteOnStates[note] = 0L
                    lastMidiEventText = "Note Off: ${MidiControllerManager.getNoteName(note)}"
                }
            }

            audioPlayer?.sendNoteOff(note, velocity)
        }

        private var lastMidiUiUpdateTimestamp = 0L

        override fun onPitchBend(value: Float) {
            audioPlayer?.sendPitchBend(0, -1, value)

            val now = System.currentTimeMillis()

            if (now - lastMidiUiUpdateTimestamp > 50L) {
                lastMidiUiUpdateTimestamp = now

                viewModelScope.launch(Dispatchers.Main) {
                    lastMidiEventText = "Pitch Bend: ${String.format(Locale.US, "%.2f", value)}"
                }
            }
        }

        override fun onPressure(note: Int, value: Float) {
            audioPlayer?.sendPressure(0, note, value)

            val now = System.currentTimeMillis()

            if (now - lastMidiUiUpdateTimestamp > 50L) {
                lastMidiUiUpdateTimestamp = now

                viewModelScope.launch(Dispatchers.Main) {
                    val target = if (note >= 0) {
                        MidiControllerManager.getNoteName(note)
                    } else {
                        "Channel"
                    }
                    lastMidiEventText = "Pressure ($target): ${String.format(Locale.US, "%.2f", value)}"
                }
            }
        }

        override fun onControlChange(controller: Int, value: Float) {
            audioPlayer?.sendControlChange(0, controller, value)

            val now = System.currentTimeMillis()

            if (now - lastMidiUiUpdateTimestamp > 50L) {
                lastMidiUiUpdateTimestamp = now

                viewModelScope.launch(Dispatchers.Main) {
                    val valInt = (value * 127f).toInt()
                    lastMidiEventText = "CC #$controller: $valInt"
                }
            }
        }

        override fun onRawUmp(bytes: ByteArray) {
            // Direct UMP forwarding
        }
    }

    private val midiControllerManager = MidiControllerManager(application, midiEventListener)

    var showVirtualMidiDevices by mutableStateOf(false)
        private set

    fun updateShowVirtualMidiDevices(show: Boolean) {
        showVirtualMidiDevices = show
        midiControllerManager.includeVirtualDevices = show
    }

    fun selectMidiDevice(device: MidiDeviceInfo) {
        midiControllerManager.openDevice(device)
    }

    fun disconnectMidiDevice() {
        midiControllerManager.closeCurrentDevice()
        activeMidiDevice = null
        isMidiDeviceConnected = false
        midiStatusMessage = "MIDI Controller Disconnected"
        statusMessage = "MIDI Controller Disconnected"
    }

    fun rescanMidiDevices() {
        midiControllerManager.rescanDevices()
    }

    fun toggleKeyboardHold() {
        isKeyboardHoldActive = !isKeyboardHoldActive

        if (!isKeyboardHoldActive) {
            releaseAllKeyboardNotes()
        }
    }

    fun releaseAllKeyboardNotes() {
        for (i in 0..127) {
            if (keyboardNoteOnStates[i] > 0L) {
                keyboardNoteOnStates[i] = 0L
                sendNoteOff(i)
            }
        }
    }

    fun onKeyboardNoteOn(note: Int) {
        if (note !in 0..127) {
            return
        }

        if (isKeyboardHoldActive) {
            if (keyboardNoteOnStates[note] > 0L) {
                keyboardNoteOnStates[note] = 0L
                sendNoteOff(note)
            } else {
                keyboardNoteOnStates[note] = 1L
                sendNoteOn(note)
            }
        } else {
            keyboardNoteOnStates[note] = 1L
            sendNoteOn(note)
        }
    }

    fun onKeyboardNoteOff(note: Int) {
        if (note !in 0..127) {
            return
        }

        if (!isKeyboardHoldActive) {
            keyboardNoteOnStates[note] = 0L
            sendNoteOff(note)
        }
    }

    var sampleRate: Int = DEFAULT_SAMPLE_RATE
        private set

    val actualBurstSize: Int
        get() {
            val nativeBurst = audioPlayer?.actualBurstSize ?: 0

            if (nativeBurst > 0) {
                return nativeBurst
            }

            return DEFAULT_BURST_SIZE
        }

    var framesPerCallback by mutableIntStateOf(DEFAULT_BURST_SIZE * DEFAULT_BURST_MULTIPLIER)
        private set

    val availableBurstMultipliers: List<Int>
        get() {
            val base = actualBurstSize
            return AVAILABLE_BURST_MULTIPLIERS.filter { (it * base) <= MAX_HOST_BUFFER_FRAMES }
        }

    fun setBufferFramesPerCallback(newFrames: Int) {
        val clampedFrames = newFrames.coerceIn(1, MAX_HOST_BUFFER_FRAMES)

        if (clampedFrames > 0 && clampedFrames != framesPerCallback) {
            framesPerCallback = clampedFrames
            audioPlayer?.setFramesPerCallback(clampedFrames)
            val estimatedLatency = (clampedFrames.toFloat() / sampleRate.toFloat()) * 1000f
            statusMessage = "FIFO render block set to $clampedFrames frames (${String.format(Locale.US, "%.2f", estimatedLatency)} ms)"
        }
    }

    private var monitorJob: Job? = null

    init {
        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: DEFAULT_SAMPLE_RATE
        framesPerCallback = DEFAULT_BURST_SIZE * DEFAULT_BURST_MULTIPLIER

        audioPlayer = AapAudioPlayer.create(sampleRate, framesPerCallback, numSlots = NUM_RACK_SLOTS)

        refreshSavedPresets()

        refreshPluginList {
            if (sessionManager.hasAutosavedSession() && slots.all { it.pluginInfo == null }) {
                loadAutosavedSession()
            }
        }

        startMonitoring()
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        val rawLevels = FloatArray(NUM_RACK_SLOTS * 2)
        var tickCount = 0

        monitorJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val player = audioPlayer

                if (player != null && isProcessing) {
                    player.getAllSlotLevels(rawLevels)

                    var updateCpu = false
                    var totalPercent = 0f
                    val slotLoads = if (tickCount % CPU_UPDATE_TICKS == 0) {
                        updateCpu = true
                        val total = player.totalCpuLoad
                        totalPercent = (total * 100f).coerceIn(0f, 100f)
                        FloatArray(NUM_RACK_SLOTS) { i ->
                            (player.getSlotCpuLoad(i) * 100f).coerceIn(0f, 100f)
                        }
                    } else {
                        null
                    }

                    withContext(Dispatchers.Main) {
                        for (i in 0 until NUM_RACK_SLOTS) {
                            val l = rawLevels[i * 2]
                            val r = rawLevels[i * 2 + 1]

                            if (i < slotLevels.size) {
                                slotLevels[i] = SlotLevel(l, r)
                            }
                        }

                        if (updateCpu && slotLoads != null) {
                            totalCpuLoad = totalPercent

                            for (i in 0 until NUM_RACK_SLOTS) {
                                if (i < slotCpuLoads.size) {
                                    slotCpuLoads[i] = slotLoads[i]
                                }
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (totalCpuLoad != 0f) {
                            totalCpuLoad = 0f

                            for (i in 0 until slotCpuLoads.size) {
                                slotCpuLoads[i] = 0f
                            }
                        }

                        for (i in 0 until slotLevels.size) {
                            if (slotLevels[i].left != 0f || slotLevels[i].right != 0f) {
                                slotLevels[i] = SlotLevel(0f, 0f)
                            }
                        }
                    }
                }

                if (tickCount % PARAM_SYNC_INTERVAL_TICKS == 0) {
                    val now = System.currentTimeMillis()
                    val paramUpdates = mutableListOf<Triple<Int, Int, Double>>()

                    for (slotIndex in 0 until NUM_RACK_SLOTS) {
                        val slot = slots[slotIndex]
                        val inst = slot.instance
                        val plugin = slot.pluginInfo

                        if (slot.isLoaded && inst != null && plugin != null) {
                            val lastEdits = lastHostEditTimestamps[slotIndex]
                            val knownPluginVals = lastPluginValues[slotIndex]

                            for (i in 0 until plugin.parameters.size) {
                                val param = plugin.parameters[i]
                                val lastEdit = lastEdits[param.id] ?: 0L

                                if (now - lastEdit < HOST_EDIT_IGNORE_MS) {
                                    continue
                                }

                                val currentVal = try {
                                    inst.getParameterValue(i)
                                } catch (e: Throwable) {
                                    continue
                                }

                                val previousVal = knownPluginVals[param.id]

                                if (previousVal == null) {
                                    knownPluginVals[param.id] = currentVal
                                } else if (Math.abs(currentVal - previousVal) > PARAM_SYNC_EPSILON) {
                                    knownPluginVals[param.id] = currentVal
                                    paramUpdates.add(Triple(slotIndex, param.id, currentVal))
                                }
                            }
                        }
                    }

                    if (paramUpdates.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            for ((slotIndex, paramId, value) in paramUpdates) {
                                slotParameterValues[slotIndex][paramId] = value
                            }
                        }
                    }
                }

                tickCount++
                delay(METER_MONITOR_INTERVAL_MS)
            }
        }
    }

    val activeSlot: RackSlotData
        get() = slots[activeSlotIndex.coerceIn(0, NUM_RACK_SLOTS - 1)]

    fun selectActiveSlot(slotIndex: Int) {
        if (slotIndex in 0 until NUM_RACK_SLOTS) {
            activeSlotIndex = slotIndex

            val slot = slots[slotIndex]

            if (currentViewMode == StudioRackViewMode.PRESETS && slot.presets.size <= 1) {
                currentViewMode = StudioRackViewMode.PARAMETERS
            } else if (currentViewMode == StudioRackViewMode.NATIVE_SURFACE && !slot.hasCustomUi) {
                currentViewMode = StudioRackViewMode.PARAMETERS
            }
        }
    }

    var selectedDeveloper by mutableStateOf("ALL")
        private set

    fun selectDeveloper(developer: String) {
        selectedDeveloper = developer
    }

    fun isPluginAllowedForSlot(plugin: PluginInformation, slotIndex: Int): Boolean {
        val cat = repository.getPluginCategory(plugin)

        if (slotIndex == 0) {
            return cat == PluginCategory.SYNTH || cat == PluginCategory.OTHER
        } else {
            return cat == PluginCategory.EFFECT || cat == PluginCategory.OTHER
        }
    }

    val availableDevelopers: List<String>
        get() {
            val slotPlugins = pluginList.filter {
                isPluginAllowedForSlot(it, targetBrowserSlotIndex)
            }
            val devs = slotPlugins.mapNotNull {
                it.developer?.ifBlank { null } ?: "Unknown"
            }.distinct().sorted()
            return listOf("ALL") + devs
        }

    fun openBrowserForSlot(slotIndex: Int) {
        if (slotIndex in 0 until NUM_RACK_SLOTS) {
            targetBrowserSlotIndex = slotIndex
            activeSlotIndex = slotIndex
            selectedDeveloper = "ALL"
            searchQuery = ""
        }
    }

    fun refreshPluginList(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val plugins = repository.queryPlugins(getApplication())

                withContext(Dispatchers.Main) {
                    pluginList = plugins
                    statusMessage = "Found ${plugins.size} AAP plugin(s) on system."
                    onComplete?.invoke()
                }
            } catch (e: Throwable) {
                Log.e(tag, "Failed to query plugins", e)

                withContext(Dispatchers.Main) {
                    statusMessage = "Error querying plugins: ${e.message}"
                }
            }
        }
    }

    fun updateViewMode(mode: StudioRackViewMode) {
        currentViewMode = mode
    }

    fun selectCategory(category: PluginCategory) {
        selectedCategory = category
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
    }

    val filteredPlugins: List<PluginInformation>
        get() {
            return pluginList.filter { plugin ->
                val matchesSlotCategory = isPluginAllowedForSlot(plugin, targetBrowserSlotIndex)
                val pluginDev = plugin.developer?.ifBlank { null } ?: "Unknown"
                val matchesDeveloper = selectedDeveloper == "ALL" || pluginDev == selectedDeveloper
                val matchesSearch = searchQuery.isBlank() ||
                        plugin.displayName.contains(searchQuery, ignoreCase = true) ||
                        pluginDev.contains(searchQuery, ignoreCase = true) ||
                        (plugin.pluginId?.contains(searchQuery, ignoreCase = true) == true)
                matchesSlotCategory && matchesDeveloper && matchesSearch
            }
        }

    fun loadPluginIntoSlot(slotIndex: Int, plugin: PluginInformation) {
        if (slotIndex !in 0 until NUM_RACK_SLOTS) {
            return
        }

        if (slots[slotIndex].isLoading || isInstantiating) {
            return
        }

        activeSlotIndex = slotIndex
        targetBrowserSlotIndex = slotIndex

        isInstantiating = true
        statusMessage = "Instantiating ${plugin.displayName} in ${slots[slotIndex].title}..."

        val currentInst = slots[slotIndex].instance
        audioPlayer?.setSlotBypassed(slotIndex, false)
        audioPlayer?.setSlotPlugin(slotIndex, null)

        if (currentInst != null) {
            try {
                currentInst.destroy()
            } catch (e: Throwable) {
                Log.e(tag, "Error destroying previous plugin instance", e)
            }
        }

        hostEngine.unloadSlot(slotIndex)

        slots[slotIndex] = slots[slotIndex].copy(
            pluginInfo = null,
            instance = null,
            isBypassed = false,
            selectedPresetIndex = -1,
            presetCount = 0,
            presets = emptyList(),
            isLoadingPresets = false,
            isLoading = true,
            loadingPluginName = plugin.displayName
        )
        slotParameterValues[slotIndex].clear()
        lastHostEditTimestamps[slotIndex].clear()
        lastPluginValues[slotIndex].clear()
        slotNativeUiZoomStates[slotIndex].reset()
        slotParameterGridStates[slotIndex] = LazyGridState()
        slotPresetGridStates[slotIndex] = LazyGridState()

        viewModelScope.launch {
            try {
                val instantiationResult = withContext(Dispatchers.IO) {
                    val (c, inst) = hostEngine.instantiatePluginForSlot(slotIndex, plugin, sampleRate, framesPerCallback)

                    // Populate dynamic parameters and ports if missing from static aap_metadata.xml
                    if (plugin.parameters.isEmpty()) {
                        val paramCount = inst.getParameterCount()

                        for (i in 0 until paramCount) {
                            plugin.parameters.add(inst.getParameter(i))
                        }
                    }

                    if (plugin.ports.isEmpty()) {
                        val portCount = inst.getPortCount()

                        for (i in 0 until portCount) {
                            plugin.ports.add(inst.getPort(i))
                        }
                    }

                    val presetCount = try {
                        inst.getPresetCount()
                    } catch (e: Throwable) {
                        Log.w(tag, "Failed to query preset count", e)
                        0
                    }

                    val initialValues = mutableMapOf<Int, Double>()
                    val paramCount = inst.getParameterCount()

                    if (paramCount > 0) {
                        for (i in 0 until paramCount) {
                            val param = if (i < plugin.parameters.size) {
                                plugin.parameters[i]
                            } else {
                                null
                            }

                            if (param != null) {
                                val currentVal = try {
                                    inst.getParameterValue(i)
                                } catch (e: Throwable) {
                                    param.defaultValue
                                }

                                initialValues[param.id] = currentVal
                            }
                        }
                    } else {
                        plugin.parameters.forEach { param ->
                            initialValues[param.id] = param.defaultValue
                        }
                    }

                    PluginInstantiationResult(c, inst, presetCount, initialValues)
                }

                val instance = instantiationResult.instance
                val rawPresetCount = instantiationResult.presetCount

                slotParameterValues[slotIndex].clear()
                slotParameterValues[slotIndex].putAll(instantiationResult.initialParamValues)
                lastPluginValues[slotIndex].clear()
                lastPluginValues[slotIndex].putAll(instantiationResult.initialParamValues)

                slots[slotIndex] = slots[slotIndex].copy(
                    pluginInfo = plugin,
                    instance = instance,
                    isBypassed = false,
                    selectedPresetIndex = -1,
                    presetCount = rawPresetCount,
                    presets = emptyList(),
                    isLoadingPresets = rawPresetCount > 1,
                    isLoading = false,
                    loadingPluginName = null
                )

                audioPlayer?.setSlotBypassed(slotIndex, false)
                audioPlayer?.setSlotPlugin(slotIndex, instance, instantiationResult.client)

                activeSlotIndex = slotIndex

                if (currentViewMode == StudioRackViewMode.NATIVE_SURFACE && !plugin.hasCustomUi) {
                    currentViewMode = StudioRackViewMode.PARAMETERS
                } else if (currentViewMode == StudioRackViewMode.PRESETS && rawPresetCount <= 1) {
                    currentViewMode = StudioRackViewMode.PARAMETERS
                }

                if (!isProcessing) {
                    toggleAudioPlayback()
                }

                statusMessage = "Loaded ${plugin.displayName} into ${slots[slotIndex].title} (${slots[slotIndex].slotType})"

                // Asynchronously fetch preset names in the background without blocking plugin startup or audio
                if (rawPresetCount > 1) {
                    viewModelScope.launch {
                        val loadedPresets = withContext(Dispatchers.IO) {
                            val list = (0 until rawPresetCount).map { i ->
                                val name = try {
                                    val n = instance.getPresetName(i)

                                    if (n.isNotBlank()) {
                                        n
                                    } else {
                                        "Preset #${i + 1}"
                                    }
                                } catch (e: Throwable) {
                                    "Preset #${i + 1}"
                                }

                                PluginPreset(nativeIndex = i, name = name)
                            }

                            list.sortedWith(
                                compareBy<PluginPreset> { it.name.none { ch -> ch.isLetterOrDigit() } }
                                    .thenBy(String.CASE_INSENSITIVE_ORDER) {
                                        it.name.trimStart { ch -> !ch.isLetterOrDigit() }
                                    }
                                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                            )
                        }

                        if (slots[slotIndex].instance == instance) {
                            slots[slotIndex] = slots[slotIndex].copy(
                                presets = loadedPresets,
                                isLoadingPresets = false
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(tag, "Failed to load plugin ${plugin.displayName}", e)
                statusMessage = "Error loading plugin: ${e.localizedMessage ?: e.message}"

                slots[slotIndex] = slots[slotIndex].copy(
                    isLoading = false,
                    loadingPluginName = null
                )
            } finally {
                isInstantiating = false
            }
        }
    }

    fun unloadSlot(slotIndex: Int) {
        if (slotIndex !in 0 until NUM_RACK_SLOTS) {
            return
        }

        val currentInst = slots[slotIndex].instance
        audioPlayer?.setSlotBypassed(slotIndex, false)
        audioPlayer?.setSlotPlugin(slotIndex, null)

        if (currentInst != null) {
            try {
                currentInst.destroy()
            } catch (e: Throwable) {
                Log.e(tag, "Error destroying plugin instance", e)
            }
        }

        hostEngine.unloadSlot(slotIndex)

        slots[slotIndex] = slots[slotIndex].copy(
            pluginInfo = null,
            instance = null,
            isBypassed = false,
            selectedPresetIndex = -1,
            presetCount = 0,
            presets = emptyList(),
            isLoadingPresets = false,
            isLoading = false,
            loadingPluginName = null
        )
        slotParameterValues[slotIndex].clear()
        lastHostEditTimestamps[slotIndex].clear()
        lastPluginValues[slotIndex].clear()
        slotNativeUiZoomStates[slotIndex].reset()
        slotParameterGridStates[slotIndex] = LazyGridState()
        slotPresetGridStates[slotIndex] = LazyGridState()

        if (slotIndex in 0 until slotLevels.size) {
            slotLevels[slotIndex] = SlotLevel(0f, 0f)
        }

        val active = slots[activeSlotIndex]

        if (currentViewMode == StudioRackViewMode.PRESETS && (slotIndex == activeSlotIndex || active.presetCount <= 1)) {
            currentViewMode = StudioRackViewMode.PARAMETERS
        } else if (currentViewMode == StudioRackViewMode.NATIVE_SURFACE && (slotIndex == activeSlotIndex || !active.hasCustomUi)) {
            currentViewMode = StudioRackViewMode.PARAMETERS
        }

        statusMessage = "Cleared ${slots[slotIndex].title}"
    }

    fun toggleSlotBypass(slotIndex: Int) {
        if (slotIndex !in 0 until NUM_RACK_SLOTS) {
            return
        }

        if (slots[slotIndex].pluginInfo == null) {
            return
        }

        val newBypass = !slots[slotIndex].isBypassed
        slots[slotIndex] = slots[slotIndex].copy(isBypassed = newBypass)
        audioPlayer?.setSlotBypassed(slotIndex, newBypass)

        if (newBypass && slotIndex in 0 until slotLevels.size) {
            slotLevels[slotIndex] = SlotLevel(0f, 0f)
        }

        statusMessage = "${slots[slotIndex].title} ${if (newBypass) "BYPASSED" else "ACTIVE"}"
    }

    fun toggleAudioPlayback() {
        val player = audioPlayer

        if (player == null) {
            return
        }

        wasPlayingBeforeBackground = false

        if (isProcessing) {
            player.pause()
            isProcessing = false

            for (i in 0 until slotLevels.size) {
                slotLevels[i] = SlotLevel(0f, 0f)
            }

            statusMessage = "Audio engine paused."
        } else {
            player.start()
            isProcessing = player.isProcessing

            if (isProcessing) {
                val burst = player.actualBurstSize

                if (burst > 0 && framesPerCallback == DEFAULT_BURST_SIZE * DEFAULT_BURST_MULTIPLIER) {
                    framesPerCallback = burst * DEFAULT_BURST_MULTIPLIER
                    player.setFramesPerCallback(framesPerCallback)
                }

                statusMessage = "Audio engine ACTIVE (FIFO decoupled render running)."
            } else {
                statusMessage = "Failed to start audio engine."
            }
        }
    }

    fun pauseOnAppBackground() {
        isBackgrounded = true

        autosaveCurrentSession()

        if (isProcessing) {
            wasPlayingBeforeBackground = true
            Log.i(tag, "App backgrounded: pausing active audio engine")
            audioPlayer?.pause()
            isProcessing = false

            for (i in 0 until slotLevels.size) {
                slotLevels[i] = SlotLevel(0f, 0f)
            }

            statusMessage = "Audio engine paused (app in background)."
        } else {
            wasPlayingBeforeBackground = false
            Log.i(tag, "App backgrounded: audio engine was already paused")
        }
    }

    fun resumeOnAppForeground() {
        isBackgrounded = false

        if (wasPlayingBeforeBackground) {
            wasPlayingBeforeBackground = false
            val player = audioPlayer

            if (player != null && !isProcessing) {
                Log.i(tag, "App foregrounded: resuming audio engine")
                player.start()
                isProcessing = player.isProcessing

                if (isProcessing) {
                    val burst = player.actualBurstSize

                    if (burst > 0 && framesPerCallback == DEFAULT_BURST_SIZE * DEFAULT_BURST_MULTIPLIER) {
                        framesPerCallback = burst * DEFAULT_BURST_MULTIPLIER
                        player.setFramesPerCallback(framesPerCallback)
                    }

                    statusMessage = "Audio engine ACTIVE (FIFO decoupled render running)."
                } else {
                    statusMessage = "Failed to resume audio engine."
                    Log.e(tag, "Failed to resume audio engine after returning to foreground")
                }
            }
        } else {
            Log.i(tag, "App foregrounded: engine remains paused (not playing prior to background)")
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        resumeOnAppForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        val isChangingConfig = (owner as? ComponentActivity)?.isChangingConfigurations ?: false

        if (!isChangingConfig) {
            pauseOnAppBackground()
        }
    }

    fun sendNoteOn(note: Int, velocity: Float = 1.0f) {
        audioPlayer?.sendNoteOn(note, velocity)
    }

    fun sendNoteOff(note: Int, velocity: Float = 0.0f) {
        audioPlayer?.sendNoteOff(note, velocity)
    }

    fun setParameterValue(slotIndex: Int, parameter: ParameterInformation, value: Double) {
        if (slotIndex !in 0 until NUM_RACK_SLOTS) {
            return
        }

        lastHostEditTimestamps[slotIndex][parameter.id] = System.currentTimeMillis()
        slotParameterValues[slotIndex][parameter.id] = value
        audioPlayer?.setParameterValue(slotIndex, parameter, value)
    }

    fun setPreset(slotIndex: Int, nativeIndex: Int) {
        if (slotIndex !in 0 until NUM_RACK_SLOTS) {
            return
        }

        val slot = slots[slotIndex]

        if (slot.presets.isEmpty()) {
            return
        }

        val targetPreset = slot.presets.find { it.nativeIndex == nativeIndex } ?: slot.presets.first()
        val targetNativeIndex = targetPreset.nativeIndex
        val presetName = targetPreset.name

        slots[slotIndex] = slot.copy(selectedPresetIndex = targetNativeIndex)
        audioPlayer?.setPresetIndex(slotIndex, targetNativeIndex)

        syncParametersFromPluginPreset(slotIndex)

        statusMessage = "${slot.title} Preset: $presetName"
    }

    fun syncParametersForSlot(slotIndex: Int) {
        syncParametersFromPluginPreset(slotIndex)
    }

    private fun syncParametersFromPluginPreset(slotIndex: Int) {
        val slot = slots[slotIndex]
        val inst = slot.instance
        val plugin = slot.pluginInfo

        if (!slot.isLoaded || inst == null || plugin == null) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val updates = mutableListOf<Pair<ParameterInformation, Double>>()

            for (i in 0 until plugin.parameters.size) {
                val param = plugin.parameters[i]
                val currentVal = try {
                    inst.getParameterValue(i)
                } catch (e: Throwable) {
                    param.defaultValue
                }

                updates.add(Pair(param, currentVal))
            }

            withContext(Dispatchers.Main) {
                for ((param, value) in updates) {
                    slotParameterValues[slotIndex][param.id] = value
                    lastPluginValues[slotIndex][param.id] = value
                    audioPlayer?.setParameterValue(slotIndex, param, value)
                }
            }
        }
    }

    fun captureCurrentRackPreset(name: String = "Current Session"): RackPreset {
        val slotStates = mutableListOf<SlotState>()

        for (i in 0 until NUM_RACK_SLOTS) {
            val slot = slots[i]
            val inst = slot.instance
            val plugin = slot.pluginInfo

            if (slot.isLoaded && inst != null && plugin != null) {
                val stateDataBase64 = try {
                    val stateSize = inst.getStateSize()

                    if (stateSize > 0) {
                        val stateBuffer = ByteArray(stateSize)
                        inst.getState(stateBuffer)
                        Base64.encodeToString(stateBuffer, Base64.NO_WRAP)
                    } else {
                        null
                    }
                } catch (e: Throwable) {
                    Log.w(tag, "Failed to capture getState for slot $i", e)
                    null
                }

                val paramMap = slotParameterValues[i].toMap()

                slotStates.add(
                    SlotState(
                        slotIndex = i,
                        slotType = slot.slotType,
                        pluginId = plugin.pluginId,
                        packageName = plugin.packageName,
                        displayName = plugin.displayName,
                        isBypassed = slot.isBypassed,
                        selectedPresetIndex = slot.selectedPresetIndex,
                        stateDataBase64 = stateDataBase64,
                        parameters = paramMap
                    )
                )
            } else {
                slotStates.add(
                    SlotState(
                        slotIndex = i,
                        slotType = slot.slotType,
                        pluginId = null,
                        packageName = null,
                        displayName = null,
                        isBypassed = slot.isBypassed,
                        selectedPresetIndex = -1,
                        stateDataBase64 = null,
                        parameters = emptyMap()
                    )
                )
            }
        }

        return RackPreset(
            name = name,
            slots = slotStates
        )
    }

    fun restoreRackPreset(preset: RackPreset, onComplete: ((Boolean) -> Unit)? = null) {
        if (isSessionOperationInProgress || isInstantiating) {
            return
        }

        isSessionOperationInProgress = true
        statusMessage = "Loading '${preset.name}'…"

        viewModelScope.launch(Dispatchers.Main) {
            val wasAudioActive = isProcessing

            if (isProcessing) {
                audioPlayer?.pause()
                isProcessing = false
            }

            // 1. Teardown existing slots cleanly
            for (i in 0 until NUM_RACK_SLOTS) {
                unloadSlot(i)
            }

            var anyError = false

            // 2. Restore each slot
            for (slotState in preset.slots) {
                val slotIdx = slotState.slotIndex

                if (slotIdx !in 0 until NUM_RACK_SLOTS) {
                    continue
                }

                if (!slotState.isLoaded || slotState.pluginId.isNullOrBlank()) {
                    continue
                }

                // Find matching plugin in current catalog
                val targetPlugin = pluginList.find { it.pluginId == slotState.pluginId }
                    ?: pluginList.find { it.packageName == slotState.packageName && it.displayName == slotState.displayName }

                if (targetPlugin == null) {
                    Log.w(tag, "Plugin ${slotState.displayName ?: slotState.pluginId} not found on device")
                    statusMessage = "Plugin ${slotState.displayName ?: slotState.pluginId} not installed on this device"
                    anyError = true
                    continue
                }

                slots[slotIdx] = slots[slotIdx].copy(
                    isLoading = true,
                    loadingPluginName = targetPlugin.displayName
                )

                try {
                    val (client, instance) = withContext(Dispatchers.IO) {
                        hostEngine.instantiatePluginForSlot(slotIdx, targetPlugin, sampleRate, framesPerCallback)
                    }

                    // Restore binary state chunk if available
                    if (!slotState.stateDataBase64.isNullOrBlank()) {
                        try {
                            val stateBytes = Base64.decode(slotState.stateDataBase64, Base64.DEFAULT)

                            if (stateBytes.isNotEmpty()) {
                                instance.setState(stateBytes)
                                Log.d(tag, "Restored setState (${stateBytes.size} bytes) for slot $slotIdx")
                            }
                        } catch (e: Throwable) {
                            Log.e(tag, "Failed to apply setState for slot $slotIdx", e)
                        }
                    }

                    // Restore preset index if available
                    val rawPresetCount = try {
                        instance.getPresetCount()
                    } catch (e: Throwable) {
                        0
                    }

                    if (slotState.selectedPresetIndex >= 0) {
                        try {
                            instance.setCurrentPresetIndex(slotState.selectedPresetIndex)
                        } catch (e: Throwable) {
                            Log.e(tag, "Failed to restore presetIndex for slot $slotIdx", e)
                        }
                    }

                    // Query plugin parameters
                    val paramCount = try {
                        instance.getParameterCount()
                    } catch (e: Throwable) {
                        0
                    }

                    if (paramCount > targetPlugin.parameters.size) {
                        for (p in targetPlugin.parameters.size until paramCount) {
                            try {
                                targetPlugin.parameters.add(instance.getParameter(p))
                            } catch (e: Throwable) {
                                Log.w(tag, "Failed to discover parameter $p", e)
                            }
                        }
                    }

                    // Populate parameter values
                    slotParameterValues[slotIdx].clear()
                    lastHostEditTimestamps[slotIdx].clear()
                    lastPluginValues[slotIdx].clear()

                    for (i in 0 until targetPlugin.parameters.size) {
                        val param = targetPlugin.parameters[i]
                        val currentVal = try {
                            instance.getParameterValue(i)
                        } catch (e: Throwable) {
                            param.defaultValue
                        }

                        lastPluginValues[slotIdx][param.id] = currentVal
                    }

                    if (slotState.parameters.isNotEmpty()) {
                        slotParameterValues[slotIdx].putAll(slotState.parameters)

                        for ((paramId, value) in slotState.parameters) {
                            val paramInfo = targetPlugin.parameters.find { it.id == paramId }

                            if (paramInfo != null) {
                                audioPlayer?.setParameterValue(slotIdx, paramInfo, value)
                            }
                        }
                    } else {
                        slotParameterValues[slotIdx].putAll(lastPluginValues[slotIdx])
                    }

                    slots[slotIdx] = slots[slotIdx].copy(
                        pluginInfo = targetPlugin,
                        instance = instance,
                        isBypassed = slotState.isBypassed,
                        selectedPresetIndex = slotState.selectedPresetIndex,
                        presetCount = rawPresetCount,
                        presets = emptyList(),
                        isLoadingPresets = rawPresetCount > 1,
                        isLoading = false,
                        loadingPluginName = null
                    )

                    audioPlayer?.setSlotBypassed(slotIdx, slotState.isBypassed)
                    audioPlayer?.setSlotPlugin(slotIdx, instance, client)

                    // Fetch preset names asynchronously
                    if (rawPresetCount > 1) {
                        viewModelScope.launch {
                            val loadedPresets = withContext(Dispatchers.IO) {
                                val list = (0 until rawPresetCount).map { pIdx ->
                                    val name = try {
                                        val n = instance.getPresetName(pIdx)

                                        if (n.isNotBlank()) {
                                            n
                                        } else {
                                            "Preset #${pIdx + 1}"
                                        }
                                    } catch (e: Throwable) {
                                        "Preset #${pIdx + 1}"
                                    }

                                    PluginPreset(nativeIndex = pIdx, name = name)
                                }

                                list.sortedWith(
                                    compareBy<PluginPreset> { it.name.none { ch -> ch.isLetterOrDigit() } }
                                        .thenBy(String.CASE_INSENSITIVE_ORDER) {
                                            it.name.trimStart { ch -> !ch.isLetterOrDigit() }
                                        }
                                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                                )
                            }

                            if (slots[slotIdx].instance == instance) {
                                slots[slotIdx] = slots[slotIdx].copy(
                                    presets = loadedPresets,
                                    isLoadingPresets = false
                                )
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(tag, "Failed to instantiate plugin for slot $slotIdx during preset restore", e)
                    anyError = true

                    slots[slotIdx] = slots[slotIdx].copy(
                        isLoading = false,
                        loadingPluginName = null
                    )
                }
            }

            if (wasAudioActive && !isProcessing) {
                toggleAudioPlayback()
            }

            autosaveCurrentSession()

            isSessionOperationInProgress = false
            statusMessage = if (!anyError) {
                "Session '${preset.name}' loaded successfully."
            } else {
                "Loaded '${preset.name}' with warnings (some plugins may be missing)."
            }

            onComplete?.invoke(!anyError)
        }
    }

    fun refreshSavedSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = sessionManager.listPresets()

            withContext(Dispatchers.Main) {
                savedSessions = list
            }
        }
    }

    fun refreshSavedPresets() {
        refreshSavedSessions()
    }

    /**
     * True when saving under [name] would land on another saved session.
     * Names are compared case-insensitively, and target files are compared too, because names
     * are sanitized into file names and two different names can map to the same file.
     * [ignoreFile] excludes the session being saved in place.
     */
    fun isSessionNameTaken(name: String, ignoreFile: File? = null): Boolean {
        val trimmed = name.trim()

        if (trimmed.isBlank()) {
            return false
        }

        val targetPath = sessionManager.getPresetFile(trimmed).absolutePath
        val ignoredPath = ignoreFile?.absolutePath

        return savedSessions.any { saved ->
            val savedPath = saved.file.absolutePath
            savedPath != ignoredPath &&
                (saved.name.equals(trimmed, ignoreCase = true) || savedPath == targetPath)
        }
    }

    /** True when the one-tap Save can write the current session without clobbering a different one. */
    fun canSaveActiveSessionInPlace(): Boolean {
        val activeName = currentSessionName

        if (activeName.isNullOrBlank()) {
            return false
        }

        return !isSessionNameTaken(activeName, ignoreFile = currentSessionFile)
    }

    fun saveCurrentSession(
        name: String,
        onComplete: ((Boolean) -> Unit)? = null,
        overwrite: Boolean = false
    ) {
        val trimmed = name.trim()

        if (trimmed.isBlank()) {
            return
        }

        if (!overwrite && isSessionNameTaken(trimmed)) {
            statusMessage = "'$trimmed' already exists."
            onComplete?.invoke(false)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val preset = captureCurrentRackPreset(trimmed)
            val file = sessionManager.savePreset(trimmed, preset)

            withContext(Dispatchers.Main) {
                if (file != null) {
                    currentSessionName = trimmed
                    currentSessionFile = file
                }

                refreshSavedSessions()

                if (file != null) {
                    statusMessage = "Saved '$trimmed'."
                    onComplete?.invoke(true)
                } else {
                    statusMessage = "Couldn't save '$trimmed'."
                    onComplete?.invoke(false)
                }
            }
        }
    }

    fun saveActiveSession(onComplete: ((Boolean) -> Unit)? = null) {
        val activeName = currentSessionName

        if (!activeName.isNullOrBlank() && canSaveActiveSessionInPlace()) {
            saveCurrentSession(activeName, onComplete, overwrite = true)
        } else {
            onComplete?.invoke(false)
        }
    }

    fun saveCurrentPreset(name: String, onComplete: ((Boolean) -> Unit)? = null) {
        saveCurrentSession(name, onComplete)
    }

    fun loadSessionFromFile(file: File, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val preset = sessionManager.loadPreset(file)

            withContext(Dispatchers.Main) {
                if (preset != null) {
                    currentSessionName = preset.name.ifBlank { file.nameWithoutExtension }
                    currentSessionFile = file
                    restoreRackPreset(preset, onComplete)
                } else {
                    statusMessage = "Couldn't open ${file.name}."
                    onComplete?.invoke(false)
                }
            }
        }
    }

    fun loadPresetFromFile(file: File, onComplete: ((Boolean) -> Unit)? = null) {
        loadSessionFromFile(file, onComplete)
    }

    fun loadSessionFromUri(uri: Uri, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val preset = sessionManager.loadPresetFromUri(uri)

            withContext(Dispatchers.Main) {
                if (preset != null) {
                    currentSessionName = preset.name.ifBlank { "Imported Session" }
                    currentSessionFile = null
                    restoreRackPreset(preset, onComplete)
                } else {
                    statusMessage = "Couldn't import that file."
                    onComplete?.invoke(false)
                }
            }
        }
    }

    fun loadPresetFromUri(uri: Uri, onComplete: ((Boolean) -> Unit)? = null) {
        loadSessionFromUri(uri, onComplete)
    }

    fun deleteSession(header: RackPresetHeader) {
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = sessionManager.deletePreset(header.file)

            withContext(Dispatchers.Main) {
                if (currentSessionFile == header.file || currentSessionName.equals(header.name, ignoreCase = true)) {
                    currentSessionName = null
                    currentSessionFile = null
                }

                refreshSavedSessions()

                if (deleted) {
                    statusMessage = "Deleted '${header.name}'."
                }
            }
        }
    }

    fun deletePreset(header: RackPresetHeader) {
        deleteSession(header)
    }

    fun clearRack() {
        for (i in 0 until NUM_RACK_SLOTS) {
            unloadSlot(i)
        }

        currentSessionName = null
        currentSessionFile = null
        sessionManager.clearAutosession()
        statusMessage = "New session started."
    }

    fun autosaveCurrentSession() {
        try {
            val sessionName = currentSessionName ?: "Autosave Session"
            val preset = captureCurrentRackPreset(sessionName)
            sessionManager.saveAutosession(preset)
        } catch (e: Throwable) {
            Log.e(tag, "Failed to autosave session", e)
        }
    }

    fun loadAutosavedSession() {
        viewModelScope.launch(Dispatchers.IO) {
            val preset = sessionManager.loadAutosession()

            if (preset != null && preset.loadedSlotCount > 0) {
                withContext(Dispatchers.Main) {
                    if (preset.name.isNotBlank() && preset.name != "Autosave Session" && preset.name != "Current Session") {
                        currentSessionName = preset.name
                        currentSessionFile = sessionManager.findPresetFile(preset.name)
                    }

                    restoreRackPreset(preset)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()

        for (i in 0 until NUM_RACK_SLOTS) {
            unloadSlot(i)
        }

        try {
            midiControllerManager.close()
            audioPlayer?.close()
            hostEngine.close()
        } catch (e: Throwable) {
            Log.e(tag, "Error closing host engine", e)
        }
    }
}
