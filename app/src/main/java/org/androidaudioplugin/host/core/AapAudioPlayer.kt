package org.androidaudioplugin.host.core

import android.content.Context
import android.util.Log
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import org.androidaudioplugin.manager.PluginPlayer

class AapAudioPlayer private constructor(
    private val player: PluginPlayer,
    val sampleRate: Int,
    val framesPerCallback: Int,
    val channelCount: Int
) : AutoCloseable {

    companion object {
        private const val TAG = "AapAudioPlayer"
        const val DEFAULT_SAMPLE_AUDIO = "androidaudioplugin_manager_sample_audio.ogg"

        fun create(
            sampleRate: Int,
            framesPerCallback: Int,
            channelCount: Int = 2
        ): AapAudioPlayer {
            val nativePlayer = PluginPlayer.create(sampleRate, framesPerCallback, channelCount)
            return AapAudioPlayer(nativePlayer, sampleRate, framesPerCallback, channelCount)
        }
    }

    var isProcessing: Boolean = false
        private set

    fun attachPlugin(instance: NativeRemotePluginInstance) {
        player.setPlugin(instance)
    }

    fun loadSampleAudio(context: Context, filename: String = DEFAULT_SAMPLE_AUDIO): Boolean {
        return try {
            context.assets.open(filename).use { inputStream ->
                val bytes = inputStream.readBytes()
                player.loadAudioResource(bytes, filename)
                Log.d(TAG, "Successfully loaded audio resource ($filename, ${bytes.size} bytes)")
                true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load sample audio asset: $filename", e)
            false
        }
    }

    fun start() {
        if (!isProcessing) {
            player.startProcessing()
            isProcessing = true
        }
    }

    fun pause() {
        if (isProcessing) {
            player.pauseProcessing()
            isProcessing = false
        }
    }

    fun playSampleAudio() {
        player.playPreloadedAudio()
    }

    fun sendNoteOn(note: Int, velocity: Float = 1.0f) {
        val velocity16 = (velocity.coerceIn(0.0f, 1.0f) * 0xFFFF).toInt()
        player.setNoteState(note, velocity16, isNoteOn = true)
    }

    fun sendNoteOff(note: Int, velocity: Float = 0.0f) {
        val velocity16 = (velocity.coerceIn(0.0f, 1.0f) * 0xFFFF).toInt()
        player.setNoteState(note, velocity16, isNoteOn = false)
    }

    fun sendPitchBend(note: Int = -1, value: Float) {
        player.processPitchBend(note, value.coerceIn(-1.0f, 1.0f))
    }

    fun sendPressure(note: Int = -1, value: Float) {
        player.processPressure(note, value.coerceIn(0.0f, 1.0f))
    }

    fun setParameterValue(parameter: ParameterInformation, value: Double) {
        player.setParameterValue(parameter, value)
    }

    fun setPresetIndex(index: Int) {
        player.setPresetIndex(index)
    }

    override fun close() {
        try {
            if (isProcessing) {
                pause()
            }
            player.close()
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing PluginPlayer", e)
        }
    }
}
