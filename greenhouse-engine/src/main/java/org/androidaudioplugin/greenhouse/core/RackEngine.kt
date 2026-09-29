package org.androidaudioplugin.greenhouse.core

import android.content.Context
import android.util.Log
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import org.androidaudioplugin.hosting.UmpHelper
import dev.atsushieno.ktmidi.Ump
import dev.atsushieno.ktmidi.UmpFactory
import dev.atsushieno.ktmidi.toPlatformNativeBytes
import java.nio.ByteOrder

/** How the output stream was actually opened, which can be less than the low-latency exclusive MMAP stream requested. */
data class OutputStreamMode(
    val isLowLatency: Boolean,
    val isExclusive: Boolean,
    /** MMAP is the fastest path; without it the stream goes through the legacy path, with larger bursts. */
    val isMMapUsed: Boolean
)

/**
 * Kotlin side of the native `RackEngine` (one per process). Only one instance should be alive at a
 * time: creating one reconfigures the native engine, and closing it releases the audio device.
 */
class RackEngine(
    framesPerCallback: Int,
    val numSlots: Int = DEFAULT_NUM_RACK_SLOTS
) : AutoCloseable {

    companion object {
        private const val TAG = "RackEngine"
        const val DEFAULT_NUM_RACK_SLOTS = 3
        const val MIDI2_SIGNED_32BIT_MAX = 0x8000_0000L
        const val MIDI2_32BIT_MAX = 0xFFFF_FFFFL
        const val MIDI2_16BIT_MAX = 0xFFFF
        const val MIDI_CC_ALL_SOUND_OFF = 120
        const val MIDI_CC_ALL_NOTES_OFF = 123
        // 32-bit words in a 128-bit UMP packet (SysEx8 parameter changes)
        const val UMP128_WORD_COUNT = 4

        init {
            try {
                System.loadLibrary("aaphostnative")
                Log.d(TAG, "Loaded aaphostnative library")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load aaphostnative library", e)
            }
        }

        @JvmStatic
        private external fun nativeConfigure(framesPerCallback: Int, numSlots: Int)

        @JvmStatic
        private external fun nativeShutdown()

        @JvmStatic
        private external fun nativeStart(): Boolean

        @JvmStatic
        private external fun nativePause()

        @JvmStatic
        private external fun nativeOpenStream(): Boolean

        @JvmStatic
        private external fun nativeCloseStream()

        @JvmStatic
        private external fun nativeIsStreaming(): Boolean

        @JvmStatic
        private external fun nativeSetFramesPerCallback(framesPerCallback: Int)

        @JvmStatic
        private external fun nativeGetSampleRate(): Int

        @JvmStatic
        private external fun nativeGetBurstFrames(): Int

        @JvmStatic
        private external fun nativeIsLowLatency(): Boolean

        @JvmStatic
        private external fun nativeIsExclusive(): Boolean

        @JvmStatic
        private external fun nativeIsMMapUsed(): Boolean

        @JvmStatic
        private external fun nativeSetSlotPlugin(slotIndex: Int, nativeClient: Long, instanceId: Int, sampleRate: Int)

        @JvmStatic
        private external fun nativeSetSlotBypassed(slotIndex: Int, bypassed: Boolean)

        @JvmStatic
        private external fun nativeSetSlotGain(slotIndex: Int, gain: Float)

        @JvmStatic
        private external fun nativeSetSlotMix(slotIndex: Int, mix: Float)

        @JvmStatic
        private external fun nativeSendUmp(slotIndex: Int, data: ByteArray, length: Int)

        @JvmStatic
        private external fun nativeGetCpuLoad(): Float

        @JvmStatic
        private external fun nativeGetSlotCpuLoad(slotIndex: Int): Float

        @JvmStatic
        private external fun nativeGetSlotInvalidBlocks(slotIndex: Int): Int

        @JvmStatic
        private external fun nativeGetAllSlotLevels(outLevels: FloatArray)
    }

    /**
     * Native sample rate of the output device, 0 if no stream could be opened yet. It can change
     * after a device change (e.g. Bluetooth headphones): plugins must then be re-prepared at it.
     */
    val sampleRate: Int
        get() = nativeGetSampleRate()

    val actualBurstSize: Int
        get() = nativeGetBurstFrames().coerceAtLeast(0)

    /** Mode of the last opened output stream. It can change when the stream is reopened after a device change. */
    val streamMode: OutputStreamMode
        get() = OutputStreamMode(nativeIsLowLatency(), nativeIsExclusive(), nativeIsMMapUsed())

    init {
        nativeConfigure(framesPerCallback, numSlots)
    }

    /** Records the MIDI sent to the slots and plays it back. Its transport runs while the engine does. */
    val sequencer = MidiSequencer()

    /** Frames each plugin renders per block, independent of the device burst size. */
    fun setFramesPerCallback(frames: Int) {
        nativeSetFramesPerCallback(frames)
    }

    private val slotInstances = Array<NativeRemotePluginInstance?>(numSlots) { null }

    /** Whether audio is running. Turns false on its own if the stream cannot be restarted after a device error. */
    val isProcessing: Boolean
        get() = nativeIsStreaming()

    val totalCpuLoad: Float
        get() = nativeGetCpuLoad()

    fun getSlotCpuLoad(slotIndex: Int): Float {
        if (slotIndex in 0 until numSlots) {
            return nativeGetSlotCpuLoad(slotIndex)
        }

        return 0f
    }

    /** Output blocks the engine dropped because they held NaN or infinite samples, since the slot's plugin was set. */
    fun getSlotInvalidBlocks(slotIndex: Int): Int {
        if (slotIndex in 0 until numSlots) {
            return nativeGetSlotInvalidBlocks(slotIndex)
        }

        return 0
    }

    fun getAllSlotLevels(outLevels: FloatArray) {
        if (outLevels.isNotEmpty()) {
            nativeGetAllSlotLevels(outLevels)
        }
    }

    /**
     * Puts [instance] in the slot; the engine activates it if it is playing and resets the slot's bypass.
     * [sampleRate] is the rate [instance] was prepared at: the slot stays silent while it differs from
     * the engine's [sampleRate]. Returns once the audio thread has let go of the previous instance.
     */
    fun setSlotPlugin(slotIndex: Int, instance: NativeRemotePluginInstance, sampleRate: Int) {
        if (slotIndex in 0 until numSlots) {
            slotInstances[slotIndex] = instance
            nativeSetSlotPlugin(slotIndex, instance.client, instance.instanceId, sampleRate)
        }
    }

    /** Empties the slot. Returns once the audio thread has let go of its instance, so it can be destroyed. */
    fun clearSlot(slotIndex: Int) {
        if (slotIndex in 0 until numSlots) {
            slotInstances[slotIndex] = null
            nativeSetSlotPlugin(slotIndex, 0L, -1, 0)
        }
    }

    fun setSlotBypassed(slotIndex: Int, bypassed: Boolean) {
        if (slotIndex in 0 until numSlots) {
            nativeSetSlotBypassed(slotIndex, bypassed)
        }
    }

    /** Linear gain the host applies to the slot's output. [setSlotPlugin] leaves it unchanged. */
    fun setSlotGain(slotIndex: Int, gain: Float) {
        if (slotIndex in 0 until numSlots) {
            nativeSetSlotGain(slotIndex, gain)
        }
    }

    /** Dry / wet balance the host applies to the slot (0: input only, 1: plugin output only). [setSlotPlugin] leaves it unchanged. */
    fun setSlotMix(slotIndex: Int, mix: Float) {
        if (slotIndex in 0 until numSlots) {
            nativeSetSlotMix(slotIndex, mix)
        }
    }

    fun start() {
        if (isProcessing) {
            return
        }

        if (nativeStart()) {
            Log.d(TAG, "Rack engine started")
        } else {
            Log.e(TAG, "Failed to start rack engine")
        }
    }

    /** Opens the output stream without starting it, so [sampleRate] is current. Returns false if the device could not be opened. */
    fun openStream(): Boolean {
        return nativeOpenStream()
    }

    /**
     * Stops audio and releases the audio device, keeping the slots. [start] opens a new stream.
     * Restarting a fresh stream avoids resuming one that AAudio put in standby, which can crash in AAudio.
     */
    fun closeStream() {
        if (isProcessing) {
            allNotesOff()
        }

        nativeCloseStream()
        Log.d(TAG, "Output stream closed")
    }

    fun allNotesOff() {
        for (slotIndex in 0 until numSlots) {

            if (slotInstances[slotIndex] != null) {
                sendControlChange(slotIndex, MIDI_CC_ALL_SOUND_OFF, 0.0f)
                sendControlChange(slotIndex, MIDI_CC_ALL_NOTES_OFF, 0.0f)
            }

        }
    }

    fun pause() {
        if (!isProcessing) {
            return
        }

        allNotesOff()
        nativePause()
        Log.d(TAG, "Rack engine paused")
    }

    fun sendNoteOn(note: Int, velocity: Float = 1.0f) {
        val velocity16 = (velocity.coerceIn(0.0f, 1.0f) * MIDI2_16BIT_MAX).toInt()
        val ump = Ump(UmpFactory.midi2NoteOn(0, 0, note, 0, velocity16, 0))
        sendUmpToSlot(0, ump.toPlatformNativeBytes())
    }

    fun sendNoteOff(note: Int, velocity: Float = 0.0f) {
        val velocity16 = (velocity.coerceIn(0.0f, 1.0f) * MIDI2_16BIT_MAX).toInt()
        val ump = Ump(UmpFactory.midi2NoteOff(0, 0, note, 0, velocity16, 0))
        sendUmpToSlot(0, ump.toPlatformNativeBytes())
    }

    fun sendPitchBend(slotIndex: Int = 0, note: Int = -1, value: Float) {
        // UmpFactory.midi2PitchBend expects signed 32-bit: -0x80000000L (-2147483648) to +0x7FFFFFFFL (+2147483647) with 0L at center
        val signed32 = (value.coerceIn(-1.0f, 1.0f).toDouble() * MIDI2_SIGNED_32BIT_MAX.toDouble()).toLong().coerceIn(-MIDI2_SIGNED_32BIT_MAX, MIDI2_SIGNED_32BIT_MAX - 1L)

        val ump = if (note < 0) {
            UmpFactory.midi2PitchBend(0, 0, signed32)
        } else {
            UmpFactory.midi2PerNotePitchBend(0, 0, note, signed32)
        }

        sendUmpToSlot(slotIndex, Ump(ump).toPlatformNativeBytes())
    }

    fun sendPressure(slotIndex: Int = 0, note: Int = -1, value: Float) {
        val pressure32 = (value.coerceIn(0.0f, 1.0f).toDouble() * MIDI2_32BIT_MAX.toDouble()).toLong().coerceIn(0L, MIDI2_32BIT_MAX)

        val ump = if (note < 0) {
            UmpFactory.midi2CAf(0, 0, pressure32)
        } else {
            UmpFactory.midi2PAf(0, 0, note, pressure32)
        }

        sendUmpToSlot(slotIndex, Ump(ump).toPlatformNativeBytes())
    }

    fun sendControlChange(slotIndex: Int = 0, controller: Int, value: Float) {
        val data32 = (value.coerceIn(0.0f, 1.0f).toDouble() * MIDI2_32BIT_MAX.toDouble()).toLong().coerceIn(0L, MIDI2_32BIT_MAX)
        val ump = Ump(UmpFactory.midi2CC(0, 0, controller, data32))
        sendUmpToSlot(slotIndex, ump.toPlatformNativeBytes())
    }

    fun setParameterValue(slotIndex: Int, parameter: ParameterInformation, value: Double) {
        val ints = UmpHelper.aapUmpSysex8ParameterPlain(parameter.id.toUInt(), parameter.minimumValue, parameter.maximumValue, value)
        val umps = ints.filterIndexed { i, _ ->
            i % UMP128_WORD_COUNT == 0
        }.flatMapIndexed { i, v ->
            val start = i * UMP128_WORD_COUNT
            Ump(v, ints[start + 1], ints[start + 2], ints[start + 3]).toPlatformNativeBytes().asList()
        }
        sendUmpToSlot(slotIndex, umps.toByteArray())
    }

    private fun sendUmpToSlot(slotIndex: Int, bytes: ByteArray) {
        if (slotIndex in 0 until numSlots) {
            nativeSendUmp(slotIndex, bytes, bytes.size)
        }
    }

    override fun close() {
        try {
            if (isProcessing) {
                pause()
            }

            nativeShutdown()
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing rack engine", e)
        }
    }
}
