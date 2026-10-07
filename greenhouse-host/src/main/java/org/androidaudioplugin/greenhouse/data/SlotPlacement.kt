package org.androidaudioplugin.greenhouse.data

/** Roles of the rack slots, saved as `slotType` in .ghrack sessions. */
object SlotType {
    const val INSTRUMENT = "Instrument"
    const val EFFECT = "Effect"
}

/**
 * Places the slots of a saved session in the rack by role rather than by index: the n-th saved slot
 * of a type goes to the n-th rack slot of that type. Sessions keep loading when the rack layout
 * changes, e.g. when a slot of a new type is added in front of the instrument.
 */
object SlotPlacement {
    /**
     * Returns the saved slots that fit in a rack whose slots have [rackSlotTypes], with their
     * slotIndex in that rack. Slots of a type the rack doesn't have (e.g. saved by a newer version),
     * or beyond the rack's slots of their type, are left out.
     */
    fun place(saved: List<SlotState>, rackSlotTypes: List<String>): List<SlotState> {
        val placed = mutableListOf<SlotState>()

        for ((type, savedOfType) in saved.sortedBy { it.slotIndex }.groupBy { it.slotType }) {
            val targets = rackSlotTypes.indices.filter { rackSlotTypes[it] == type }

            savedOfType.zip(targets) { state, target ->
                placed.add(state.copy(slotIndex = target))
            }
        }

        return placed.sortedBy { it.slotIndex }
    }
}
