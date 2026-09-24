package org.androidaudioplugin.greenhouse.ui.host

import android.content.Context
import android.media.midi.MidiDeviceInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.androidaudioplugin.greenhouse.core.AapAudioPlayer
import org.androidaudioplugin.greenhouse.core.MidiControllerManager
import java.util.Locale

/**
 * Hardware MIDI input: device discovery / connection, and routing of incoming events to the
 * instrument slot. Events are forwarded on the MIDI thread; UI state is updated on Main.
 */
class MidiDeviceController(
    context: Context,
    private val scope: CoroutineScope,
    private val player: AapAudioPlayer,
    private val keyboard: VirtualKeyboardController,
    private val postStatus: (String) -> Unit
) : AutoCloseable {
    companion object {
        const val MIDI_7BIT_MAX = 127f
        const val EVENT_TEXT_THROTTLE_MS = 50L
        const val TARGET_SLOT_INDEX = RackController.INSTRUMENT_SLOT_INDEX
        const val ALL_NOTES = -1
    }

    var availableDevices by mutableStateOf<List<MidiDeviceInfo>>(emptyList())
        private set

    var activeDevice by mutableStateOf<MidiDeviceInfo?>(null)
        private set

    var isDeviceConnected by mutableStateOf(false)
        private set

    var lastEventText by mutableStateOf<String?>(null)
        private set

    var showVirtualDevices by mutableStateOf(false)
        private set

    private val listener = object : MidiControllerManager.MidiEventListener {
        /** Throttles the event read-out for continuous controllers; touched only on the MIDI thread. */
        private var lastEventTextTimestamp = 0L

        override fun onMidiDevicesChanged(devices: List<MidiDeviceInfo>, activeDevice: MidiDeviceInfo?) {
            onMain {
                availableDevices = devices
                this@MidiDeviceController.activeDevice = activeDevice
                isDeviceConnected = activeDevice != null
            }
        }

        override fun onMidiDeviceConnectionStateChanged(device: MidiDeviceInfo?, isConnected: Boolean, message: String) {
            onMain {
                activeDevice = device
                isDeviceConnected = isConnected
                postStatus(message)
            }
        }

        override fun onNoteOn(note: Int, velocity: Float) {
            onMain {
                if (keyboard.showExternalNoteOn(note)) {
                    lastEventText = "Note On: ${MidiControllerManager.getNoteName(note)} (${(velocity * MIDI_7BIT_MAX).toInt()})"
                }
            }

            player.sendNoteOn(note, velocity)
        }

        override fun onNoteOff(note: Int, velocity: Float) {
            onMain {
                if (keyboard.showExternalNoteOff(note)) {
                    lastEventText = "Note Off: ${MidiControllerManager.getNoteName(note)}"
                }
            }

            player.sendNoteOff(note, velocity)
        }

        override fun onPitchBend(value: Float) {
            player.sendPitchBend(TARGET_SLOT_INDEX, ALL_NOTES, value)
            showThrottled { "Pitch Bend: ${format(value)}" }
        }

        override fun onPressure(note: Int, value: Float) {
            player.sendPressure(TARGET_SLOT_INDEX, note, value)
            showThrottled {
                val target = if (note >= 0) {
                    MidiControllerManager.getNoteName(note)
                } else {
                    "Channel"
                }

                "Pressure ($target): ${format(value)}"
            }
        }

        override fun onControlChange(controller: Int, value: Float) {
            player.sendControlChange(TARGET_SLOT_INDEX, controller, value)
            showThrottled { "CC #$controller: ${(value * MIDI_7BIT_MAX).toInt()}" }
        }

        override fun onRawUmp(bytes: ByteArray) {
            // Direct UMP forwarding
        }

        private fun showThrottled(text: () -> String) {
            val now = System.currentTimeMillis()

            if (now - lastEventTextTimestamp <= EVENT_TEXT_THROTTLE_MS) {
                return
            }

            lastEventTextTimestamp = now
            onMain {
                lastEventText = text()
            }
        }

        private fun format(value: Float): String {
            return String.format(Locale.US, "%.2f", value)
        }
    }

    private val manager = MidiControllerManager(context, listener)

    fun updateShowVirtualDevices(show: Boolean) {
        showVirtualDevices = show
        manager.includeVirtualDevices = show
    }

    fun selectDevice(device: MidiDeviceInfo) {
        manager.openDevice(device)
    }

    fun disconnect() {
        manager.closeCurrentDevice()
        activeDevice = null
        isDeviceConnected = false
        postStatus("MIDI Controller Disconnected")
    }

    fun rescan() {
        manager.rescanDevices()
    }

    override fun close() {
        manager.close()
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main) {
            block()
        }
    }
}
