package org.androidaudioplugin.greenhouse.core

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages external MIDI controllers (USB, Bluetooth LE, and Virtual MIDI) via Android MidiManager.
 * Connects to controller output ports and parses incoming MIDI 1.0 and UMP streams in real-time.
 */
class MidiControllerManager(
    private val context: Context,
    private val listener: MidiEventListener
) {

    interface MidiEventListener {
        fun onMidiDevicesChanged(devices: List<MidiDeviceInfo>, activeDevice: MidiDeviceInfo?)
        fun onMidiDeviceConnectionStateChanged(device: MidiDeviceInfo?, isConnected: Boolean, message: String)
        fun onNoteOn(note: Int, velocity: Float)
        fun onNoteOff(note: Int, velocity: Float)
        fun onPitchBend(value: Float)
        fun onPressure(note: Int, value: Float)
        fun onControlChange(controller: Int, value: Float)
        fun onRawUmp(bytes: ByteArray)
    }

    companion object {
        private const val TAG = "MidiControllerManager"

        // MIDI 1.0 Status Bitmasks and Constants
        const val STATUS_MASK = 0x80
        const val STATUS_TYPE_MASK = 0xF0
        const val CHANNEL_MASK = 0x0F

        const val STATUS_NOTE_OFF = 0x80
        const val STATUS_NOTE_ON = 0x90
        const val STATUS_POLY_PRESSURE = 0xA0
        const val STATUS_CONTROL_CHANGE = 0xB0
        const val STATUS_PROGRAM_CHANGE = 0xC0
        const val STATUS_CHANNEL_PRESSURE = 0xD0
        const val STATUS_PITCH_BEND = 0xE0

        const val STATUS_SYSEX_START = 0xF0
        const val STATUS_TIME_CODE = 0xF1
        const val STATUS_SONG_POSITION = 0xF2
        const val STATUS_SONG_SELECT = 0xF3
        const val STATUS_TUNE_REQUEST = 0xF6
        const val STATUS_SYSEX_END = 0xF7

        const val STATUS_TIMING_CLOCK = 0xF8
        const val STATUS_START = 0xFA
        const val STATUS_CONTINUE = 0xFB
        const val STATUS_STOP = 0xFC
        const val STATUS_ACTIVE_SENSING = 0xFE
        const val STATUS_SYSTEM_RESET = 0xFF

        const val PITCH_BEND_CENTER_14BIT = 8192
        const val PITCH_BEND_MAX_14BIT = 16383
        const val MIDI_MAX_7BIT_VALUE = 127.0f
        const val MIDI_MAX_16BIT_VALUE = 65535
        const val DEFAULT_MIDI_CHANNEL = 0
        const val DEFAULT_MIDI_GROUP = 0

        const val TOTAL_MIDI_NOTES = 128

        fun getNoteName(note: Int): String {
            if (note < 0 || note >= TOTAL_MIDI_NOTES) {
                return "N/A"
            }

            val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
            val name = noteNames[note % 12]
            val oct = (note / 12) - 1
            return "$name$oct"
        }

        fun getDeviceDisplayName(deviceInfo: MidiDeviceInfo): String {
            val properties = deviceInfo.properties
            val name = properties.getString(MidiDeviceInfo.PROPERTY_NAME)

            if (!name.isNullOrBlank()) {
                return name
            }

            val product = properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)

            if (!product.isNullOrBlank()) {
                val manufacturer = properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)

                if (!manufacturer.isNullOrBlank()) {
                    return "$manufacturer $product"
                }

                return product
            }

            return "MIDI Device #${deviceInfo.id}"
        }

        fun getDeviceManufacturer(deviceInfo: MidiDeviceInfo): String {
            val manufacturer = deviceInfo.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)

            if (!manufacturer.isNullOrBlank()) {
                return manufacturer
            }

            return "Unknown Manufacturer"
        }
    }

    private val midiManager: MidiManager? = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeDevice: MidiDevice? = null
    private var activeDeviceInfo: MidiDeviceInfo? = null
    private var activeOutputPorts = mutableListOf<MidiOutputPort>()

    private val activeNotes = BooleanArray(TOTAL_MIDI_NOTES) { false }

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            Log.d(TAG, "MIDI device added: ${getDeviceDisplayName(device)} (ID: ${device.id})")
            notifyDevicesUpdated()

            val isAllowed = includeVirtualDevices || (device.type != MidiDeviceInfo.TYPE_VIRTUAL)

            if (activeDeviceInfo == null && hasOutputPorts(device) && isAllowed) {
                openDevice(device)
            }
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            Log.d(TAG, "MIDI device removed: ${getDeviceDisplayName(device)} (ID: ${device.id})")

            if (activeDeviceInfo?.id == device.id) {
                closeCurrentDevice()
                listener.onMidiDeviceConnectionStateChanged(null, false, "Device disconnected: ${getDeviceDisplayName(device)}")
            }

            notifyDevicesUpdated()
        }

        override fun onDeviceStatusChanged(status: android.media.midi.MidiDeviceStatus) {
            notifyDevicesUpdated()
        }
    }

    // Stream Parser State for MIDI 1.0 byte stream
    private var runningStatus = 0
    private var pendingStatus = 0
    private var pendingByte1 = -1
    private var isInsideSysex = false

    private val midiReceiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray?, offset: Int, count: Int, timestamp: Long) {
            if (msg == null || count <= 0) {
                return
            }

            parseMidiBytes(msg, offset, count)
        }
    }

    init {
        val manager = midiManager

        if (manager != null) {
            manager.registerDeviceCallback(deviceCallback, mainHandler)
            autoConnectFirstAvailableDevice()
        } else {
            Log.w(TAG, "MidiManager not available on this device")
        }
    }

    var includeVirtualDevices: Boolean = false
        set(value) {
            field = value
            notifyDevicesUpdated()
        }

    fun getAvailableDevices(): List<MidiDeviceInfo> {
        val manager = midiManager

        if (manager == null) {
            return emptyList()
        }

        val allDevices = manager.devices.toList()

        if (!includeVirtualDevices) {
            return allDevices.filter { it.type != MidiDeviceInfo.TYPE_VIRTUAL }
        }

        return allDevices
    }

    fun getActiveDeviceInfo(): MidiDeviceInfo? {
        return activeDeviceInfo
    }

    fun isConnected(): Boolean {
        return activeDevice != null && activeDeviceInfo != null
    }

    fun hasOutputPorts(device: MidiDeviceInfo): Boolean {
        return device.outputPortCount > 0
    }

    fun rescanDevices() {
        notifyDevicesUpdated()

        if (activeDeviceInfo == null) {
            autoConnectFirstAvailableDevice()
        }
    }

    fun autoConnectFirstAvailableDevice() {
        val devices = getAvailableDevices()

        for (device in devices) {
            if (hasOutputPorts(device)) {
                openDevice(device)
                return
            }
        }
    }

    fun openDevice(deviceInfo: MidiDeviceInfo) {
        val manager = midiManager

        if (manager == null) {
            return
        }

        if (activeDeviceInfo?.id == deviceInfo.id && activeDevice != null) {
            return
        }

        closeCurrentDevice()

        Log.d(TAG, "Opening MIDI device: ${getDeviceDisplayName(deviceInfo)}")

        manager.openDevice(deviceInfo, { device ->
            if (device != null) {
                activeDevice = device
                activeDeviceInfo = deviceInfo

                val outputPortCount = deviceInfo.outputPortCount
                activeOutputPorts.clear()

                for (portIndex in 0 until outputPortCount) {
                    try {
                        val outputPort = device.openOutputPort(portIndex)

                        if (outputPort != null) {
                            outputPort.connect(midiReceiver)
                            activeOutputPorts.add(outputPort)
                            Log.d(TAG, "Connected to output port $portIndex on ${getDeviceDisplayName(deviceInfo)}")
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error opening output port $portIndex", e)
                    }
                }

                val devName = getDeviceDisplayName(deviceInfo)
                listener.onMidiDeviceConnectionStateChanged(deviceInfo, true, "Connected to $devName")
                notifyDevicesUpdated()
            } else {
                Log.e(TAG, "Failed to open MIDI device: ${getDeviceDisplayName(deviceInfo)}")
                listener.onMidiDeviceConnectionStateChanged(deviceInfo, false, "Failed to open device")
            }
        }, mainHandler)
    }

    fun closeCurrentDevice() {
        releaseAllActiveNotes()

        for (port in activeOutputPorts) {
            try {
                port.disconnect(midiReceiver)
                port.close()
            } catch (e: Throwable) {
                Log.e(TAG, "Error closing MIDI output port", e)
            }
        }

        activeOutputPorts.clear()

        val device = activeDevice

        if (device != null) {
            try {
                device.close()
            } catch (e: Throwable) {
                Log.e(TAG, "Error closing MIDI device", e)
            }

            activeDevice = null
        }

        activeDeviceInfo = null
        resetParserState()
    }

    private fun resetParserState() {
        runningStatus = 0
        pendingStatus = 0
        pendingByte1 = -1
        isInsideSysex = false
    }

    private fun releaseAllActiveNotes() {
        for (note in 0 until TOTAL_MIDI_NOTES) {
            if (activeNotes[note]) {
                activeNotes[note] = false
                listener.onNoteOff(note, 0.0f)
            }
        }
    }

    private fun notifyDevicesUpdated() {
        val devices = getAvailableDevices()
        listener.onMidiDevicesChanged(devices, activeDeviceInfo)
    }

    /**
     * Parses a chunk of incoming raw MIDI bytes.
     * Supports MIDI 1.0 byte stream with running status, and multi-byte messages.
     */
    fun parseMidiBytes(msg: ByteArray, offset: Int, count: Int) {
        val end = offset + count

        for (i in offset until end) {
            val raw = msg[i].toInt() and 0xFF

            // Handle System Real-Time Messages (can appear anywhere in stream without affecting running status)
            if (raw >= STATUS_TIMING_CLOCK) {
                continue
            }

            // Handle Status Bytes
            if ((raw and STATUS_MASK) != 0) {
                if (raw == STATUS_SYSEX_START) {
                    isInsideSysex = true
                    runningStatus = 0
                    pendingByte1 = -1
                    continue
                }

                if (raw == STATUS_SYSEX_END) {
                    isInsideSysex = false
                    runningStatus = 0
                    pendingByte1 = -1
                    continue
                }

                if (raw >= STATUS_SYSEX_START) {
                    // Other System Common messages clear running status
                    isInsideSysex = false
                    runningStatus = 0
                    pendingByte1 = -1
                    continue
                }

                // Channel Voice Message status byte (0x80 - 0xEF)
                isInsideSysex = false
                runningStatus = raw
                pendingStatus = raw
                pendingByte1 = -1
                continue
            }

            // Data byte (0x00 - 0x7F)
            if (isInsideSysex) {
                continue
            }

            var status = pendingStatus

            if (status == 0 && runningStatus != 0) {
                status = runningStatus
            }

            if (status == 0) {
                continue
            }

            val statusType = status and STATUS_TYPE_MASK
            val channel = status and CHANNEL_MASK

            when (statusType) {
                STATUS_PROGRAM_CHANGE -> {
                    // 1-byte message: program number
                    val program = raw
                    // Reset pending for next byte
                    pendingStatus = runningStatus
                    pendingByte1 = -1
                }

                STATUS_CHANNEL_PRESSURE -> {
                    // 1-byte message: pressure value
                    val pressureNorm = raw.toFloat() / MIDI_MAX_7BIT_VALUE
                    listener.onPressure(-1, pressureNorm)
                    pendingStatus = runningStatus
                    pendingByte1 = -1
                }

                STATUS_NOTE_ON -> {
                    if (pendingByte1 < 0) {
                        pendingByte1 = raw
                    } else {
                        val note = pendingByte1
                        val velocityRaw = raw
                        val velocityNorm = velocityRaw.toFloat() / MIDI_MAX_7BIT_VALUE

                        if (velocityRaw == 0) {
                            // Note On with velocity 0 is treated as Note Off per standard MIDI specification
                            activeNotes[note.coerceIn(0, TOTAL_MIDI_NOTES - 1)] = false
                            listener.onNoteOff(note, 0.0f)
                        } else {
                            activeNotes[note.coerceIn(0, TOTAL_MIDI_NOTES - 1)] = true
                            listener.onNoteOn(note, velocityNorm)
                        }

                        pendingByte1 = -1
                        pendingStatus = runningStatus
                    }
                }

                STATUS_NOTE_OFF -> {
                    if (pendingByte1 < 0) {
                        pendingByte1 = raw
                    } else {
                        val note = pendingByte1
                        val velocityRaw = raw
                        val velocityNorm = velocityRaw.toFloat() / MIDI_MAX_7BIT_VALUE

                        activeNotes[note.coerceIn(0, TOTAL_MIDI_NOTES - 1)] = false
                        listener.onNoteOff(note, velocityNorm)

                        pendingByte1 = -1
                        pendingStatus = runningStatus
                    }
                }

                STATUS_POLY_PRESSURE -> {
                    if (pendingByte1 < 0) {
                        pendingByte1 = raw
                    } else {
                        val note = pendingByte1
                        val pressureNorm = raw.toFloat() / MIDI_MAX_7BIT_VALUE

                        listener.onPressure(note, pressureNorm)

                        pendingByte1 = -1
                        pendingStatus = runningStatus
                    }
                }

                STATUS_CONTROL_CHANGE -> {
                    if (pendingByte1 < 0) {
                        pendingByte1 = raw
                    } else {
                        val ccNumber = pendingByte1
                        val ccValueNorm = raw.toFloat() / MIDI_MAX_7BIT_VALUE

                        listener.onControlChange(ccNumber, ccValueNorm)

                        pendingByte1 = -1
                        pendingStatus = runningStatus
                    }
                }

                STATUS_PITCH_BEND -> {
                    if (pendingByte1 < 0) {
                        pendingByte1 = raw
                    } else {
                        val lsb = pendingByte1
                        val msb = raw
                        val pitchBend14 = (msb shl 7) or lsb
                        // Normalize 0..16383 to -1.0 .. +1.0 where 8192 is 0.0
                        val pitchBendNorm = ((pitchBend14 - PITCH_BEND_CENTER_14BIT).toFloat() / PITCH_BEND_CENTER_14BIT.toFloat()).coerceIn(-1.0f, 1.0f)

                        listener.onPitchBend(pitchBendNorm)

                        pendingByte1 = -1
                        pendingStatus = runningStatus
                    }
                }

                else -> {
                    pendingByte1 = -1
                    pendingStatus = runningStatus
                }
            }
        }
    }

    fun close() {
        val manager = midiManager

        if (manager != null) {
            try {
                manager.unregisterDeviceCallback(deviceCallback)
            } catch (e: Throwable) {
                Log.e(TAG, "Error unregistering device callback", e)
            }
        }

        closeCurrentDevice()
    }
}
