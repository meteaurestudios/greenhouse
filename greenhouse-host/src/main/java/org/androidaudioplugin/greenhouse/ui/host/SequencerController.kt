package org.androidaudioplugin.greenhouse.ui.host

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidaudioplugin.greenhouse.core.MidiSequencer
import org.androidaudioplugin.greenhouse.core.SequencerSettings
import org.androidaudioplugin.greenhouse.core.SequencerStatus
import org.androidaudioplugin.greenhouse.core.TransportState
import org.androidaudioplugin.greenhouse.data.SequenceState
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A note of the sequence, as the read-only lane draws it. */
data class SequenceNote(
    val slot: Int,
    val note: Int,
    val startTick: Long,
    /** [HELD]: still held while recording, it reaches the playhead. */
    val endTick: Long,
    /** Take that recorded it: undo removes the highest one. */
    val take: Int = 0,
    /** Note-on velocity, from 0 to 1. */
    val velocity: Float = 1f
) {
    companion object {
        const val HELD = -1L
    }
}

/** A quantize grid offered in the settings. */
data class QuantizeOption(val label: String, val ticks: Int)

/**
 * Transport, settings and session state of the MIDI sequencer. Recording and playback run in the
 * native engine; this polls it for the UI and saves / restores the sequence with the session.
 */
class SequencerController(
    private val context: Context,
    private val audio: AudioEngineController,
    private val scope: CoroutineScope,
    private val postStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "SequencerController"
        const val MIDI_FILE_MIME_TYPE = "audio/midi"
        const val DEFAULT_EXPORT_FILE_NAME = "greenhouse-sequence.mid"
        /** Fixed loop lengths, in bars; AUTO is switched on and off separately. */
        val LENGTH_OPTIONS = listOf(1, 2, 4, 8, 16)
        val QUANTIZE_OPTIONS = listOf(
            QuantizeOption("1/4", MidiSequencer.PPQ),
            QuantizeOption("1/8", MidiSequencer.PPQ / 2),
            QuantizeOption("1/16", MidiSequencer.PPQ / 4),
            QuantizeOption("1/32", MidiSequencer.PPQ / 8)
        )

        /** Taps further apart than this start a new tempo. */
        private const val TAP_TEMPO_TIMEOUT_MS = 2000L
        private const val TAP_TEMPO_MAX_INTERVALS = 4
        private const val MIN_TAPS_FOR_TEMPO = 2
        private const val MILLIS_PER_MINUTE = 60_000.0
        private const val BPM_DECIMALS_SCALE = 10.0

        // UMP fields of the first word, to find notes in packed events
        private const val UMP_MESSAGE_TYPE_SHIFT = 28
        private const val UMP_STATUS_SHIFT = 20
        private const val UMP_NOTE_SHIFT = 8
        private const val UMP_MIDI2_VELOCITY_SHIFT = 16
        private const val UMP_MIDI1_VELOCITY_MAX = 0x7F
        private const val UMP_MIDI2_VELOCITY_MAX = 0xFFFF
        private const val UMP_NIBBLE_MASK = 0xF
        private const val UMP_DATA7_MASK = 0x7F
        private const val UMP_GROUP_CHANNEL_NOTE_MASK = 0x0F0F7F00
        private const val UMP_MIDI1_CHANNEL_VOICE = 0x2
        private const val UMP_MIDI2_CHANNEL_VOICE = 0x4
        private const val UMP_STATUS_NOTE_OFF = 0x8
        private const val UMP_STATUS_NOTE_ON = 0x9
        private const val SLOT_KEY_SHIFT = 32

        /** Whole tempos without decimals, e.g. "120" or "97.5". */
        fun formatBpm(bpm: Double): String {
            if (bpm % 1.0 == 0.0) {
                return bpm.toInt().toString()
            }

            return String.format(Locale.US, "%.1f", bpm)
        }

        /**
         * The notes of packed events in recording order, for display. Each note-off ends the latest
         * note-on of its note: pairing in recording order stays right when notes of several loop
         * passes overlap in time. A note ending before it starts was held across the loop end: it is
         * split into the end of the loop and its start. Notes without a note-off are [SequenceNote.HELD]
         * while [isRecording], and end at the loop end otherwise.
         *
         * With a [quantizeTicks] grid (0: none), the notes are where the engine plays them: each start
         * on the nearest grid line (the loop end wraps to the start), its end moved with it, up to the
         * loop end. Held notes stay where they were played until released.
         */
        fun extractNotes(packed: IntArray, lengthTicks: Long, isRecording: Boolean = false, quantizeTicks: Int = 0): List<SequenceNote> {
            val notes = mutableListOf<SequenceNote>()
            val openNotes = HashMap<Long, OpenNote>()

            for (index in 0..packed.size - MidiSequencer.EVENT_STRIDE step MidiSequencer.EVENT_STRIDE) {
                val tick = packed[index + MidiSequencer.EVENT_TICK].toLong()
                val slot = packed[index + MidiSequencer.EVENT_SLOT]
                val take = packed[index + MidiSequencer.EVENT_TAKE]
                val word0 = packed[index + MidiSequencer.EVENT_WORDS]
                val type = (word0 ushr UMP_MESSAGE_TYPE_SHIFT) and UMP_NIBBLE_MASK
                val status = (word0 ushr UMP_STATUS_SHIFT) and UMP_NIBBLE_MASK

                if ((type != UMP_MIDI1_CHANNEL_VOICE && type != UMP_MIDI2_CHANNEL_VOICE) ||
                    (status != UMP_STATUS_NOTE_ON && status != UMP_STATUS_NOTE_OFF)) {
                    continue
                }

                val key = (slot.toLong() shl SLOT_KEY_SHIFT) or (word0 and UMP_GROUP_CHANNEL_NOTE_MASK).toLong()
                val isMidi1ZeroVelocity = type == UMP_MIDI1_CHANNEL_VOICE && (word0 and UMP_DATA7_MASK) == 0

                val isNoteOn = status == UMP_STATUS_NOTE_ON && !isMidi1ZeroVelocity
                val open = openNotes.remove(key)

                if (open != null) {
                    // A retriggered note ends the previous one where the new one starts
                    val end = if (isNoteOn) {
                        quantizeStart(tick, quantizeTicks, lengthTicks)
                    } else {
                        releaseTick(tick, open, lengthTicks)
                    }

                    addNote(notes, open.note, end, lengthTicks)
                }

                if (isNoteOn) {
                    val velocity = if (type == UMP_MIDI1_CHANNEL_VOICE) {
                        (word0 and UMP_DATA7_MASK).toFloat() / UMP_MIDI1_VELOCITY_MAX
                    } else {
                        val word1 = packed[index + MidiSequencer.EVENT_WORDS + 1]
                        (word1 ushr UMP_MIDI2_VELOCITY_SHIFT).toFloat() / UMP_MIDI2_VELOCITY_MAX
                    }

                    val note = (word0 ushr UMP_NOTE_SHIFT) and UMP_DATA7_MASK
                    val quantized = quantize(tick, quantizeTicks)
                    val start = quantizeStart(tick, quantizeTicks, lengthTicks)
                    val isWrapped = start != quantized
                    openNotes[key] = OpenNote(SequenceNote(slot, note, start, start, take, velocity), quantized - tick, tick, isWrapped)
                }
            }

            for (open in openNotes.values) {
                if (isRecording) {
                    // Where it was played until released: quantized, it could start ahead of the playhead
                    notes.add(open.note.copy(startTick = open.playedTick, endTick = SequenceNote.HELD))
                } else {
                    addNote(notes, open.note, lengthTicks, lengthTicks)
                }
            }

            return notes
        }

        /** The nearest line of the quantizeTicks grid, as the engine rounds it. No grid: tick. */
        private fun quantize(tick: Long, quantizeTicks: Int): Long {
            if (quantizeTicks <= 0) {
                return tick
            }

            return (tick.toDouble() / quantizeTicks).roundToLong() * quantizeTicks
        }

        /** A quantized note start: quantized onto the loop end, it wraps to the start. */
        private fun quantizeStart(tick: Long, quantizeTicks: Int, lengthTicks: Long): Long {
            val quantized = quantize(tick, quantizeTicks)

            if (quantized >= lengthTicks && tick < lengthTicks) {
                return quantized - lengthTicks
            }

            return quantized
        }

        /** A note-off moved as far as its note-on: released in the pass its note-on wrapped from, it wraps with it. */
        private fun releaseTick(tick: Long, open: OpenNote, lengthTicks: Long): Long {
            val shifted = tick + open.shift

            if (open.isWrapped && shifted >= lengthTicks) {
                return shifted - lengthTicks
            }

            return shifted.coerceIn(0L, lengthTicks)
        }

        private fun addNote(notes: MutableList<SequenceNote>, open: SequenceNote, end: Long, lengthTicks: Long) {
            if (end >= open.startTick) {
                notes.add(open.copy(endTick = end))
                return
            }

            // Held across the loop end: it sounds until the end, and from the start again
            notes.add(open.copy(endTick = max(lengthTicks, open.startTick)))
            notes.add(open.copy(startTick = 0L, endTick = end))
        }
    }

    /**
     * A note waiting for its note-off, which moves by shift as the quantized note-on did. playedTick: its
     * unquantized start. isWrapped: quantized onto the loop end, it plays from the start.
     */
    private class OpenNote(val note: SequenceNote, val shift: Long, val playedTick: Long, val isWrapped: Boolean)

    /** What the UI shows of the sequence, read together. */
    private class Snapshot(val status: SequencerStatus, val settings: SequencerSettings, val notes: List<SequenceNote>)

    private val sequencer = audio.engine.sequencer

    var settings by mutableStateOf(SequencerSettings())
        private set

    var status by mutableStateOf(SequencerStatus())
        private set

    /** Notes of the sequence for the lane, refreshed when the sequence changes. */
    var notes by mutableStateOf<List<SequenceNote>>(emptyList())
        private set

    /** Tick of the playhead while playing. Separate from [status] so it can change without recomposing its readers. */
    var positionTick by mutableLongStateOf(0L)
        private set

    /** [System.nanoTime] when [positionTick] was read, to move the playhead on between polls. */
    var positionNanos = 0L
        private set

    val hasEvents: Boolean
        get() = status.eventCount > 0

    val isRunning: Boolean
        get() = status.state != TransportState.STOPPED

    val quantizeOption: QuantizeOption
        get() = QUANTIZE_OPTIONS.find { it.ticks == settings.quantizeTicks } ?: QUANTIZE_OPTIONS.first { it.ticks == MidiSequencer.DEFAULT_QUANTIZE_TICKS }

    @Volatile
    private var polledRevision = -1
    private val tapTimes = ArrayDeque<Long>()

    init {
        apply(snapshot())
    }

    /** Plays from the start, starting the audio engine if needed; stops if running. */
    fun togglePlayback() {
        if (isRunning) {
            sequencer.stop()
            return
        }

        if (ensureAudioRunning()) {
            sequencer.startPlayback()
        }
    }

    /**
     * Stopped: arms recording (it starts at the first note). Playing: punches in.
     * Recording: punches out, or disarms if nothing was played yet.
     */
    fun toggleRecording() {
        if (status.isRecording) {
            sequencer.stopRecording()
            return
        }

        if (ensureAudioRunning()) {
            sequencer.startRecording()
        }
    }

    fun updateSettings(transform: (SequencerSettings) -> SequencerSettings) {
        sequencer.settings = transform(settings)
        // Read back: the engine clamps the values
        settings = sequencer.settings
    }

    /** Sets the tempo from the average interval of the last taps. */
    fun tapTempo() {
        val now = SystemClock.uptimeMillis()

        if (tapTimes.isNotEmpty() && now - tapTimes.last() > TAP_TEMPO_TIMEOUT_MS) {
            tapTimes.clear()
        }

        tapTimes.addLast(now)

        while (tapTimes.size > TAP_TEMPO_MAX_INTERVALS + 1) {
            tapTimes.removeFirst()
        }

        if (tapTimes.size < MIN_TAPS_FOR_TEMPO) {
            return
        }

        val averageInterval = (tapTimes.last() - tapTimes.first()).toDouble() / (tapTimes.size - 1)
        val bpm = (MILLIS_PER_MINUTE / averageInterval * BPM_DECIMALS_SCALE).roundToInt() / BPM_DECIMALS_SCALE
        updateSettings { it.copy(bpm = bpm) }
    }

    fun undoLastTake() {
        sequencer.undoLastTake()
        postStatus("Last take removed.")
    }

    fun clear() {
        sequencer.clear()
        postStatus("Sequence cleared.")
    }

    fun importMidiFile(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to read MIDI file", e)
                null
            }

            val snapshot = if (bytes != null && sequencer.importStandardMidiFile(bytes)) {
                snapshot()
            } else {
                null
            }

            withContext(Dispatchers.Main) {
                if (snapshot == null) {
                    postStatus("Couldn't read that MIDI file.")
                    return@withContext
                }

                apply(snapshot)
                postStatus("MIDI file imported: ${status.eventCount} events at ${formatBpm(settings.bpm)} bpm.")
            }
        }
    }

    fun exportMidiFile(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val isExported = try {
                context.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(sequencer.exportStandardMidiFile())
                    true
                } ?: false
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to write MIDI file", e)
                false
            }

            val message = if (isExported) {
                "Sequence exported as a MIDI file."
            } else {
                "Couldn't export the MIDI file."
            }

            withContext(Dispatchers.Main) {
                postStatus(message)
            }
        }
    }

    /** The sequence (in recording order) and settings to save with the session. Any thread. */
    fun captureState(): SequenceState {
        // Read from the engine: the polled status can lag behind a take that just set the length
        return SequenceState(sequencer.settings, SequenceState.encodeEvents(sequencer.getEvents()), sequencer.status.autoLengthBars)
    }

    /** Replaces the sequence and settings (default ones for null) and stops the transport. */
    fun restore(state: SequenceState?) {
        sequencer.settings = state?.settings ?: SequencerSettings()
        sequencer.setEvents(SequenceState.decodeEvents(state?.eventsBase64), state?.autoLengthBars ?: 0)
        apply(snapshot())
    }

    /** The native revision, current even between polls. */
    fun currentRevision(): Int {
        return sequencer.status.revision
    }

    /** Reads the engine on the calling thread and publishes the results on Main. */
    suspend fun poll() {
        val polledPosition = sequencer.getPositionTick()
        val polledNanos = System.nanoTime()
        val polled = sequencer.status
        val snapshot = if (polled.revision != polledRevision) {
            snapshot(polled)
        } else {
            null
        }

        withContext(Dispatchers.Main) {
            positionTick = polledPosition
            positionNanos = polledNanos
            status = polled

            if (snapshot != null) {
                apply(snapshot)
            }
        }
    }

    private fun snapshot(polled: SequencerStatus = sequencer.status): Snapshot {
        val settings = sequencer.settings
        val quantizeTicks = if (settings.isQuantizing) {
            settings.quantizeTicks
        } else {
            0
        }

        return Snapshot(polled, settings, extractNotes(sequencer.getEvents(), polled.lengthTicks, polled.isRecording, quantizeTicks))
    }

    private fun apply(snapshot: Snapshot) {
        status = snapshot.status
        settings = snapshot.settings
        notes = snapshot.notes
        polledRevision = snapshot.status.revision
    }

    private fun ensureAudioRunning(): Boolean {
        audio.ensureRunning()
        return audio.isProcessing
    }
}
