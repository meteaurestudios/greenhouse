package org.androidaudioplugin.greenhouse

import org.androidaudioplugin.greenhouse.core.MidiSequencer
import org.androidaudioplugin.greenhouse.ui.host.SequenceNote
import org.androidaudioplugin.greenhouse.ui.host.SequencerController
import org.junit.Assert.assertEquals
import org.junit.Test

class SequenceNotesTest {

    companion object {
        private const val LENGTH_TICKS = MidiSequencer.TICKS_PER_BAR.toLong()
        private const val MIDI2_NOTE_ON = 0x40900000
        private const val MIDI2_NOTE_OFF = 0x40800000
        private const val MIDI1_NOTE_ON = 0x20900000
        private const val NOTE_SHIFT = 8
        private const val CHANNEL_SHIFT = 16
        private const val FULL_VELOCITY16 = 0xFFFF0000.toInt()
        private const val MIDI1_VELOCITY = 100
        private const val MIDI1_VELOCITY_MAX = 127f
        private const val HALF_VELOCITY16 = 0x80000000.toInt()
        private const val VELOCITY_TOLERANCE = 0.001f
    }

    private fun event(tick: Int, word0: Int, word1: Int = 0, slot: Int = 0, take: Int = 0): IntArray {
        return intArrayOf(tick, slot, take, 2, word0, word1, 0, 0)
    }

    private fun on(note: Int, channel: Int = 0): Int = MIDI2_NOTE_ON or (channel shl CHANNEL_SHIFT) or (note shl NOTE_SHIFT)

    private fun off(note: Int, channel: Int = 0): Int = MIDI2_NOTE_OFF or (channel shl CHANNEL_SHIFT) or (note shl NOTE_SHIFT)

    @Test
    fun noteOffEndsItsNoteOn() {
        val packed = event(0, on(60), FULL_VELOCITY16) + event(480, on(64), FULL_VELOCITY16) +
            event(960, off(60)) + event(1440, off(64))

        val notes = SequencerController.extractNotes(packed, LENGTH_TICKS)

        assertEquals(listOf(SequenceNote(0, 60, 0, 960), SequenceNote(0, 64, 480, 1440)), notes.sortedBy { it.startTick })
    }

    @Test
    fun channelsAndMidi1ZeroVelocityNoteOffs() {
        val midi1On = MIDI1_NOTE_ON or (62 shl NOTE_SHIFT) or MIDI1_VELOCITY
        val midi1Off = MIDI1_NOTE_ON or (62 shl NOTE_SHIFT)
        val packed = event(0, on(60, channel = 1), FULL_VELOCITY16) + event(100, off(60, channel = 0)) +
            event(200, midi1On) + event(300, midi1Off) + event(400, off(60, channel = 1))

        val notes = SequencerController.extractNotes(packed, LENGTH_TICKS)

        assertEquals(
            listOf(SequenceNote(0, 60, 0, 400), SequenceNote(0, 62, 200, 300, velocity = MIDI1_VELOCITY / MIDI1_VELOCITY_MAX)),
            notes.sortedBy { it.note }
        )
    }

    @Test
    fun takeAndVelocity() {
        val packed = event(0, on(60), FULL_VELOCITY16, take = 0) + event(480, off(60), take = 0) +
            event(960, on(64), HALF_VELOCITY16, take = 1) + event(1440, off(64), take = 1)

        val notes = SequencerController.extractNotes(packed, LENGTH_TICKS).sortedBy { it.startTick }

        assertEquals(listOf(0, 1), notes.map { it.take })
        assertEquals(1f, notes[0].velocity, VELOCITY_TOLERANCE)
        assertEquals(0.5f, notes[1].velocity, VELOCITY_TOLERANCE)
    }

    @Test
    fun noteAcrossTheLoopEndIsSplit() {
        // Recorded from 3600 over the loop end, released at 200 in the next pass
        val packed = event(1000, on(60), FULL_VELOCITY16) + event(2000, off(60)) +
            event(3600, on(67), FULL_VELOCITY16) + event(200, off(67))

        val notes = SequencerController.extractNotes(packed, LENGTH_TICKS)

        assertEquals(
            setOf(SequenceNote(0, 67, 3600, LENGTH_TICKS), SequenceNote(0, 67, 0, 200), SequenceNote(0, 60, 1000, 2000)),
            notes.toSet()
        )
    }

    @Test
    fun overlappingPassesPairInRecordingOrder() {
        // Pass 1: C4 from 1000 to 2000. Pass 2: C4 again from 800 to 2500, overlapping it in time.
        val packed = event(1000, on(60), FULL_VELOCITY16) + event(2000, off(60)) +
            event(800, on(60), FULL_VELOCITY16) + event(2500, off(60))

        val notes = SequencerController.extractNotes(packed, LENGTH_TICKS)

        assertEquals(setOf(SequenceNote(0, 60, 1000, 2000), SequenceNote(0, 60, 800, 2500)), notes.toSet())
    }

    @Test
    fun heldNotesWhileRecording() {
        val held = event(3000, on(60), FULL_VELOCITY16)

        assertEquals(listOf(SequenceNote(0, 60, 3000, SequenceNote.HELD)), SequencerController.extractNotes(held, LENGTH_TICKS, isRecording = true))
        assertEquals(listOf(SequenceNote(0, 60, 3000, LENGTH_TICKS)), SequencerController.extractNotes(held, LENGTH_TICKS))
    }
}
