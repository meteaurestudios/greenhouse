package org.androidaudioplugin.host.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.PortInformation
import org.androidaudioplugin.hosting.InstanceState
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import org.androidaudioplugin.hosting.UmpHelper
import dev.atsushieno.ktmidi.Ump
import dev.atsushieno.ktmidi.UmpFactory
import dev.atsushieno.ktmidi.toPlatformNativeBytes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class AapAudioPlayer private constructor(
    val sampleRate: Int,
    val framesPerCallback: Int,
    val channelCount: Int = 2
) : AutoCloseable {

    companion object {
        private const val TAG = "AapAudioPlayer"
        const val DEFAULT_SAMPLE_AUDIO = "androidaudioplugin_manager_sample_audio.ogg"

        fun create(
            sampleRate: Int,
            framesPerCallback: Int,
            channelCount: Int = 2
        ): AapAudioPlayer {
            return AapAudioPlayer(sampleRate, framesPerCallback, channelCount)
        }
    }

    private val slotInstances = Array<NativeRemotePluginInstance?>(3) { null }
    private val slotBypassed = BooleanArray(3) { false }

    var isProcessing: Boolean = false
        private set

    @Volatile
    private var isPlayingSampleAudio: Boolean = false

    private var audioTrack: AudioTrack? = null
    private var renderThread: Thread? = null

    // Mono channel buffer size in bytes (framesPerCallback * 4 bytes/float)
    private val channelSizeBytes = framesPerCallback * 4

    private val monoBufferL: ByteBuffer = ByteBuffer.allocateDirect(channelSizeBytes).apply {
        order(ByteOrder.nativeOrder())
    }
    private val monoBufferR: ByteBuffer = ByteBuffer.allocateDirect(channelSizeBytes).apply {
        order(ByteOrder.nativeOrder())
    }

    private val floatBufferL = monoBufferL.asFloatBuffer()
    private val floatBufferR = monoBufferR.asFloatBuffer()

    private val pcmFloatArray = FloatArray(framesPerCallback * channelCount)

    // Sample audio waveform cache (sine/test tone generator for sample testing)
    private var sampleTonePhase = 0.0

    fun setSlotPlugin(slotIndex: Int, instance: NativeRemotePluginInstance?) {
        if (slotIndex in 0..2) {
            slotInstances[slotIndex] = instance
            if (instance != null && isProcessing && instance.state == InstanceState.INACTIVE) {
                try {
                    instance.activate()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to activate plugin in slot $slotIndex", e)
                }
            }
        }
    }

    fun setSlotBypassed(slotIndex: Int, bypassed: Boolean) {
        if (slotIndex in 0..2) {
            slotBypassed[slotIndex] = bypassed
        }
    }

    private var sampleAudioData: FloatArray? = null
    @Volatile
    private var sampleAudioPosition: Int = 0

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

            if (trackIndex < 0 || format == null) return false
            extractor.selectTrack(trackIndex)

            val mime = format.getString(android.media.MediaFormat.KEY_MIME)!!
            val channels = if (format.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

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

            sampleAudioData = sampleList.toFloatArray()
            Log.d(TAG, "Sample audio loaded successfully (${sampleAudioData?.size} samples, channels=$channels)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading sample audio asset", e)
            false
        }
    }

    private val umpEventBuffer: ByteBuffer = ByteBuffer.allocateDirect(256).apply {
        order(ByteOrder.nativeOrder())
    }

    fun start() {
        if (isProcessing) return

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(framesPerCallback * channelCount * 4 * 4)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    audioTrack?.bufferSizeInFrames = framesPerCallback * 2
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to set bufferSizeInFrames", e)
                }
            }

            // Activate plugins
            for (inst in slotInstances) {
                if (inst != null && inst.state == InstanceState.INACTIVE) {
                    try {
                        inst.activate()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to activate plugin", e)
                    }
                }
            }

            audioTrack?.play()
            isProcessing = true

            renderThread = Thread({
                audioRenderLoop()
            }, "AAP-AudioRenderThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            Log.d(TAG, "Audio player thread started")
        } catch (e: Throwable) {
            Log.e(TAG, "Error starting AudioTrack player", e)
            isProcessing = false
        }
    }

    fun pause() {
        if (!isProcessing) return
        isProcessing = false

        try {
            renderThread?.join(500)
        } catch (e: Throwable) {
            Log.e(TAG, "Error waiting for render thread to finish", e)
        }
        renderThread = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        }
        audioTrack = null

        for (inst in slotInstances) {
            if (inst != null && inst.state == InstanceState.ACTIVE) {
                try {
                    inst.deactivate()
                } catch (e: Throwable) {
                    Log.e(TAG, "Error deactivating plugin", e)
                }
            }
        }
    }

    fun playSampleAudio() {
        val inst0 = slotInstances[0]
        val isInst0Active = inst0 != null && !slotBypassed[0] && inst0.state == InstanceState.ACTIVE
        if (isInst0Active) {
            // Do NOT trigger sample audio if instrument slot is loaded & active
            return
        }
        sampleAudioPosition = 0
        isPlayingSampleAudio = true
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
        val umps = ints.filterIndexed { i, _ -> i % 4 == 0 }.flatMapIndexed { i, v ->
            Ump(v, ints[i * 4 + 1], ints[i * 4 + 2], ints[i * 4 + 3]).toPlatformNativeBytes().asList()
        }
        sendUmpToSlot(slotIndex, umps.toByteArray())
    }

    fun setPresetIndex(slotIndex: Int, index: Int) {
        if (slotIndex in 0..2) {
            slotInstances[slotIndex]?.setCurrentPresetIndex(index)
        }
    }

    private fun sendUmpToSlot(slotIndex: Int, bytes: ByteArray) {
        if (slotIndex in 0..2) {
            val inst = slotInstances[slotIndex] ?: return
            if (inst.state == InstanceState.ACTIVE) {
                synchronized(umpEventBuffer) {
                    umpEventBuffer.clear()
                    umpEventBuffer.put(bytes)
                    umpEventBuffer.flip()
                    inst.addEventUmpInput(umpEventBuffer, bytes.size)
                }
            }
        }
    }

    private fun audioRenderLoop() {
        val numSamplesTotal = framesPerCallback * channelCount

        while (isProcessing) {
            // ----------------------------------------------------
            // STEP 1: SLOT 0 (INSTRUMENT PLUGIN / SOURCE GENERATOR)
            // ----------------------------------------------------
            val inst0 = slotInstances[0]
            val isInst0Active = inst0 != null && !slotBypassed[0] && inst0.state == InstanceState.ACTIVE

            if (isInst0Active) {
                inst0!!.process(framesPerCallback, 0)
                val outPorts = getAudioPorts(inst0, isInput = false)
                if (outPorts.isNotEmpty()) {
                    monoBufferL.clear()
                    inst0.getPortBuffer(outPorts[0], monoBufferL, channelSizeBytes)
                    if (outPorts.size > 1) {
                        monoBufferR.clear()
                        inst0.getPortBuffer(outPorts[1], monoBufferR, channelSizeBytes)
                    }

                    floatBufferL.rewind()
                    floatBufferR.rewind()
                    for (i in 0 until framesPerCallback) {
                        val l = floatBufferL.get()
                        val r = if (outPorts.size > 1) floatBufferR.get() else l
                        pcmFloatArray[i * 2 + 0] = l
                        pcmFloatArray[i * 2 + 1] = r
                    }
                } else {
                    pcmFloatArray.fill(0.0f)
                }
            } else if (isPlayingSampleAudio && sampleAudioData != null) {
                val sampleData = sampleAudioData!!
                val numSamples = sampleData.size
                for (i in 0 until framesPerCallback) {
                    if (sampleAudioPosition + 1 < numSamples) {
                        pcmFloatArray[i * 2 + 0] = sampleData[sampleAudioPosition++]
                        pcmFloatArray[i * 2 + 1] = sampleData[sampleAudioPosition++]
                    } else {
                        pcmFloatArray[i * 2 + 0] = 0.0f
                        pcmFloatArray[i * 2 + 1] = 0.0f
                        isPlayingSampleAudio = false
                        sampleAudioPosition = 0
                        break
                    }
                }
            } else {
                pcmFloatArray.fill(0.0f)
            }

            // ----------------------------------------------------
            // STEP 2 & 3: EFFECT PLUGINS (SLOT 1 & SLOT 2)
            // ----------------------------------------------------
            for (slotIdx in 1..2) {
                val inst = slotInstances[slotIdx]
                if (inst != null && !slotBypassed[slotIdx] && inst.state == InstanceState.ACTIVE) {
                    val inPorts = getAudioPorts(inst, isInput = true)
                    val outPorts = getAudioPorts(inst, isInput = false)

                    if (inPorts.isNotEmpty() && outPorts.isNotEmpty()) {
                        // De-interleave pcmFloatArray to mono channel buffers
                        floatBufferL.clear()
                        floatBufferR.clear()
                        for (i in 0 until framesPerCallback) {
                            floatBufferL.put(pcmFloatArray[i * 2 + 0])
                            floatBufferR.put(pcmFloatArray[i * 2 + 1])
                        }
                        floatBufferL.rewind()
                        floatBufferR.rewind()

                        monoBufferL.rewind()
                        inst.setPortBuffer(inPorts[0], monoBufferL, channelSizeBytes)
                        if (inPorts.size > 1) {
                            monoBufferR.rewind()
                            inst.setPortBuffer(inPorts[1], monoBufferR, channelSizeBytes)
                        }

                        // Execute plugin processing
                        inst.process(framesPerCallback, 0)

                        // Read output port buffers
                        monoBufferL.clear()
                        inst.getPortBuffer(outPorts[0], monoBufferL, channelSizeBytes)
                        if (outPorts.size > 1) {
                            monoBufferR.clear()
                            inst.getPortBuffer(outPorts[1], monoBufferR, channelSizeBytes)
                        }

                        // Interleave output mono buffers back to pcmFloatArray
                        floatBufferL.rewind()
                        floatBufferR.rewind()
                        for (i in 0 until framesPerCallback) {
                            val l = floatBufferL.get()
                            val r = if (outPorts.size > 1) floatBufferR.get() else l
                            pcmFloatArray[i * 2 + 0] = l
                            pcmFloatArray[i * 2 + 1] = r
                        }
                    }
                }
            }

            // ----------------------------------------------------
            // STEP 4: WRITE STEREO AUDIO TO AUDIOTRACK
            // ----------------------------------------------------
            audioTrack?.write(pcmFloatArray, 0, numSamplesTotal, AudioTrack.WRITE_BLOCKING)
        }
    }

    private fun getAudioPorts(plugin: NativeRemotePluginInstance, isInput: Boolean): List<Int> {
        val targetDir = if (isInput) PortInformation.PORT_DIRECTION_INPUT else PortInformation.PORT_DIRECTION_OUTPUT
        val list = mutableListOf<Int>()
        val count = plugin.getPortCount()
        for (i in 0 until count) {
            val port = plugin.getPort(i)
            if (port.direction == targetDir && port.content == PortInformation.PORT_CONTENT_TYPE_AUDIO) {
                list.add(i)
            }
        }
        return list
    }

    override fun close() {
        try {
            if (isProcessing) {
                pause()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing AapAudioPlayer", e)
        }
    }
}


