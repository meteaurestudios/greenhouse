package org.androidaudioplugin.greenhouse.ui.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.androidaudioplugin.greenhouse.core.AapAudioPlayer

/**
 * On-screen keyboard state. Lives in the ViewModel so held notes and octave survive
 * navigation and slot changes; hardware MIDI input lights the same keys.
 */
class VirtualKeyboardController(private val player: AapAudioPlayer) {
    companion object {
        const val MIDI_NOTE_COUNT = 128
        const val DEFAULT_OCTAVE = 4
        private const val KEY_UP = 0L
        private const val KEY_DOWN = 1L
    }

    val noteOnStates = mutableStateListOf<Long>().apply {
        addAll(List(MIDI_NOTE_COUNT) { KEY_UP })
    }

    var octave by mutableIntStateOf(DEFAULT_OCTAVE)

    var isHoldActive by mutableStateOf(false)
        private set

    fun toggleHold() {
        isHoldActive = !isHoldActive

        if (!isHoldActive) {
            releaseAllNotes()
        }
    }

    fun releaseAllNotes() {
        for (note in 0 until MIDI_NOTE_COUNT) {
            if (isDown(note)) {
                noteOnStates[note] = KEY_UP
                player.sendNoteOff(note)
            }
        }
    }

    fun noteOn(note: Int) {
        if (!isValidNote(note)) {
            return
        }

        // With hold on, each press toggles the key's latch.
        if (isHoldActive && isDown(note)) {
            noteOnStates[note] = KEY_UP
            player.sendNoteOff(note)
        } else {
            noteOnStates[note] = KEY_DOWN
            player.sendNoteOn(note)
        }
    }

    fun noteOff(note: Int) {
        if (!isValidNote(note) || isHoldActive) {
            return
        }

        noteOnStates[note] = KEY_UP
        player.sendNoteOff(note)
    }

    /** Lights a key for a note that arrived from hardware MIDI; does not send anything. */
    fun showExternalNoteOn(note: Int): Boolean {
        if (!isValidNote(note)) {
            return false
        }

        noteOnStates[note] = KEY_DOWN
        return true
    }

    /** Unlights a key for a hardware note-off, unless hold is latching it. Returns whether it was released. */
    fun showExternalNoteOff(note: Int): Boolean {
        if (!isValidNote(note) || isHoldActive) {
            return false
        }

        noteOnStates[note] = KEY_UP
        return true
    }

    private fun isDown(note: Int): Boolean {
        return noteOnStates[note] > KEY_UP
    }

    private fun isValidNote(note: Int): Boolean {
        return note in 0 until MIDI_NOTE_COUNT
    }
}
