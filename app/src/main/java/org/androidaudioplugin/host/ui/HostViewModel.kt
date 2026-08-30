package org.androidaudioplugin.host.ui

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.host.core.AapAudioPlayer
import org.androidaudioplugin.host.core.AapHostEngine
import org.androidaudioplugin.host.core.MAX_HOST_BUFFER_FRAMES
import org.androidaudioplugin.host.data.PluginCategory
import org.androidaudioplugin.host.data.PluginRepository
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import java.util.Locale

enum class StudioRackViewMode(val title: String) {
    PARAMETERS("Parameters"),
    NATIVE_SURFACE("Plugin UI"),
    SPECS("Ports & Details")
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
    val pluginInfo: PluginInformation? = null,
    val instance: NativeRemotePluginInstance? = null,
    val isBypassed: Boolean = false,
    val selectedPresetIndex: Int = 0,
    val isLoading: Boolean = false,
    val loadingPluginName: String? = null
)

class HostViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val NUM_RACK_SLOTS = 3
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_BURST_SIZE = 128
        const val DEFAULT_BURST_MULTIPLIER = 4
        const val CPU_MONITOR_INTERVAL_MS = 100L
        val AVAILABLE_BURST_MULTIPLIERS = listOf(2, 4, 8, 16, 32)
    }

    private val tag = "HostViewModel"

    private val repository = PluginRepository()
    private val hostEngine = AapHostEngine(application)

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
    val slotNativeUiZoomStates = Array(NUM_RACK_SLOTS) { SlotNativeUiZoomState() }

    // Persistent Keyboard State (survives screen navigation and slot changes)
    val keyboardNoteOnStates = mutableStateListOf<Long>().apply {
        addAll(List(128) { 0L })
    }
    var keyboardOctave by mutableIntStateOf(4)
    var isKeyboardHoldActive by mutableStateOf(false)

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

    private var cpuMonitorJob: Job? = null

    init {
        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: DEFAULT_SAMPLE_RATE
        framesPerCallback = DEFAULT_BURST_SIZE * DEFAULT_BURST_MULTIPLIER

        audioPlayer = AapAudioPlayer.create(sampleRate, framesPerCallback, numSlots = NUM_RACK_SLOTS)

        refreshPluginList()
        startCpuMonitoring()
    }

    private fun startCpuMonitoring() {
        cpuMonitorJob?.cancel()
        cpuMonitorJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val player = audioPlayer

                if (player != null && isProcessing) {
                    val total = player.totalCpuLoad
                    val totalPercent = (total * 100f).coerceIn(0f, 100f)

                    withContext(Dispatchers.Main) {
                        totalCpuLoad = totalPercent

                        for (i in 0 until NUM_RACK_SLOTS) {
                            val slotLoad = (player.getSlotCpuLoad(i) * 100f).coerceIn(0f, 100f)

                            if (i < slotCpuLoads.size) {
                                slotCpuLoads[i] = slotLoad
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
                    }
                }

                delay(CPU_MONITOR_INTERVAL_MS)
            }
        }
    }

    val activeSlot: RackSlotData
        get() = slots[activeSlotIndex.coerceIn(0, NUM_RACK_SLOTS - 1)]

    fun selectActiveSlot(slotIndex: Int) {
        if (slotIndex in 0 until NUM_RACK_SLOTS) {
            activeSlotIndex = slotIndex
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

    fun refreshPluginList() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val plugins = repository.queryPlugins(getApplication())
                withContext(Dispatchers.Main) {
                    pluginList = plugins
                    statusMessage = "Found ${plugins.size} AAP plugin(s) on system."
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
            selectedPresetIndex = 0,
            isLoading = true,
            loadingPluginName = plugin.displayName
        )
        slotParameterValues[slotIndex].clear()
        slotNativeUiZoomStates[slotIndex].reset()

        viewModelScope.launch {
            try {
                val (client, instance) = hostEngine.instantiatePluginForSlot(slotIndex, plugin, sampleRate, framesPerCallback)

                // Populate dynamic parameters and ports if missing from static aap_metadata.xml
                if (plugin.parameters.isEmpty()) {
                    val paramCount = instance.getParameterCount()

                    for (i in 0 until paramCount) {
                        plugin.parameters.add(instance.getParameter(i))
                    }
                }

                if (plugin.ports.isEmpty()) {
                    val portCount = instance.getPortCount()

                    for (i in 0 until portCount) {
                        plugin.ports.add(instance.getPort(i))
                    }
                }

                slotParameterValues[slotIndex].clear()
                plugin.parameters.forEach { param ->
                    slotParameterValues[slotIndex][param.id] = param.defaultValue
                }

                slots[slotIndex] = slots[slotIndex].copy(
                    pluginInfo = plugin,
                    instance = instance,
                    isBypassed = false,
                    selectedPresetIndex = 0,
                    isLoading = false,
                    loadingPluginName = null
                )

                audioPlayer?.setSlotBypassed(slotIndex, false)
                audioPlayer?.setSlotPlugin(slotIndex, instance, client)

                // Dispatch initial default parameter values to plugin instance
                plugin.parameters.forEach { param ->
                    audioPlayer?.setParameterValue(slotIndex, param, param.defaultValue)
                }

                activeSlotIndex = slotIndex

                if (!isProcessing) {
                    toggleAudioPlayback()
                }

                statusMessage = "Loaded ${plugin.displayName} into ${slots[slotIndex].title} (${slots[slotIndex].slotType})"
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
            selectedPresetIndex = 0,
            isLoading = false,
            loadingPluginName = null
        )
        slotParameterValues[slotIndex].clear()
        slotNativeUiZoomStates[slotIndex].reset()
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
        statusMessage = "${slots[slotIndex].title} ${if (newBypass) "BYPASSED" else "ACTIVE"}"
    }

    fun toggleAudioPlayback() {
        val player = audioPlayer

        if (player == null) {
            return
        }

        if (isProcessing) {
            player.pause()
            isProcessing = false
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

        slotParameterValues[slotIndex][parameter.id] = value
        audioPlayer?.setParameterValue(slotIndex, parameter, value)
    }

    fun setPreset(slotIndex: Int, index: Int) {
        if (slotIndex !in 0 until NUM_RACK_SLOTS) {
            return
        }

        slots[slotIndex] = slots[slotIndex].copy(selectedPresetIndex = index)
        audioPlayer?.setPresetIndex(slotIndex, index)
        statusMessage = "${slots[slotIndex].title} Preset changed to #$index"
    }

    override fun onCleared() {
        super.onCleared()

        for (i in 0 until NUM_RACK_SLOTS) {
            unloadSlot(i)
        }

        try {
            audioPlayer?.close()
            hostEngine.close()
        } catch (e: Throwable) {
            Log.e(tag, "Error closing host engine", e)
        }
    }
}
