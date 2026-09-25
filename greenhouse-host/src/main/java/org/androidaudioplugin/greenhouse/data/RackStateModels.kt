package org.androidaudioplugin.greenhouse.data

import java.io.File

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
