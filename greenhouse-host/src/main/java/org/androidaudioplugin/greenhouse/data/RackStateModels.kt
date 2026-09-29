package org.androidaudioplugin.greenhouse.data

import org.androidaudioplugin.greenhouse.core.SequencerSettings
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.pow

/** Level and dry / wet mix the host applies to a slot on top of its plugin. Saved with the session; reset when a new plugin is added. */
object SlotHostSettings {
    /** At or below this level the slot is muted. */
    const val MIN_LEVEL_DB = -60f
    const val MAX_LEVEL_DB = 12f
    const val DEFAULT_LEVEL_DB = 0f
    const val DRY_ONLY_MIX = 0f
    const val WET_ONLY_MIX = 1f
    /** Mix a slot starts at: effects play fully wet until the user dials in some dry signal. */
    const val INITIAL_MIX = WET_ONLY_MIX
    /** Reference mix of the fader: where double-tap resets it and where its default dot sits. */
    const val DEFAULT_MIX = 0.5f

    private const val DB_PER_DECADE = 20f
    private const val DECIBEL_BASE = 10f
    private const val SILENT_GAIN = 0f

    fun levelDbToGain(levelDb: Float): Float {
        if (levelDb <= MIN_LEVEL_DB) {
            return SILENT_GAIN
        }

        return DECIBEL_BASE.pow(levelDb / DB_PER_DECADE)
    }
}

/**
 * Data models for Greenhouse Rack Presets (.ghrack) and Session State Persistence.
 */

data class SlotState(
    val slotIndex: Int,
    val slotType: String,
    val pluginId: String? = null,
    val packageName: String? = null,
    val displayName: String? = null,
    val isBypassed: Boolean = false,
    val levelDb: Float = SlotHostSettings.DEFAULT_LEVEL_DB,
    val mix: Float = SlotHostSettings.INITIAL_MIX,
    val selectedPresetIndex: Int = -1,
    val stateDataBase64: String? = null,
    val parameters: Map<Int, Double> = emptyMap(),
    val customProperties: Map<String, String> = emptyMap()
) {
    val isLoaded: Boolean
        get() = !pluginId.isNullOrBlank()
}

/** The MIDI sequence of a session and its sequencer settings. */
data class SequenceState(
    val settings: SequencerSettings = SequencerSettings(),
    /** Events packed like `MidiSequencer.getEvents()`, as little-endian 32-bit ints in Base64; null when empty. */
    val eventsBase64: String? = null,
    /** Loop length the first take set while the length setting is AUTO; 0 if none. */
    val autoLengthBars: Int = 0
) {
    companion object {
        fun encodeEvents(packed: IntArray): String? {
            if (packed.isEmpty()) {
                return null
            }

            val buffer = ByteBuffer.allocate(packed.size * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asIntBuffer().put(packed)
            return Base64.getEncoder().encodeToString(buffer.array())
        }

        fun decodeEvents(base64: String?): IntArray {
            if (base64.isNullOrBlank()) {
                return IntArray(0)
            }

            return try {
                val bytes = Base64.getDecoder().decode(base64)
                val ints = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                IntArray(ints.remaining()).also { ints.get(it) }
            } catch (e: IllegalArgumentException) {
                IntArray(0)
            }
        }
    }
}

data class RackPreset(
    val name: String,
    val version: Int = CURRENT_SCHEMA_VERSION,
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val slots: List<SlotState> = emptyList(),
    /** Absent from sessions saved before the sequencer existed. */
    val sequence: SequenceState? = null,
    val customProperties: Map<String, String> = emptyMap()
) {
    companion object {
        const val FORMAT_IDENTIFIER = "greenhouse_rack_preset"
        const val CURRENT_SCHEMA_VERSION = 1
        const val FILE_EXTENSION = "ghrack"
        const val MIME_TYPE = "application/json"
    }

    val loadedSlotCount: Int
        get() = slots.count { it.isLoaded }

    val pluginDisplayNames: List<String>
        get() = slots.mapNotNull { it.displayName?.ifBlank { null } }
}

data class RackPresetHeader(
    val name: String,
    val file: File,
    val modifiedAt: Long,
    val loadedSlotCount: Int,
    val pluginNames: List<String>
)
