package org.androidaudioplugin.host.core

import android.content.Context
import android.util.Log
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.hosting.AudioPluginClientBase
import org.androidaudioplugin.hosting.InstanceState
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import org.androidaudioplugin.hosting.UmpHelper
import dev.atsushieno.ktmidi.Ump
import dev.atsushieno.ktmidi.UmpFactory
import dev.atsushieno.ktmidi.toPlatformNativeBytes
import java.nio.ByteOrder

class AapAudioPlayer private constructor(
    val sampleRate: Int,
    initialFramesPerCallback: Int,
    val channelCount: Int = 2,
    val numSlots: Int = DEFAULT_NUM_RACK_SLOTS
) : AutoCloseable {

    companion object {
        private const val TAG = "AapAudioPlayer"
        const val DEFAULT_NUM_RACK_SLOTS = 3

        init {
            try {
                System.loadLibrary("aaphostnative")
                Log.d(TAG, "Loaded aaphostnative library")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load aaphostnative library", e)
            }
        }

        fun create(
            sampleRate: Int,
            framesPerCallback: Int,
            channelCount: Int = 2,
            numSlots: Int = DEFAULT_NUM_RACK_SLOTS
        ): AapAudioPlayer {
            return AapAudioPlayer(sampleRate, framesPerCallback, channelCount, numSlots)
        }

        @JvmStatic
        private external fun nativeCreate(sampleRate: Int, framesPerCallback: Int, channelCount: Int, numSlots: Int): Long

        @JvmStatic
        private external fun nativeDestroy(engineHandle: Long)

        @JvmStatic
        private external fun nativeStart(engineHandle: Long): Boolean

        @JvmStatic
        private external fun nativePause(engineHandle: Long)

        @JvmStatic
        private external fun nativeSetFramesPerCallback(engineHandle: Long, framesPerCallback: Int)

        @JvmStatic
        private external fun nativeSetSlotPlugin(engineHandle: Long, slotIndex: Int, nativeClient: Long, instanceId: Int)

        @JvmStatic
        private external fun nativeSetSlotBypassed(engineHandle: Long, slotIndex: Int, bypassed: Boolean)

        @JvmStatic
        private external fun nativeSendUmp(engineHandle: Long, slotIndex: Int, data: ByteArray, length: Int)

        @JvmStatic
        private external fun nativeGetCpuLoad(engineHandle: Long): Float

        @JvmStatic
        private external fun nativeGetSlotCpuLoad(engineHandle: Long, slotIndex: Int): Float
    }

    private var nativeEngineHandle: Long = 0L

    var framesPerCallback: Int = initialFramesPerCallback
        private set

    init {
        nativeEngineHandle = nativeCreate(sampleRate, framesPerCallback, channelCount, numSlots)
    }

    fun setFramesPerCallback(frames: Int) {
        if (frames > 0 && frames != framesPerCallback) {
            framesPerCallback = frames

            if (nativeEngineHandle != 0L) {
                nativeSetFramesPerCallback(nativeEngineHandle, frames)
            }
        }
    }

    private val slotInstances = Array<NativeRemotePluginInstance?>(numSlots) { null }
    private val slotClients = Array<AudioPluginClientBase?>(numSlots) { null }
    private val slotBypassed = BooleanArray(numSlots) { false }

    var isProcessing: Boolean = false
        private set

    val totalCpuLoad: Float
        get() {
            if (nativeEngineHandle != 0L) {
                return nativeGetCpuLoad(nativeEngineHandle)
            }

            return 0f
        }

    fun getSlotCpuLoad(slotIndex: Int): Float {
        if (nativeEngineHandle != 0L && slotIndex in 0 until numSlots) {
            return nativeGetSlotCpuLoad(nativeEngineHandle, slotIndex)
        }

        return 0f
    }

    fun setSlotPlugin(
        slotIndex: Int,
        instance: NativeRemotePluginInstance?,
        client: AudioPluginClientBase? = null
    ) {
        if (slotIndex in 0 until numSlots) {
            slotInstances[slotIndex] = instance
            slotClients[slotIndex] = client
            slotBypassed[slotIndex] = false

            if (instance != null && isProcessing && instance.state == InstanceState.INACTIVE) {
                try {
                    instance.activate()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to activate plugin in slot $slotIndex", e)
                }
            }

            if (nativeEngineHandle != 0L) {
                nativeSetSlotBypassed(nativeEngineHandle, slotIndex, false)

                if (instance != null) {
                    nativeSetSlotPlugin(nativeEngineHandle, slotIndex, instance.client, instance.instanceId)
                } else {
                    nativeSetSlotPlugin(nativeEngineHandle, slotIndex, 0L, -1)
                }
            }
        }
    }

    fun setSlotBypassed(slotIndex: Int, bypassed: Boolean) {
        if (slotIndex in 0 until numSlots) {
            slotBypassed[slotIndex] = bypassed

            if (nativeEngineHandle != 0L) {
                nativeSetSlotBypassed(nativeEngineHandle, slotIndex, bypassed)
            }
        }
    }

    fun start() {
        if (isProcessing) {
            return
        }

        if (nativeEngineHandle != 0L) {
            val success = nativeStart(nativeEngineHandle)
            isProcessing = success

            if (success) {
                Log.d(TAG, "Native Oboe audio player started")
            } else {
                Log.e(TAG, "Failed to start native audio player")
            }
        }
    }

    fun pause() {
        if (!isProcessing) {
            return
        }

        isProcessing = false

        if (nativeEngineHandle != 0L) {
            nativePause(nativeEngineHandle)
            Log.d(TAG, "Native audio player paused")
        }
    }

    fun sendNoteOn(note: Int, velocity: Float = 1.0f) {
        val velocity16 = (velocity.coerceIn(0.0f, 1.0f) * 0xFFFF).toInt()
        val ump = Ump(UmpFactory.midi2NoteOn(0, 0, note, 0, velocity16, 0))
        sendUmpToSlot(0, ump.toPlatformNativeBytes())
    }

    fun sendNoteOff(note: Int, velocity: Float = 0.0f) {
        val velocity16 = (velocity.coerceIn(0.0f, 1.0f) * 0xFFFF).toInt()
        val ump = Ump(UmpFactory.midi2NoteOff(0, 0, note, 0, velocity16, 0))
        sendUmpToSlot(0, ump.toPlatformNativeBytes())
    }

    fun sendPitchBend(slotIndex: Int = 0, note: Int = -1, value: Float) {
        val ump = if (note < 0) {
            UmpFactory.midi2PitchBend(0, 0, (0x1_0000_0000 * value).toLong())
        } else {
            UmpFactory.midi2PerNotePitchBend(0, 0, note, (0x1_0000_0000 * value).toLong())
        }
        sendUmpToSlot(slotIndex, Ump(ump).toPlatformNativeBytes())
    }

    fun sendPressure(slotIndex: Int = 0, note: Int = -1, value: Float) {
        val ump = Ump(UmpFactory.midi2PAf(0, 0, note, (0x1_0000_0000 * value).toLong()))
        sendUmpToSlot(slotIndex, ump.toPlatformNativeBytes())
    }

    fun setParameterValue(slotIndex: Int, parameter: ParameterInformation, value: Double) {
        val ints = UmpHelper.aapUmpSysex8ParameterPlain(parameter.id.toUInt(), parameter.minimumValue, parameter.maximumValue, value)
        val umps = ints.filterIndexed { i, _ ->
            i % 4 == 0
        }.flatMapIndexed { i, v ->
            Ump(v, ints[i * 4 + 1], ints[i * 4 + 2], ints[i * 4 + 3]).toPlatformNativeBytes().asList()
        }
        sendUmpToSlot(slotIndex, umps.toByteArray())
    }

    fun setPresetIndex(slotIndex: Int, index: Int) {
        if (slotIndex in 0 until numSlots) {
            slotInstances[slotIndex]?.setCurrentPresetIndex(index)
        }
    }

    private fun sendUmpToSlot(slotIndex: Int, bytes: ByteArray) {
        if (nativeEngineHandle != 0L && slotIndex in 0 until numSlots) {
            nativeSendUmp(nativeEngineHandle, slotIndex, bytes, bytes.size)
        }
    }

    override fun close() {
        try {
            if (isProcessing) {
                pause()
            }

            if (nativeEngineHandle != 0L) {
                nativeDestroy(nativeEngineHandle)
                nativeEngineHandle = 0L
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing AapAudioPlayer", e)
        }
    }
}
