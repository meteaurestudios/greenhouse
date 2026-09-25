package org.androidaudioplugin.greenhouse.data

import android.util.Log
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
    private const val KEY_PLUGIN_ID = "pluginId"
    private const val KEY_PACKAGE_NAME = "packageName"
    private const val KEY_DISPLAY_NAME = "displayName"
    private const val KEY_IS_BYPASSED = "isBypassed"
    private const val KEY_SELECTED_PRESET_INDEX = "selectedPresetIndex"
    private const val KEY_STATE_DATA_BASE64 = "stateDataBase64"
    private const val KEY_PARAMETERS = "parameters"

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
            slotObj.put(KEY_PLUGIN_ID, slot.pluginId ?: JSONObject.NULL)
            slotObj.put(KEY_PACKAGE_NAME, slot.packageName ?: JSONObject.NULL)
            slotObj.put(KEY_DISPLAY_NAME, slot.displayName ?: JSONObject.NULL)
            slotObj.put(KEY_IS_BYPASSED, slot.isBypassed)
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
                        val slotType = slotObj.optString(KEY_SLOT_TYPE, if (slotIndex == 0) "Instrument" else "Effect")
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
                                pluginId = pluginId,
                                packageName = packageName,
                                displayName = displayName,
                                isBypassed = isBypassed,
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
                customProperties = customPropsMap
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to deserialize RackPreset from JSON", e)
            null
        }
    }
}
