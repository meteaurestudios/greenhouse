package org.androidaudioplugin.greenhouse

import org.androidaudioplugin.greenhouse.data.SlotPlacement
import org.androidaudioplugin.greenhouse.data.SlotState
import org.androidaudioplugin.greenhouse.data.SlotType
import org.junit.Assert.assertEquals
import org.junit.Test

class SlotPlacementTest {

    companion object {
        private const val MIDI_FX = "MIDI FX"
        private val CURRENT_RACK = listOf(SlotType.INSTRUMENT, SlotType.EFFECT, SlotType.EFFECT)
    }

    private fun slot(index: Int, type: String, pluginId: String? = null): SlotState {
        return SlotState(slotIndex = index, slotType = type, pluginId = pluginId)
    }

    private fun session(): List<SlotState> {
        return listOf(
            slot(0, SlotType.INSTRUMENT, "synth"),
            slot(1, SlotType.EFFECT),
            slot(2, SlotType.EFFECT, "delay")
        )
    }

    @Test
    fun sameLayoutKeepsTheIndices() {
        val placed = SlotPlacement.place(session(), CURRENT_RACK)

        assertEquals(listOf(0, 1, 2), placed.map { it.slotIndex })
        assertEquals(listOf("synth", null, "delay"), placed.map { it.pluginId })
    }

    @Test
    fun slotsFollowTheirRoleWhenTheLayoutChanges() {
        // A rack with a new slot type in front of the instrument
        val rack = listOf(MIDI_FX, SlotType.INSTRUMENT, SlotType.EFFECT, SlotType.EFFECT)

        val placed = SlotPlacement.place(session(), rack)

        assertEquals(listOf(1, 2, 3), placed.map { it.slotIndex })
        // Effects keep their order, including the empty one in front
        assertEquals(listOf("synth", null, "delay"), placed.map { it.pluginId })
    }

    @Test
    fun unknownTypesAndSlotsWithoutRoomAreLeftOut() {
        val saved = listOf(slot(0, MIDI_FX, "arp")) + session().map { it.copy(slotIndex = it.slotIndex + 1) }
        val rack = listOf(SlotType.INSTRUMENT, SlotType.EFFECT)

        val placed = SlotPlacement.place(saved, rack)

        assertEquals(listOf(0, 1), placed.map { it.slotIndex })
        assertEquals(listOf("synth", null), placed.map { it.pluginId })
    }

    @Test
    fun savedOrderIsTheSlotIndexOrder() {
        val placed = SlotPlacement.place(session().reversed(), CURRENT_RACK)

        assertEquals(listOf("synth", null, "delay"), placed.map { it.pluginId })
    }
}
