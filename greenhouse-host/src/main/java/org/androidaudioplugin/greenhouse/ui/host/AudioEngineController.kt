package org.androidaudioplugin.greenhouse.ui.host

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.androidaudioplugin.greenhouse.core.AapAudioPlayer
import org.androidaudioplugin.greenhouse.core.MAX_HOST_BUFFER_FRAMES
import java.util.Locale

/**
 * Owns the Oboe render engine: transport (start / pause), buffer sizing, and the
 * pause-on-background / resume-on-foreground behaviour.
 */
class AudioEngineController(
    context: Context,
    numSlots: Int,
    private val postStatus: (String) -> Unit
) : AutoCloseable {
    companion object {
        private const val TAG = "AudioEngineController"
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_BURST_SIZE = 128
        const val DEFAULT_BURST_MULTIPLIER = 4
        const val DEFAULT_FRAMES_PER_CALLBACK = DEFAULT_BURST_SIZE * DEFAULT_BURST_MULTIPLIER
        const val MIN_FRAMES_PER_CALLBACK = 1
        const val MILLIS_PER_SECOND = 1000f
        val AVAILABLE_BURST_MULTIPLIERS = listOf(2, 4, 8, 16, 32)

        private fun queryOutputSampleRate(context: Context): Int {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: DEFAULT_SAMPLE_RATE
        }
    }

    val sampleRate: Int = queryOutputSampleRate(context)

    var framesPerCallback by mutableIntStateOf(DEFAULT_FRAMES_PER_CALLBACK)
        private set

    val player: AapAudioPlayer = AapAudioPlayer.create(sampleRate, framesPerCallback, numSlots = numSlots)

    var isProcessing by mutableStateOf(false)
        private set

    var wasPlayingBeforeBackground by mutableStateOf(false)
        private set

    var isBackgrounded by mutableStateOf(false)
        private set

    val actualBurstSize: Int
        get() {
            val nativeBurst = player.actualBurstSize

            if (nativeBurst > 0) {
                return nativeBurst
            }

            return DEFAULT_BURST_SIZE
        }

    val availableBurstMultipliers: List<Int>
        get() {
            val base = actualBurstSize
            return AVAILABLE_BURST_MULTIPLIERS.filter { (it * base) <= MAX_HOST_BUFFER_FRAMES }
        }

    fun setBufferFramesPerCallback(newFrames: Int) {
        val clampedFrames = newFrames.coerceIn(MIN_FRAMES_PER_CALLBACK, MAX_HOST_BUFFER_FRAMES)

        if (clampedFrames == framesPerCallback) {
            return
        }

        framesPerCallback = clampedFrames
        player.setFramesPerCallback(clampedFrames)
        val estimatedLatency = (clampedFrames.toFloat() / sampleRate.toFloat()) * MILLIS_PER_SECOND
        postStatus("FIFO render block set to $clampedFrames frames (${String.format(Locale.US, "%.2f", estimatedLatency)} ms)")
    }

    fun togglePlayback() {
        wasPlayingBeforeBackground = false

        if (isProcessing) {
            pause()
            postStatus("Audio engine paused.")
        } else {
            start(failureMessage = "Failed to start audio engine.")
        }
    }

    fun ensureRunning() {
        if (!isProcessing) {
            togglePlayback()
        }
    }

    /**
     * Like [ensureRunning], but never starts rendering while the app is in the background:
     * it arms the foreground auto-resume instead.
     */
    fun requestRunning() {
        if (isBackgrounded) {
            wasPlayingBeforeBackground = true
            return
        }

        ensureRunning()
    }

    /** Stops rendering without touching the status line; used around rack teardown / restore. */
    fun pause() {
        player.pause()
        isProcessing = false
    }

    fun onAppBackground() {
        isBackgrounded = true

        if (isProcessing) {
            wasPlayingBeforeBackground = true
            Log.i(TAG, "App backgrounded: pausing active audio engine")
            pause()
            postStatus("Audio engine paused (app in background).")
        } else {
            wasPlayingBeforeBackground = false
            Log.i(TAG, "App backgrounded: audio engine was already paused")
        }
    }

    fun onAppForeground() {
        isBackgrounded = false

        if (!wasPlayingBeforeBackground) {
            Log.i(TAG, "App foregrounded: engine remains paused (not playing prior to background)")
            return
        }

        wasPlayingBeforeBackground = false

        if (isProcessing) {
            return
        }

        Log.i(TAG, "App foregrounded: resuming audio engine")

        if (!start(failureMessage = "Failed to resume audio engine.")) {
            Log.e(TAG, "Failed to resume audio engine after returning to foreground")
        }
    }

    private fun start(failureMessage: String): Boolean {
        player.start()
        isProcessing = player.isProcessing

        if (!isProcessing) {
            postStatus(failureMessage)
            return false
        }

        adoptHardwareBurstSize()
        postStatus("Audio engine ACTIVE (FIFO decoupled render running).")
        return true
    }

    /** Until the user picks a buffer size, keep the default multiplier but scale it to the real hardware burst. */
    private fun adoptHardwareBurstSize() {
        val burst = player.actualBurstSize

        if (burst > 0 && framesPerCallback == DEFAULT_FRAMES_PER_CALLBACK) {
            framesPerCallback = burst * DEFAULT_BURST_MULTIPLIER
            player.setFramesPerCallback(framesPerCallback)
        }
    }

    override fun close() {
        player.close()
    }
}
