package org.androidaudioplugin.greenhouse.ui.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.androidaudioplugin.greenhouse.core.AapAudioPlayer
import org.androidaudioplugin.greenhouse.ui.SlotLevel

/** Per-slot output levels and CPU load, polled from the native engine. */
class RackMeters(private val numSlots: Int) {
    companion object {
        const val STEREO_CHANNELS = 2
        const val PERCENT_SCALE = 100f
        const val MAX_PERCENT = 100f
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

    var totalCpuLoad by mutableFloatStateOf(0f)
        private set

    private val rawLevels = FloatArray(numSlots * STEREO_CHANNELS)

    /** Reads the engine on the calling thread and publishes the results on Main. */
    suspend fun poll(player: AapAudioPlayer, isProcessing: Boolean, updateCpu: Boolean) {
        if (!isProcessing) {
            withContext(Dispatchers.Main) {
                reset()
            }

            return
        }

        player.getAllSlotLevels(rawLevels)

        var totalPercent = 0f
        val slotLoads = if (updateCpu) {
            totalPercent = toPercent(player.totalCpuLoad)
            FloatArray(numSlots) { i -> toPercent(player.getSlotCpuLoad(i)) }
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
