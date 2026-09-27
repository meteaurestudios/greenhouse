package org.androidaudioplugin.greenhouse.ui.host

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.androidaudioplugin.greenhouse.core.OutputStreamMode
import org.androidaudioplugin.greenhouse.core.RackEngine
import org.androidaudioplugin.greenhouse.core.MAX_HOST_BUFFER_FRAMES
import java.util.Locale

/**
 * Owns the Oboe render engine: transport (start / pause), buffer sizing, and the
 * pause-on-background / resume-on-foreground behaviour.
 */
class AudioEngineController(
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
    }

    var framesPerCallback by mutableIntStateOf(DEFAULT_FRAMES_PER_CALLBACK)
        private set

    val engine = RackEngine(framesPerCallback, numSlots)

    /** Rate plugins are prepared at: the output device's native rate, kept current by [syncWithEngine]. */
    var sampleRate by mutableIntStateOf(engine.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE)
        private set

    var isProcessing by mutableStateOf(false)
        private set

    var wasPlayingBeforeBackground by mutableStateOf(false)
        private set

    var isBackgrounded by mutableStateOf(false)
        private set

    val actualBurstSize: Int
        get() {
            val nativeBurst = engine.actualBurstSize

            if (nativeBurst > 0) {
                return nativeBurst
            }

            return DEFAULT_BURST_SIZE
        }

    val streamMode: OutputStreamMode
        get() = engine.streamMode

    val availableBurstMultipliers: List<Int>
        get() {
            val base = actualBurstSize
            return AVAILABLE_BURST_MULTIPLIERS.filter { (it * base) <= MAX_HOST_BUFFER_FRAMES }
        }

    /**
     * Picks up what the engine changed on its own after a device error: the new device's sample rate,
     * or audio having stopped because the stream could not be restarted.
     */
    fun syncWithEngine() {
        val engineRate = engine.sampleRate

        if (engineRate > 0 && engineRate != sampleRate) {
            Log.i(TAG, "Output sample rate changed: $sampleRate Hz -> $engineRate Hz")
            sampleRate = engineRate
        }

        if (isProcessing && !engine.isProcessing) {
            Log.w(TAG, "Audio engine stopped: the output stream could not be restarted")
            isProcessing = false
            postStatus("Audio engine stopped: the output device could not be restarted.")
        }
    }

    fun setBufferFramesPerCallback(newFrames: Int) {
        val clampedFrames = newFrames.coerceIn(MIN_FRAMES_PER_CALLBACK, MAX_HOST_BUFFER_FRAMES)

        if (clampedFrames == framesPerCallback) {
            return
        }

        framesPerCallback = clampedFrames
        engine.setFramesPerCallback(clampedFrames)
        val estimatedLatency = (clampedFrames.toFloat() / sampleRate.toFloat()) * MILLIS_PER_SECOND
        postStatus("Render block set to $clampedFrames frames (${String.format(Locale.US, "%.2f", estimatedLatency)} ms)")
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
        engine.pause()
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
        engine.start()
        isProcessing = engine.isProcessing

        if (!isProcessing) {
            postStatus(failureMessage)
            return false
        }

        adoptHardwareBurstSize()
        postStatus("Audio engine ACTIVE.")
        return true
    }

    /** Until the user picks a buffer size, keep the default multiplier but scale it to the real hardware burst. */
    private fun adoptHardwareBurstSize() {
        val burst = engine.actualBurstSize

        if (burst > 0 && framesPerCallback == DEFAULT_FRAMES_PER_CALLBACK) {
            framesPerCallback = burst * DEFAULT_BURST_MULTIPLIER
            engine.setFramesPerCallback(framesPerCallback)
        }
    }

    override fun close() {
        engine.close()
    }
}
