package org.androidaudioplugin.host.core

import android.content.Context
import android.util.Log
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.hosting.InstanceState
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import org.androidaudioplugin.hosting.UmpHelper
import dev.atsushieno.ktmidi.Ump
import dev.atsushieno.ktmidi.UmpFactory
import dev.atsushieno.ktmidi.toPlatformNativeBytes
import java.nio.ByteOrder

class AapAudioPlayer private constructor(
    val sampleRate: Int,
    val framesPerCallback: Int,
    val channelCount: Int = 2,
    val numSlots: Int = DEFAULT_NUM_RACK_SLOTS
) : AutoCloseable {

    companion object {
        private const val TAG = "AapAudioPlayer"
        const val DEFAULT_SAMPLE_AUDIO = "androidaudioplugin_manager_sample_audio.ogg"
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
        private external fun nativeSetSlotPlugin(engineHandle: Long, slotIndex: Int, nativeClient: Long, instanceId: Int)

        @JvmStatic
        private external fun nativeSetSlotBypassed(engineHandle: Long, slotIndex: Int, bypassed: Boolean)

        @JvmStatic
        private external fun nativeSetSampleAudioData(engineHandle: Long, data: FloatArray)

        @JvmStatic
        private external fun nativePlaySampleAudio(engineHandle: Long)

        @JvmStatic
        private external fun nativeSendUmp(engineHandle: Long, slotIndex: Int, data: ByteArray, length: Int)

        @JvmStatic
        private external fun nativeGetCpuLoad(engineHandle: Long): Float

        @JvmStatic
        private external fun nativeGetSlotCpuLoad(engineHandle: Long, slotIndex: Int): Float
    }

    private var nativeEngineHandle: Long = 0L

    init {
        nativeEngineHandle = nativeCreate(sampleRate, framesPerCallback, channelCount, numSlots)
    }

    private val slotInstances = Array<NativeRemotePluginInstance?>(numSlots) { null }
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

    fun setSlotPlugin(slotIndex: Int, instance: NativeRemotePluginInstance?) {
        if (slotIndex in 0 until numSlots) {
            slotInstances[slotIndex] = instance

            if (instance != null && isProcessing && instance.state == InstanceState.INACTIVE) {
                try {
                    instance.activate()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to activate plugin in slot $slotIndex", e)
                }
            }

            if (nativeEngineHandle != 0L) {
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

    fun loadSampleAudio(context: Context, filename: String = DEFAULT_SAMPLE_AUDIO): Boolean {
        return try {
            val afd = context.assets.openFd(filename)
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()

            var trackIndex = -1
            var format: android.media.MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: ""

                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            if (trackIndex < 0 || format == null) {
                return false
            }

            extractor.selectTrack(trackIndex)

            val mime = format.getString(android.media.MediaFormat.KEY_MIME)!!
            val channels = if (format.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }

            val codec = android.media.MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleList = mutableListOf<Float>()
            val info = android.media.MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inputIndex = codec.dequeueInputBuffer(5000)

                    if (inputIndex >= 0) {
                        val inputBuf = codec.getInputBuffer(inputIndex)
                        val sampleSize = extractor.readSampleData(inputBuf!!, 0)

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 5000)

                if (outputIndex >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputIndex)

                    if (outputBuf != null && info.size > 0) {
                        outputBuf.position(info.offset)
                        outputBuf.limit(info.offset + info.size)
                        val shortBuf = outputBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val numShorts = shortBuf.remaining()
                        val shorts = ShortArray(numShorts)
                        shortBuf.get(shorts)

                        if (channels == 1) {
                            for (s in shorts) {
                                val floatVal = (s / 32768.0f).coerceIn(-1.0f, 1.0f)
                                sampleList.add(floatVal) // Left
                                sampleList.add(floatVal) // Right
                            }
                        } else {
                            for (s in shorts) {
                                val floatVal = (s / 32768.0f).coerceIn(-1.0f, 1.0f)
                                sampleList.add(floatVal)
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if ((info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val floatData = sampleList.toFloatArray()

            if (nativeEngineHandle != 0L) {
                nativeSetSampleAudioData(nativeEngineHandle, floatData)
            }

            Log.d(TAG, "Sample audio loaded successfully (${floatData.size} samples, channels=$channels)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading sample audio asset", e)
            false
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

    fun playSampleAudio() {
        if (nativeEngineHandle != 0L) {
            nativePlaySampleAudio(nativeEngineHandle)
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
