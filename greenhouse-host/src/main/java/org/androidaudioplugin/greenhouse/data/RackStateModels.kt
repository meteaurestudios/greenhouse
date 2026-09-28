package org.androidaudioplugin.greenhouse.data

import java.io.File
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

data class RackPreset(
    val name: String,
    val version: Int = CURRENT_SCHEMA_VERSION,
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val slots: List<SlotState> = emptyList(),
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
