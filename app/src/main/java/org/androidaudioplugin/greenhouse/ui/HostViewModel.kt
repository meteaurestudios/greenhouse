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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.greenhouse.core.AapHostEngine
import org.androidaudioplugin.greenhouse.ui.host.AudioEngineController
import org.androidaudioplugin.greenhouse.ui.host.MidiDeviceController
import org.androidaudioplugin.greenhouse.ui.host.PluginBrowserController
import org.androidaudioplugin.greenhouse.ui.host.RackController
import org.androidaudioplugin.greenhouse.ui.host.RackMeters
import org.androidaudioplugin.greenhouse.ui.host.RackSessionController
import org.androidaudioplugin.greenhouse.ui.host.VirtualKeyboardController

/**
 * Composition root for the host: owns the long-lived engine objects, wires the feature
 * controllers together, runs the engine monitor loop, and follows the app lifecycle.
 * Screens talk to the controllers directly (`viewModel.rack`, `viewModel.audio`, ...);
 * only operations spanning several controllers live here.
 */
class HostViewModel(application: Application) : AndroidViewModel(application), DefaultLifecycleObserver {
    companion object {
        private const val TAG = "HostViewModel"
        const val MONITOR_INTERVAL_MS = 33L
        const val CPU_UPDATE_TICKS = 3
        const val PARAM_SYNC_INTERVAL_TICKS = 2
    }

    var statusMessage by mutableStateOf("Welcome to AAP Studio Host")
        private set

    private val postStatus: (String) -> Unit = { statusMessage = it }

    private val hostEngine = AapHostEngine(application, RackController.NUM_RACK_SLOTS)

    val audio = AudioEngineController(application, RackController.NUM_RACK_SLOTS, postStatus)
    val meters = RackMeters(RackController.NUM_RACK_SLOTS)
    val rack = RackController(hostEngine, audio, viewModelScope, postStatus)
    val browser = PluginBrowserController(application, viewModelScope, postStatus)
    val keyboard = VirtualKeyboardController(audio.player)
    val midi = MidiDeviceController(application, viewModelScope, audio.player, keyboard, postStatus)
    val sessions = RackSessionController(application, rack, audio, browser, viewModelScope, postStatus)

    init {
        sessions.refreshSavedSessions()
        browser.refresh {
            sessions.restoreAutosaveIfRackEmpty()
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

    fun loadPluginIntoSlot(slotIndex: Int, plugin: PluginInformation) {
        if (rack.loadPlugin(slotIndex, plugin)) {
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

        sessions.autosave()
        audio.onAppBackground()
    }

    /** Meters every tick, CPU and plugin-side parameter changes at lower rates. */
    private fun startMonitoring() {
        viewModelScope.launch(Dispatchers.Default) {
            var tickCount = 0

            while (isActive) {
                meters.poll(audio.player, audio.isProcessing, updateCpu = tickCount % CPU_UPDATE_TICKS == 0)

                if (tickCount % PARAM_SYNC_INTERVAL_TICKS == 0) {
                    rack.pollPluginParameterChanges()
                }

                tickCount++
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        rack.unloadAll()

        try {
            midi.close()
            audio.close()
            hostEngine.close()
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing host engine", e)
        }
    }
}
