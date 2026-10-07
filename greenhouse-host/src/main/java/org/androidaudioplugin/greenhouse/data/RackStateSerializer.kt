package org.androidaudioplugin.greenhouse.data

import android.util.Log
import org.androidaudioplugin.greenhouse.core.SequencerSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes and deserializes [RackPreset] instances to and from JSON format (.ghrack).
 */
object RackStateSerializer {
    private const val TAG = "RackStateSerializer"

    private const val KEY_FORMAT = "format"
    private const val KEY_VERSION = "version"
    private const val KEY_NAME = "name"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_MODIFIED_AT = "modifiedAt"
    private const val KEY_SLOTS = "slots"
    private const val KEY_CUSTOM_PROPERTIES = "customProperties"

    private const val KEY_SLOT_INDEX = "slotIndex"
    private const val KEY_SLOT_TYPE = "slotType"
    private const val KEY_SOURCE = "source"
    private const val KEY_PLUGIN_ID = "pluginId"
    private const val KEY_PACKAGE_NAME = "packageName"
    private const val KEY_DISPLAY_NAME = "displayName"
    private const val KEY_IS_BYPASSED = "isBypassed"
    private const val KEY_LEVEL_DB = "levelDb"
    private const val KEY_MIX = "mix"
    private const val KEY_SELECTED_PRESET_INDEX = "selectedPresetIndex"
    private const val KEY_STATE_DATA_BASE64 = "stateDataBase64"
    private const val KEY_PARAMETERS = "parameters"

    private const val KEY_SEQUENCE = "sequence"
    private const val KEY_BPM = "bpm"
    private const val KEY_LENGTH_BARS = "lengthBars"
    private const val KEY_IS_QUANTIZING = "isQuantizing"
    private const val KEY_QUANTIZE_TICKS = "quantizeTicks"
    private const val KEY_METRONOME_LEVEL_DB = "metronomeLevelDb"
    private const val KEY_EVENTS_BASE64 = "eventsBase64"
    private const val KEY_AUTO_LENGTH_BARS = "autoLengthBars"

    fun serializeToJson(preset: RackPreset): String {
        val root = JSONObject()

        root.put(KEY_FORMAT, RackPreset.FORMAT_IDENTIFIER)
        root.put(KEY_VERSION, preset.version)
        root.put(KEY_NAME, preset.name)
        root.put(KEY_CREATED_AT, preset.createdAt)
        root.put(KEY_MODIFIED_AT, preset.modifiedAt)

        val slotsArray = JSONArray()

        for (slot in preset.slots) {
            val slotObj = JSONObject()
            slotObj.put(KEY_SLOT_INDEX, slot.slotIndex)
            slotObj.put(KEY_SLOT_TYPE, slot.slotType)
            slotObj.put(KEY_SOURCE, slot.source)
            slotObj.put(KEY_PLUGIN_ID, slot.pluginId ?: JSONObject.NULL)
            slotObj.put(KEY_PACKAGE_NAME, slot.packageName ?: JSONObject.NULL)
            slotObj.put(KEY_DISPLAY_NAME, slot.displayName ?: JSONObject.NULL)
            slotObj.put(KEY_IS_BYPASSED, slot.isBypassed)
            slotObj.put(KEY_LEVEL_DB, slot.levelDb.toDouble())
            slotObj.put(KEY_MIX, slot.mix.toDouble())
            slotObj.put(KEY_SELECTED_PRESET_INDEX, slot.selectedPresetIndex)
            slotObj.put(KEY_STATE_DATA_BASE64, slot.stateDataBase64 ?: JSONObject.NULL)

            val paramsObj = JSONObject()

            for ((paramId, value) in slot.parameters) {
                paramsObj.put(paramId.toString(), value)
            }

            slotObj.put(KEY_PARAMETERS, paramsObj)

            val slotCustomObj = JSONObject()

            for ((key, value) in slot.customProperties) {
                slotCustomObj.put(key, value)
            }

            slotObj.put(KEY_CUSTOM_PROPERTIES, slotCustomObj)

            slotsArray.put(slotObj)
        }

        root.put(KEY_SLOTS, slotsArray)

        val sequence = preset.sequence

        if (sequence != null) {
            root.put(KEY_SEQUENCE, serializeSequence(sequence))
        }

        val customPropsObj = JSONObject()

        for ((key, value) in preset.customProperties) {
            customPropsObj.put(key, value)
        }

        root.put(KEY_CUSTOM_PROPERTIES, customPropsObj)

        return root.toString(2)
    }

    fun deserializeFromJson(jsonString: String): RackPreset? {
        return try {
            val root = JSONObject(jsonString)

            val name = root.optString(KEY_NAME, "Untitled Preset")
            val version = root.optInt(KEY_VERSION, RackPreset.CURRENT_SCHEMA_VERSION)
            val createdAt = root.optLong(KEY_CREATED_AT, System.currentTimeMillis())
            val modifiedAt = root.optLong(KEY_MODIFIED_AT, System.currentTimeMillis())

            val slotsList = mutableListOf<SlotState>()
            val slotsArray = root.optJSONArray(KEY_SLOTS)

            if (slotsArray != null) {
                for (i in 0 until slotsArray.length()) {
                    val slotObj = slotsArray.optJSONObject(i)

                    if (slotObj != null) {
                        val slotIndex = slotObj.optInt(KEY_SLOT_INDEX, i)
                        val slotType = slotObj.optString(KEY_SLOT_TYPE, if (slotIndex == 0) SlotType.INSTRUMENT else SlotType.EFFECT)
                        // Absent from sessions saved before there were several device sources
                        val source = slotObj.optString(KEY_SOURCE, SlotState.DEFAULT_SOURCE).ifBlank { SlotState.DEFAULT_SOURCE }
                        val pluginId = if (slotObj.has(KEY_PLUGIN_ID) && !slotObj.isNull(KEY_PLUGIN_ID)) {
                            slotObj.optString(KEY_PLUGIN_ID).ifBlank { null }
                        } else {
                            null
                        }

                        val packageName = if (slotObj.has(KEY_PACKAGE_NAME) && !slotObj.isNull(KEY_PACKAGE_NAME)) {
                            slotObj.optString(KEY_PACKAGE_NAME).ifBlank { null }
                        } else {
                            null
                        }

                        val displayName = if (slotObj.has(KEY_DISPLAY_NAME) && !slotObj.isNull(KEY_DISPLAY_NAME)) {
                            slotObj.optString(KEY_DISPLAY_NAME).ifBlank { null }
                        } else {
                            null
                        }

                        val isBypassed = slotObj.optBoolean(KEY_IS_BYPASSED, false)
                        // Absent from sessions saved before the host level and mix existed
                        val levelDb = slotObj.optDouble(KEY_LEVEL_DB, SlotHostSettings.DEFAULT_LEVEL_DB.toDouble())
                            .toFloat()
                            .coerceIn(SlotHostSettings.MIN_LEVEL_DB, SlotHostSettings.MAX_LEVEL_DB)
                        val mix = slotObj.optDouble(KEY_MIX, SlotHostSettings.INITIAL_MIX.toDouble())
                            .toFloat()
                            .coerceIn(SlotHostSettings.DRY_ONLY_MIX, SlotHostSettings.WET_ONLY_MIX)
                        val selectedPresetIndex = slotObj.optInt(KEY_SELECTED_PRESET_INDEX, -1)
                        val stateDataBase64 = if (slotObj.has(KEY_STATE_DATA_BASE64) && !slotObj.isNull(KEY_STATE_DATA_BASE64)) {
                            slotObj.optString(KEY_STATE_DATA_BASE64).ifBlank { null }
                        } else {
                            null
                        }

                        val paramsMap = mutableMapOf<Int, Double>()
                        val paramsObj = slotObj.optJSONObject(KEY_PARAMETERS)

                        if (paramsObj != null) {
                            val keys = paramsObj.keys()

                            while (keys.hasNext()) {
                                val keyStr = keys.next()
                                val paramId = keyStr.toIntOrNull()

                                if (paramId != null) {
                                    paramsMap[paramId] = paramsObj.optDouble(keyStr, 0.0)
                                }
                            }
                        }

                        val slotCustomMap = mutableMapOf<String, String>()
                        val slotCustomObj = slotObj.optJSONObject(KEY_CUSTOM_PROPERTIES)

                        if (slotCustomObj != null) {
                            val keys = slotCustomObj.keys()

                            while (keys.hasNext()) {
                                val key = keys.next()
                                slotCustomMap[key] = slotCustomObj.optString(key, "")
                            }
                        }

                        slotsList.add(
                            SlotState(
                                slotIndex = slotIndex,
                                slotType = slotType,
                                source = source,
                                pluginId = pluginId,
                                packageName = packageName,
                                displayName = displayName,
                                isBypassed = isBypassed,
                                levelDb = levelDb,
                                mix = mix,
                                selectedPresetIndex = selectedPresetIndex,
                                stateDataBase64 = stateDataBase64,
                                parameters = paramsMap,
                                customProperties = slotCustomMap
                            )
                        )
                    }
                }
            }

            val customPropsMap = mutableMapOf<String, String>()
            val customPropsObj = root.optJSONObject(KEY_CUSTOM_PROPERTIES)

            if (customPropsObj != null) {
                val keys = customPropsObj.keys()

                while (keys.hasNext()) {
                    val key = keys.next()
                    customPropsMap[key] = customPropsObj.optString(key, "")
                }
            }

            RackPreset(
                name = name,
                version = version,
                createdAt = createdAt,
                modifiedAt = modifiedAt,
                slots = slotsList,
                sequence = root.optJSONObject(KEY_SEQUENCE)?.let { deserializeSequence(it) },
                customProperties = customPropsMap
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to deserialize RackPreset from JSON", e)
            null
        }
    }

    private fun serializeSequence(sequence: SequenceState): JSONObject {
        val settings = sequence.settings
        val sequenceObj = JSONObject()
        sequenceObj.put(KEY_BPM, settings.bpm)
        sequenceObj.put(KEY_LENGTH_BARS, settings.lengthBars)
        sequenceObj.put(KEY_IS_QUANTIZING, settings.isQuantizing)
        sequenceObj.put(KEY_QUANTIZE_TICKS, settings.quantizeTicks)
        sequenceObj.put(KEY_METRONOME_LEVEL_DB, settings.metronomeLevelDb.toDouble())
        sequenceObj.put(KEY_EVENTS_BASE64, sequence.eventsBase64 ?: JSONObject.NULL)
        sequenceObj.put(KEY_AUTO_LENGTH_BARS, sequence.autoLengthBars)
        return sequenceObj
    }

    /** Out of range values are clamped by the engine when the sequence is restored. */
    private fun deserializeSequence(sequenceObj: JSONObject): SequenceState {
        val defaults = SequencerSettings()
        val settings = SequencerSettings(
            bpm = sequenceObj.optDouble(KEY_BPM, defaults.bpm),
            lengthBars = sequenceObj.optInt(KEY_LENGTH_BARS, defaults.lengthBars),
            isQuantizing = sequenceObj.optBoolean(KEY_IS_QUANTIZING, defaults.isQuantizing),
            quantizeTicks = sequenceObj.optInt(KEY_QUANTIZE_TICKS, defaults.quantizeTicks),
            metronomeLevelDb = sequenceObj.optDouble(KEY_METRONOME_LEVEL_DB, defaults.metronomeLevelDb.toDouble()).toFloat()
        )
        val eventsBase64 = if (sequenceObj.isNull(KEY_EVENTS_BASE64)) {
            null
        } else {
            sequenceObj.optString(KEY_EVENTS_BASE64).ifBlank { null }
        }

        return SequenceState(settings, eventsBase64, sequenceObj.optInt(KEY_AUTO_LENGTH_BARS, 0))
    }
}
