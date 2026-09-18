package org.androidaudioplugin.greenhouse

import org.androidaudioplugin.ParameterInformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterSyncTest {

    companion object {
        private const val TEST_EPSILON = 1e-5
        private const val TEST_COOLDOWN_MS = 350L
        private const val TEST_PARAM_ID_FILTER_CUTOFF = 100
        private const val TEST_PARAM_ID_RESONANCE = 101
        private const val TEST_PARAM_ID_ATTACK = 102
        private const val DEFAULT_CUTOFF = 440.0
        private const val DEFAULT_RESONANCE = 0.5
        private const val DEFAULT_ATTACK = 0.01
    }

    private data class SimulatedParameterState(
        val param: ParameterInformation,
        var nativeValue: Double
    )

    private fun detectParameterChanges(
        parameters: List<SimulatedParameterState>,
        cachedValues: Map<Int, Double>,
        lastHostEdits: Map<Int, Long>,
        currentTimeMs: Long,
        ignoreCooldown: Boolean = false
    ): List<Pair<Int, Double>> {
        val updates = mutableListOf<Pair<Int, Double>>()

        for (state in parameters) {
            val param = state.param

            if (!ignoreCooldown) {
                val lastEdit = lastHostEdits[param.id] ?: 0L

                if (currentTimeMs - lastEdit < TEST_COOLDOWN_MS) {
                    continue
                }
            }

            val currentVal = state.nativeValue
            val cachedVal = cachedValues[param.id]

            if (cachedVal == null || Math.abs(cachedVal - currentVal) > TEST_EPSILON) {
                updates.add(Pair(param.id, currentVal))
            }
        }

        return updates
    }

    @Test
    fun testDetectsNativeUiParameterChanges() {
        val params = listOf(
            SimulatedParameterState(ParameterInformation(TEST_PARAM_ID_FILTER_CUTOFF, "Cutoff", 20.0, 20000.0, DEFAULT_CUTOFF), 880.0),
            SimulatedParameterState(ParameterInformation(TEST_PARAM_ID_RESONANCE, "Resonance", 0.0, 1.0, DEFAULT_RESONANCE), DEFAULT_RESONANCE)
        )

        val cachedValues = mapOf(
            TEST_PARAM_ID_FILTER_CUTOFF to DEFAULT_CUTOFF,
            TEST_PARAM_ID_RESONANCE to DEFAULT_RESONANCE
        )
        val lastHostEdits = emptyMap<Int, Long>()

        val updates = detectParameterChanges(
            parameters = params,
            cachedValues = cachedValues,
            lastHostEdits = lastHostEdits,
            currentTimeMs = 1000L
        )

        assertEquals(1, updates.size)
        assertEquals(TEST_PARAM_ID_FILTER_CUTOFF, updates[0].first)
        assertEquals(880.0, updates[0].second, 0.0001)
    }

    @Test
    fun testEpsilonTolerancePreventsSpuriousUpdates() {
        val params = listOf(
            SimulatedParameterState(
                ParameterInformation(TEST_PARAM_ID_RESONANCE, "Resonance", 0.0, 1.0, DEFAULT_RESONANCE),
                DEFAULT_RESONANCE + 1e-7
            )
        )

        val cachedValues = mapOf(
            TEST_PARAM_ID_RESONANCE to DEFAULT_RESONANCE
        )
        val lastHostEdits = emptyMap<Int, Long>()

        val updates = detectParameterChanges(
            parameters = params,
            cachedValues = cachedValues,
            lastHostEdits = lastHostEdits,
            currentTimeMs = 1000L
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun testHostEditCooldownPreventsRubberBanding() {
        val params = listOf(
            SimulatedParameterState(ParameterInformation(TEST_PARAM_ID_FILTER_CUTOFF, "Cutoff", 20.0, 20000.0, DEFAULT_CUTOFF), 500.0),
            SimulatedParameterState(ParameterInformation(TEST_PARAM_ID_ATTACK, "Attack", 0.0, 5.0, DEFAULT_ATTACK), 0.05)
        )

        val cachedValues = mapOf(
            TEST_PARAM_ID_FILTER_CUTOFF to 1000.0,
            TEST_PARAM_ID_ATTACK to DEFAULT_ATTACK
        )

        // Host just edited Cutoff 100ms ago (cooldown window active)
        val lastHostEdits = mapOf(
            TEST_PARAM_ID_FILTER_CUTOFF to 900L
        )

        val updatesDuringCooldown = detectParameterChanges(
            parameters = params,
            cachedValues = cachedValues,
            lastHostEdits = lastHostEdits,
            currentTimeMs = 1000L,
            ignoreCooldown = false
        )

        // Cutoff should be skipped due to cooldown; Attack should update
        assertEquals(1, updatesDuringCooldown.size)
        assertEquals(TEST_PARAM_ID_ATTACK, updatesDuringCooldown[0].first)

        // After cooldown window expires (400ms after edit)
        val updatesAfterCooldown = detectParameterChanges(
            parameters = params,
            cachedValues = cachedValues,
            lastHostEdits = lastHostEdits,
            currentTimeMs = 1301L,
            ignoreCooldown = false
        )

        assertEquals(2, updatesAfterCooldown.size)
    }

    @Test
    fun testIgnoreCooldownAppliesUpdatesImmediately() {
        val params = listOf(
            SimulatedParameterState(ParameterInformation(TEST_PARAM_ID_FILTER_CUTOFF, "Cutoff", 20.0, 20000.0, DEFAULT_CUTOFF), 500.0)
        )

        val cachedValues = mapOf(
            TEST_PARAM_ID_FILTER_CUTOFF to 1000.0
        )

        val lastHostEdits = mapOf(
            TEST_PARAM_ID_FILTER_CUTOFF to 950L
        )

        // ignoreCooldown = true should bypass cooldown (used on preset load or view switch)
        val updates = detectParameterChanges(
            parameters = params,
            cachedValues = cachedValues,
            lastHostEdits = lastHostEdits,
            currentTimeMs = 1000L,
            ignoreCooldown = true
        )

        assertEquals(1, updates.size)
        assertEquals(TEST_PARAM_ID_FILTER_CUTOFF, updates[0].first)
        assertEquals(500.0, updates[0].second, 0.0001)
    }
}
