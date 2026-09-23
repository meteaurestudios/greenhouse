package org.androidaudioplugin.greenhouse

import org.androidaudioplugin.greenhouse.data.RackPreset
import org.androidaudioplugin.greenhouse.data.RackStateSerializer
import org.androidaudioplugin.greenhouse.data.SlotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RackStateSerializerTest {

    companion object {
        private const val TEST_PRESET_NAME = "Cosmic Synth Rig"
        private const val TEST_PLUGIN_SYNTH_ID = "org.androidaudioplugin.ports.dexed"
        private const val TEST_PACKAGE_SYNTH = "org.androidaudioplugin.ports.dexed"
        private const val TEST_DISPLAY_SYNTH = "Dexed FM Synthesizer"
        private const val TEST_PLUGIN_DELAY_ID = "org.androidaudioplugin.ports.delay"
        private const val TEST_PACKAGE_DELAY = "org.androidaudioplugin.ports.delay"
        private const val TEST_DISPLAY_DELAY = "Stereo Ping-Pong Delay"
        private const val TEST_STATE_BASE64 = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
        private const val TEST_PARAM_CUTOFF = 10
        private const val TEST_PARAM_RESONANCE = 11
        private const val TEST_PARAM_DELAY_TIME = 20
        private const val TEST_PARAM_FEEDBACK = 21
    }

    @Test
    fun testRackPresetSerializationRoundtrip() {
        val synthSlot = SlotState(
            slotIndex = 0,
            slotType = "Instrument",
            pluginId = TEST_PLUGIN_SYNTH_ID,
            packageName = TEST_PACKAGE_SYNTH,
            displayName = TEST_DISPLAY_SYNTH,
            isBypassed = false,
            selectedPresetIndex = 3,
            stateDataBase64 = TEST_STATE_BASE64,
            parameters = mapOf(
                TEST_PARAM_CUTOFF to 1200.5,
                TEST_PARAM_RESONANCE to 0.75
            ),
            customProperties = mapOf("color" to "#00FFCC")
        )

        val delaySlot = SlotState(
            slotIndex = 1,
            slotType = "Effect",
            pluginId = TEST_PLUGIN_DELAY_ID,
            packageName = TEST_PACKAGE_DELAY,
            displayName = TEST_DISPLAY_DELAY,
            isBypassed = true,
            selectedPresetIndex = -1,
            stateDataBase64 = null,
            parameters = mapOf(
                TEST_PARAM_DELAY_TIME to 375.0,
                TEST_PARAM_FEEDBACK to 0.6
            )
        )

        val emptySlot = SlotState(
            slotIndex = 2,
            slotType = "Effect",
            pluginId = null,
            isBypassed = false,
            selectedPresetIndex = -1,
            stateDataBase64 = null,
            parameters = emptyMap()
        )

        val originalPreset = RackPreset(
            name = TEST_PRESET_NAME,
            version = 1,
            createdAt = 1700000000000L,
            modifiedAt = 1700000001000L,
            slots = listOf(synthSlot, delaySlot, emptySlot),
            customProperties = mapOf(
                "tempoBpm" to "128.0",
                "masterVolume" to "0.9"
            )
        )

        val json = RackStateSerializer.serializeToJson(originalPreset)

        assertNotNull(json)
        assertTrue(json.contains(TEST_PRESET_NAME))
        assertTrue(json.contains(TEST_PLUGIN_SYNTH_ID))
        assertTrue(json.contains(TEST_STATE_BASE64))

        val deserialized = RackStateSerializer.deserializeFromJson(json)

        assertNotNull(deserialized)
        assertEquals(TEST_PRESET_NAME, deserialized!!.name)
        assertEquals(1, deserialized.version)
        assertEquals(1700000000000L, deserialized.createdAt)
        assertEquals(3, deserialized.slots.size)

        // Validate Slot 0 (Instrument)
        val s0 = deserialized.slots[0]
        assertEquals(0, s0.slotIndex)
        assertEquals("Instrument", s0.slotType)
        assertEquals(TEST_PLUGIN_SYNTH_ID, s0.pluginId)
        assertEquals(TEST_PACKAGE_SYNTH, s0.packageName)
        assertEquals(TEST_DISPLAY_SYNTH, s0.displayName)
        assertFalse(s0.isBypassed)
        assertEquals(3, s0.selectedPresetIndex)
        assertEquals(TEST_STATE_BASE64, s0.stateDataBase64)
        assertEquals(2, s0.parameters.size)
        assertEquals(1200.5, s0.parameters[TEST_PARAM_CUTOFF] ?: 0.0, 0.001)
        assertEquals(0.75, s0.parameters[TEST_PARAM_RESONANCE] ?: 0.0, 0.001)
        assertEquals("#00FFCC", s0.customProperties["color"])

        // Validate Slot 1 (Effect - Bypassed)
        val s1 = deserialized.slots[1]
        assertEquals(1, s1.slotIndex)
        assertEquals("Effect", s1.slotType)
        assertEquals(TEST_PLUGIN_DELAY_ID, s1.pluginId)
        assertTrue(s1.isBypassed)
        assertEquals(-1, s1.selectedPresetIndex)
        assertNull(s1.stateDataBase64)
        assertEquals(375.0, s1.parameters[TEST_PARAM_DELAY_TIME] ?: 0.0, 0.001)

        // Validate Slot 2 (Empty)
        val s2 = deserialized.slots[2]
        assertEquals(2, s2.slotIndex)
        assertNull(s2.pluginId)
        assertFalse(s2.isLoaded)

        // Validate Custom properties
        assertEquals("128.0", deserialized.customProperties["tempoBpm"])
        assertEquals("0.9", deserialized.customProperties["masterVolume"])
    }

    @Test
    fun testCorruptedJsonReturnsNullSafely() {
        val corruptedJson = "{ not a valid json ::"
        val result = RackStateSerializer.deserializeFromJson(corruptedJson)
        assertNull(result)
    }

    @Test
    fun testEmptyJsonReturnsSensibleDefaults() {
        val minimalJson = "{}"
        val result = RackStateSerializer.deserializeFromJson(minimalJson)

        assertNotNull(result)
        assertEquals("Untitled Preset", result!!.name)
        assertEquals(0, result.slots.size)
    }

    @Test
    fun testSessionNamePreservationAndHeaderMapping() {
        val sessionName = "Deep Space Ambient"
        val synthSlot = SlotState(
            slotIndex = 0,
            slotType = "Instrument",
            pluginId = TEST_PLUGIN_SYNTH_ID,
            packageName = TEST_PACKAGE_SYNTH,
            displayName = TEST_DISPLAY_SYNTH
        )
        val effectSlot = SlotState(
            slotIndex = 1,
            slotType = "Effect",
            pluginId = TEST_PLUGIN_DELAY_ID,
            packageName = TEST_PACKAGE_DELAY,
            displayName = TEST_DISPLAY_DELAY
        )

        val preset = RackPreset(
            name = sessionName,
            slots = listOf(synthSlot, effectSlot)
        )

        assertEquals(sessionName, preset.name)
        assertEquals(2, preset.loadedSlotCount)
        assertEquals(listOf(TEST_DISPLAY_SYNTH, TEST_DISPLAY_DELAY), preset.pluginDisplayNames)

        val json = RackStateSerializer.serializeToJson(preset)
        val parsed = RackStateSerializer.deserializeFromJson(json)

        assertNotNull(parsed)
        assertEquals(sessionName, parsed!!.name)
        assertEquals(2, parsed.loadedSlotCount)
        assertEquals(listOf(TEST_DISPLAY_SYNTH, TEST_DISPLAY_DELAY), parsed.pluginDisplayNames)
    }
}
