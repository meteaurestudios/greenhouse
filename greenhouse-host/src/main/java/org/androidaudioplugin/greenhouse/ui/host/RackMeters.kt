package org.androidaudioplugin.greenhouse.ui.host

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.androidaudioplugin.greenhouse.core.RackEngine
import org.androidaudioplugin.greenhouse.ui.SlotLevel

/** Per-slot output levels, CPU load and invalid output, polled from the native engine. */
class RackMeters(private val numSlots: Int) {
    companion object {
        const val STEREO_CHANNELS = 2
        const val PERCENT_SCALE = 100f
        const val MAX_PERCENT = 100f
        /** How long a slot shows invalid output after the engine last dropped one of its blocks. */
        const val INVALID_OUTPUT_DISPLAY_MS = 2000L
    }

    val slotLevels = mutableStateListOf<SlotLevel>().apply {
        repeat(numSlots) {
            add(SlotLevel())
        }
    }

    val slotCpuLoads = mutableStateListOf<Float>().apply {
        repeat(numSlots) {
            add(0f)
        }
    }

    /** Whether the slot's plugin produced NaN or infinite samples (dropped by the engine) in the last [INVALID_OUTPUT_DISPLAY_MS]. */
    val slotHasInvalidOutput = mutableStateListOf<Boolean>().apply {
        repeat(numSlots) {
            add(false)
        }
    }

    var totalCpuLoad by mutableFloatStateOf(0f)
        private set

    private val rawLevels = FloatArray(numSlots * STEREO_CHANNELS)

    // Polling thread only
    private val lastInvalidBlocks = IntArray(numSlots)
    private val lastInvalidOutputMs = LongArray(numSlots) { -INVALID_OUTPUT_DISPLAY_MS }

    /** Reads the engine on the calling thread and publishes the results on Main. */
    suspend fun poll(engine: RackEngine, isProcessing: Boolean, updateCpu: Boolean) {
        if (!isProcessing) {
            withContext(Dispatchers.Main) {
                reset()
            }

            return
        }

        engine.getAllSlotLevels(rawLevels)

        var totalPercent = 0f
        val slotLoads = if (updateCpu) {
            totalPercent = toPercent(engine.totalCpuLoad)
            FloatArray(numSlots) { i -> toPercent(engine.getSlotCpuLoad(i)) }
        } else {
            null
        }
        val invalidOutput = if (updateCpu) {
            val now = SystemClock.uptimeMillis()

            BooleanArray(numSlots) { i ->
                val count = engine.getSlotInvalidBlocks(i)

                if (count > lastInvalidBlocks[i]) {
                    lastInvalidOutputMs[i] = now
                }

                // The count restarts from 0 when a new plugin is set in the slot
                lastInvalidBlocks[i] = count
                now - lastInvalidOutputMs[i] < INVALID_OUTPUT_DISPLAY_MS
            }
        } else {
            null
        }

        withContext(Dispatchers.Main) {
            for (i in 0 until numSlots) {
                slotLevels[i] = SlotLevel(rawLevels[i * STEREO_CHANNELS], rawLevels[i * STEREO_CHANNELS + 1])
            }

            if (slotLoads != null) {
                totalCpuLoad = totalPercent

                for (i in 0 until numSlots) {
                    slotCpuLoads[i] = slotLoads[i]
                }
            }

            if (invalidOutput != null) {
                for (i in 0 until numSlots) {
                    slotHasInvalidOutput[i] = invalidOutput[i]
                }
            }
        }
    }

    private fun reset() {
        if (totalCpuLoad != 0f) {
            totalCpuLoad = 0f

            for (i in 0 until numSlots) {
                slotCpuLoads[i] = 0f
            }
        }

        for (i in 0 until numSlots) {
            if (slotLevels[i].left != 0f || slotLevels[i].right != 0f) {
                slotLevels[i] = SlotLevel()
            }
        }
    }

    private fun toPercent(load: Float): Float {
        return (load * PERCENT_SCALE).coerceIn(0f, MAX_PERCENT)
    }
}
