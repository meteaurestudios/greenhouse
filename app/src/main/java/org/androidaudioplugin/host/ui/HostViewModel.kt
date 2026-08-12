package org.androidaudioplugin.host.ui

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.host.core.AapAudioPlayer
import org.androidaudioplugin.host.core.AapHostEngine
import org.androidaudioplugin.host.data.PluginCategory
import org.androidaudioplugin.host.data.PluginRepository
import org.androidaudioplugin.hosting.NativeRemotePluginInstance

enum class StudioRackViewMode(val title: String) {
    PARAMETERS("Parameter Controls"),
    NATIVE_SURFACE("Native Plugin GUI"),
    SPECS("Ports & Details")
}

data class RackSlotData(
    val index: Int,
    val title: String,
    val slotType: String,
    val pluginInfo: PluginInformation? = null,
    val instance: NativeRemotePluginInstance? = null,
    val isBypassed: Boolean = false,
    val selectedPresetIndex: Int = 0
)

class HostViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = "HostViewModel"

    private val repository = PluginRepository()
    private val hostEngine = AapHostEngine(application)

    var pluginList by mutableStateOf<List<PluginInformation>>(emptyList())
        private set

    var selectedCategory by mutableStateOf(PluginCategory.ALL)
        private set

    var searchQuery by mutableStateOf("")
        private set

    // Multi-slot rack state (0: Instrument, 1: Effect 1, 2: Effect 2)
    val slots = mutableStateListOf(
        RackSlotData(0, "Slot 1", "Instrument"),
        RackSlotData(1, "Slot 2", "Effect 1"),
        RackSlotData(2, "Slot 3", "Effect 2")
    )

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

    var isSamplePressed by mutableStateOf(false)
        private set

    var isInstantiating by mutableStateOf(false)
        private set

    var statusMessage by mutableStateOf<String?>("Scan complete. Select an AAP plugin from the catalog to load.")
        private set

    val slotParameterValues = Array(3) { mutableStateMapOf<Int, Double>() }

    val sampleRate: Int
    val framesPerCallback: Int

    init {
        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 44100
        framesPerCallback = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256

        audioPlayer = AapAudioPlayer.create(sampleRate, framesPerCallback)
        audioPlayer?.loadSampleAudio(application)

        refreshPluginList()
    }

    val activeSlot: RackSlotData
        get() = slots[activeSlotIndex.coerceIn(0, 2)]

    fun selectActiveSlot(slotIndex: Int) {
        if (slotIndex in 0..2) {
            activeSlotIndex = slotIndex
        }
    }

    fun openBrowserForSlot(slotIndex: Int) {
        if (slotIndex in 0..2) {
            targetBrowserSlotIndex = slotIndex
            selectedCategory = if (slotIndex == 0) PluginCategory.SYNTH else PluginCategory.EFFECT
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
                val matchesCategory = when (selectedCategory.id) {
                    PluginCategory.SYNTH.id -> repository.getPluginCategory(plugin) == PluginCategory.SYNTH
                    PluginCategory.EFFECT.id -> repository.getPluginCategory(plugin) == PluginCategory.EFFECT
                    PluginCategory.OTHER.id -> repository.getPluginCategory(plugin) == PluginCategory.OTHER
                    else -> true
                }
                val matchesSearch = searchQuery.isBlank() ||
                        plugin.displayName.contains(searchQuery, ignoreCase = true) ||
                        (plugin.developer?.contains(searchQuery, ignoreCase = true) == true) ||
                        (plugin.pluginId?.contains(searchQuery, ignoreCase = true) == true)
                matchesCategory && matchesSearch
            }
        }

    fun loadPluginIntoSlot(slotIndex: Int, plugin: PluginInformation) {
        if (slotIndex !in 0..2) return

        viewModelScope.launch {
            isInstantiating = true
            statusMessage = "Instantiating ${plugin.displayName} in ${slots[slotIndex].title}..."

            unloadSlot(slotIndex)

            try {
                val instance = hostEngine.instantiatePlugin(plugin, sampleRate, framesPerCallback)

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

                slots[slotIndex] = slots[slotIndex].copy(
                    pluginInfo = plugin,
                    instance = instance,
                    isBypassed = false,
                    selectedPresetIndex = 0
                )

                audioPlayer?.setSlotPlugin(slotIndex, instance)

                slotParameterValues[slotIndex].clear()
                plugin.parameters.forEach { param ->
                    slotParameterValues[slotIndex][param.id] = param.defaultValue
                }

                activeSlotIndex = slotIndex

                if (!isProcessing) {
                    toggleAudioPlayback()
                }

                statusMessage = "Loaded ${plugin.displayName} into ${slots[slotIndex].title} (${slots[slotIndex].slotType})"
            } catch (e: Throwable) {
                Log.e(tag, "Failed to load plugin ${plugin.displayName}", e)
                statusMessage = "Error loading plugin: ${e.localizedMessage ?: e.message}"
            } finally {
                isInstantiating = false
            }
        }
    }

    fun unloadSlot(slotIndex: Int) {
        if (slotIndex !in 0..2) return
        val currentInst = slots[slotIndex].instance
        audioPlayer?.setSlotPlugin(slotIndex, null)
        try {
            currentInst?.destroy()
        } catch (e: Throwable) {
            Log.e(tag, "Error destroying plugin instance", e)
        }

        slots[slotIndex] = slots[slotIndex].copy(
            pluginInfo = null,
            instance = null,
            isBypassed = false,
            selectedPresetIndex = 0
        )
        slotParameterValues[slotIndex].clear()
        statusMessage = "Cleared ${slots[slotIndex].title}"
    }

    fun toggleSlotBypass(slotIndex: Int) {
        if (slotIndex !in 0..2) return
        val newBypass = !slots[slotIndex].isBypassed
        slots[slotIndex] = slots[slotIndex].copy(isBypassed = newBypass)
        audioPlayer?.setSlotBypassed(slotIndex, newBypass)
        statusMessage = "${slots[slotIndex].title} ${if (newBypass) "BYPASSED" else "ACTIVE"}"
    }

    fun toggleAudioPlayback() {
        val player = audioPlayer ?: return
        if (isProcessing) {
            player.pause()
            isProcessing = false
            statusMessage = "Audio engine paused."
        } else {
            player.start()
            isProcessing = true
            statusMessage = "Audio engine ACTIVE (Low-latency audio rack running)."
        }
    }

    fun triggerSampleAudio() {
        val inst0Slot = slots[0]
        val isInst0Active = inst0Slot.pluginInfo != null && !inst0Slot.isBypassed && inst0Slot.instance != null
        if (isInst0Active) {
            statusMessage = "Instrument active — play notes on keyboard to test!"
            return
        }
        audioPlayer?.playSampleAudio()
        statusMessage = "Playing test sample audio through effect chain..."
    }

    fun sendNoteOn(note: Int, velocity: Float = 1.0f) {
        audioPlayer?.sendNoteOn(note, velocity)
    }

    fun sendNoteOff(note: Int, velocity: Float = 0.0f) {
        audioPlayer?.sendNoteOff(note, velocity)
    }

    fun setParameterValue(slotIndex: Int, parameter: ParameterInformation, value: Double) {
        if (slotIndex !in 0..2) return
        slotParameterValues[slotIndex][parameter.id] = value
        audioPlayer?.setParameterValue(slotIndex, parameter, value)
    }

    fun setPreset(slotIndex: Int, index: Int) {
        if (slotIndex !in 0..2) return
        slots[slotIndex] = slots[slotIndex].copy(selectedPresetIndex = index)
        audioPlayer?.setPresetIndex(slotIndex, index)
        statusMessage = "${slots[slotIndex].title} Preset changed to #$index"
    }

    override fun onCleared() {
        super.onCleared()
        for (i in 0..2) {
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

