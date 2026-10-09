package org.androidaudioplugin.greenhouse.ui

import android.app.Application
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidaudioplugin.greenhouse.device.DeviceInfo
import org.androidaudioplugin.greenhouse.ui.host.AudioEngineController
import org.androidaudioplugin.greenhouse.ui.host.MidiDeviceController
import org.androidaudioplugin.greenhouse.ui.host.PluginBrowserController
import org.androidaudioplugin.greenhouse.ui.host.RackController
import org.androidaudioplugin.greenhouse.ui.host.RackMeters
import org.androidaudioplugin.greenhouse.ui.host.RackSessionController
import org.androidaudioplugin.greenhouse.ui.host.SequencerController
import org.androidaudioplugin.greenhouse.ui.host.VirtualKeyboardController

/**
 * Composition root for the host: owns the long-lived engine objects, wires the feature
 * controllers together, runs the engine monitor loop, and follows the app lifecycle.
 * Screens talk to the controllers directly (`viewModel.rack`, `viewModel.audio`, ...);
 * only operations spanning several controllers live here.
 */
class HostViewModel(application: Application, config: HostConfig) : AndroidViewModel(application), DefaultLifecycleObserver {
    /** With the default configuration (AAP plugins only), e.g. for `by viewModels()`. */
    constructor(application: Application) : this(application, HostConfig())

    companion object {
        private const val TAG = "HostViewModel"
        const val MONITOR_INTERVAL_MS = 33L
        const val CPU_UPDATE_TICKS = 3
        const val PARAM_SYNC_INTERVAL_TICKS = 2
        const val ENGINE_SYNC_TICKS = 3

        /** Creates the view model with [config], e.g. `by viewModels { HostViewModel.factory(config) }`. */
        fun factory(config: HostConfig): ViewModelProvider.Factory {
            return viewModelFactory {
                initializer {
                    HostViewModel(checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]), config)
                }
            }
        }
    }

    var statusMessage by mutableStateOf("Welcome to AAP Studio Host")
        private set

    private val postStatus: (String) -> Unit = { statusMessage = it }

    private val sources = config.createSources(application, RackController.NUM_RACK_SLOTS)

    val audio = AudioEngineController(RackController.NUM_RACK_SLOTS, postStatus)
    val meters = RackMeters(RackController.NUM_RACK_SLOTS)
    val rack = RackController(sources, audio, viewModelScope, postStatus)
    val browser = PluginBrowserController(sources, viewModelScope, postStatus)
    val keyboard = VirtualKeyboardController(audio.engine)
    val midi = MidiDeviceController(application, viewModelScope, audio.engine, keyboard, postStatus)
    val sequencer = SequencerController(application, audio, viewModelScope, postStatus)
    val sessions = RackSessionController(application, rack, audio, browser, sequencer, viewModelScope, postStatus)

    init {
        sessions.refreshSavedSessions()
        browser.refresh {
            sessions.restoreAutosaveIfRackEmpty()
        }

        // Plugins installed or removed meanwhile, e.g. from the catalog link, show up without a restart
        for (source in sources) {
            source.setOnDevicesChanged { browser.refreshAfterDevicesChanged() }
        }

        startMonitoring()
    }

    fun openBrowserForSlot(slotIndex: Int) {
        if (!rack.isValidSlot(slotIndex)) {
            return
        }

        browser.openForSlot(slotIndex)
        rack.selectActiveSlot(slotIndex)
    }

    fun loadDeviceIntoSlot(slotIndex: Int, info: DeviceInfo) {
        if (rack.loadDevice(slotIndex, info)) {
            browser.targetSlotIndex = slotIndex
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        audio.onAppForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        val isChangingConfig = (owner as? ComponentActivity)?.isChangingConfigurations ?: false

        if (isChangingConfig) {
            return
        }

        // Audio first: reading the plugin states while they render competes with the audio thread
        audio.onAppBackground()
        sessions.autosave()
    }

    /** Meters and the sequencer every tick; CPU, plugin-side parameter changes and engine state changes at lower rates. */
    private fun startMonitoring() {
        viewModelScope.launch(Dispatchers.Default) {
            var tickCount = 0

            while (isActive) {
                meters.poll(audio.engine, audio.isProcessing, updateCpu = tickCount % CPU_UPDATE_TICKS == 0)
                sequencer.poll()

                if (tickCount % PARAM_SYNC_INTERVAL_TICKS == 0) {
                    rack.pollDeviceParameterChanges()
                }

                if (tickCount % ENGINE_SYNC_TICKS == 0) {
                    withContext(Dispatchers.Main) {
                        followEngine()
                    }
                }

                tickCount++
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    /**
     * Follows what the engine changed on its own after a device error. AAP plugins cannot be re-prepared,
     * so after a change to another sample rate the rack is re-created at the new rate with its state;
     * until then the engine keeps those plugins silent. Retried at the next check while another rack
     * operation is in progress.
     */
    private fun followEngine() {
        audio.syncWithEngine()

        if (rack.hasDevicesToReloadAt(audio.sampleRate)) {
            sessions.reloadAtSampleRate(audio.sampleRate)
        }
    }

    override fun onCleared() {
        super.onCleared()
        rack.unloadAll()

        try {
            midi.close()
            audio.close()

            for (source in sources) {
                source.close()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing the engine or a device source", e)
        }
    }
}
