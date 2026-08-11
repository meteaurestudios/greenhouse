package org.androidaudioplugin.host.ui

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

    var selectedPlugin by mutableStateOf<PluginInformation?>(null)
        private set

    var activeInstance by mutableStateOf<NativeRemotePluginInstance?>(null)
        private set

    var audioPlayer by mutableStateOf<AapAudioPlayer?>(null)
        private set

    var isProcessing by mutableStateOf(false)
        private set

    var isInstantiating by mutableStateOf(false)
        private set

    var statusMessage by mutableStateOf<String?>("Scan complete. Select a plugin to load.")
        private set

    val parameterValues = mutableStateMapOf<Int, Double>()

    var selectedPresetIndex by mutableIntStateOf(0)
        private set

    val sampleRate: Int
    val framesPerCallback: Int

    init {
        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 44100
        framesPerCallback = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
        refreshPluginList()
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

    fun loadPlugin(plugin: PluginInformation) {
        if (selectedPlugin?.pluginId == plugin.pluginId && activeInstance != null) {
            return
        }

        viewModelScope.launch {
            isInstantiating = true
            statusMessage = "Instantiating ${plugin.displayName}..."

            // Teardown previous player and instance if any
            unloadActivePlugin()

            try {
                selectedPlugin = plugin

                // Create audio player for engine output
                val player = AapAudioPlayer.create(sampleRate, framesPerCallback)
                player.loadSampleAudio(getApplication())

                // Instantiate native remote plugin instance
                val instance = hostEngine.instantiatePlugin(plugin, sampleRate, framesPerCallback)
                activeInstance = instance

                // Attach plugin instance to audio player engine
                player.attachPlugin(instance)
                audioPlayer = player

                // Initialize parameter defaults
                parameterValues.clear()
                plugin.parameters.forEach { param ->
                    parameterValues[param.id] = param.defaultValue
                }

                statusMessage = "Successfully loaded ${plugin.displayName}"
            } catch (e: Throwable) {
                Log.e(tag, "Failed to load plugin ${plugin.displayName}", e)
                statusMessage = "Error loading plugin: ${e.localizedMessage ?: e.message}"
                selectedPlugin = null
                activeInstance = null
                audioPlayer = null
            } finally {
                isInstantiating = false
            }
        }
    }

    fun toggleAudioPlayback() {
        val player = audioPlayer ?: return
        if (isProcessing) {
            player.pause()
            isProcessing = false
            statusMessage = "Audio processing paused."
        } else {
            player.start()
            isProcessing = true
            statusMessage = "Audio processing active."
        }
    }

    fun triggerSampleAudio() {
        audioPlayer?.playSampleAudio()
        statusMessage = "Playing test sample audio through plugin..."
    }

    fun sendNoteOn(note: Int, velocity: Float = 1.0f) {
        audioPlayer?.sendNoteOn(note, velocity)
    }

    fun sendNoteOff(note: Int, velocity: Float = 0.0f) {
        audioPlayer?.sendNoteOff(note, velocity)
    }

    fun setParameterValue(parameter: ParameterInformation, value: Double) {
        parameterValues[parameter.id] = value
        audioPlayer?.setParameterValue(parameter, value)
    }

    fun setPreset(index: Int) {
        selectedPresetIndex = index
        audioPlayer?.setPresetIndex(index)
        statusMessage = "Preset changed to #$index"
    }

    private fun unloadActivePlugin() {
        try {
            audioPlayer?.close()
        } catch (e: Throwable) {
            Log.e(tag, "Error closing audio player", e)
        }
        audioPlayer = null
        activeInstance = null
        isProcessing = false
        parameterValues.clear()
    }

    override fun onCleared() {
        super.onCleared()
        unloadActivePlugin()
        try {
            hostEngine.close()
        } catch (e: Throwable) {
            Log.e(tag, "Error closing host engine", e)
        }
    }
}
