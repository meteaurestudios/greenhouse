package org.androidaudioplugin.greenhouse.core

enum class TransportState {
    STOPPED,
    /** Recording, waiting for the first note to start. */
    ARMED,
    PLAYING
}

/** Sequencer settings, saved with the session. */
data class SequencerSettings(
    val bpm: Double = MidiSequencer.DEFAULT_BPM,
    /** [MidiSequencer.AUTO_LENGTH_BARS]: the first take sets the length. */
    val lengthBars: Int = MidiSequencer.AUTO_LENGTH_BARS,
    /** Quantize note starts to [quantizeTicks] on playback. Recorded timing is kept. */
    val isQuantizing: Boolean = false,
    val quantizeTicks: Int = MidiSequencer.DEFAULT_QUANTIZE_TICKS
)

data class SequencerStatus(
    val state: TransportState = TransportState.STOPPED,
    val isRecording: Boolean = false,
    /** While a take sets the length: the bars recorded so far. */
    val lengthTicks: Long = MidiSequencer.TICKS_PER_BAR.toLong(),
    /** Loop length the first take set while [SequencerSettings.lengthBars] is AUTO; 0 if none. */
    val autoLengthBars: Int = 0,
    val eventCount: Int = 0,
    /** Changes whenever the sequence or the settings change. */
    val revision: Int = 0
)

/**
 * Kotlin side of the native MIDI sequencer owned by the [RackEngine]: it records the notes played on
 * the rack and plays them back in a loop, in time with the audio. Events and timing live in native code; this
 * class only drives it. Thread-safe.
 */
class MidiSequencer internal constructor() {
    companion object {
        /** Ticks per quarter note. */
        const val PPQ = 960
        const val BEATS_PER_BAR = 4
        const val TICKS_PER_BAR = PPQ * BEATS_PER_BAR
        const val DEFAULT_BPM = 120.0
        const val MIN_BPM = 20.0
        const val MAX_BPM = 300.0
        const val AUTO_LENGTH_BARS = 0
        /** 1/16 */
        const val DEFAULT_QUANTIZE_TICKS = PPQ / 4

        /** Packed events ([getEvents], [setEvents]): tick, slot, take, word count, then 4 UMP words. */
        const val EVENT_TICK = 0
        const val EVENT_SLOT = 1
        const val EVENT_TAKE = 2
        const val EVENT_WORDS = 4
        const val EVENT_STRIDE = 8

        // Layouts shared with MidiSequencerJni.cpp
        private const val SETTINGS_BPM = 0
        private const val SETTINGS_LENGTH_BARS = 1
        private const val SETTINGS_IS_QUANTIZING = 2
        private const val SETTINGS_QUANTIZE_TICKS = 3
        private const val SETTINGS_SIZE = 4

        private const val STATUS_STATE = 0
        private const val STATUS_IS_RECORDING = 1
        private const val STATUS_LENGTH_TICKS = 2
        private const val STATUS_AUTO_LENGTH_BARS = 3
        private const val STATUS_EVENT_COUNT = 4
        private const val STATUS_REVISION = 5
        private const val STATUS_SIZE = 6

        private fun Boolean.toDouble(): Double {
            return if (this) {
                1.0
            } else {
                0.0
            }
        }
    }

    var settings: SequencerSettings
        get() {
            val values = DoubleArray(SETTINGS_SIZE)
            nativeGetSettings(values)
            return SequencerSettings(
                bpm = values[SETTINGS_BPM],
                lengthBars = values[SETTINGS_LENGTH_BARS].toInt(),
                isQuantizing = values[SETTINGS_IS_QUANTIZING] != 0.0,
                quantizeTicks = values[SETTINGS_QUANTIZE_TICKS].toInt()
            )
        }
        set(value) {
            nativeSetSettings(
                doubleArrayOf(
                    value.bpm,
                    value.lengthBars.toDouble(),
                    value.isQuantizing.toDouble(),
                    value.quantizeTicks.toDouble()
                )
            )
        }

    /** Also publishes what was recorded since the last read: poll it regularly. */
    val status: SequencerStatus
        get() {
            val values = LongArray(STATUS_SIZE)
            nativeGetStatus(values)
            return SequencerStatus(
                state = TransportState.entries.getOrElse(values[STATUS_STATE].toInt()) { TransportState.STOPPED },
                isRecording = values[STATUS_IS_RECORDING] != 0L,
                lengthTicks = values[STATUS_LENGTH_TICKS],
                autoLengthBars = values[STATUS_AUTO_LENGTH_BARS].toInt(),
                eventCount = values[STATUS_EVENT_COUNT].toInt(),
                revision = values[STATUS_REVISION].toInt()
            )
        }

    /** Tick of the playhead while playing, 0 otherwise. Lock-free. */
    external fun getPositionTick(): Long

    /** Plays from the start. Ends recording. The engine must be running. */
    external fun startPlayback()

    /**
     * When stopped, starts recording at the first note played; when playing, punches in. The
     * metronome clicks while recording. The engine must be running.
     */
    external fun startRecording()

    /** Punches out: playback continues. Armed, it stops the transport. */
    external fun stopRecording()

    /** Stops the transport and ends recording. */
    external fun stop()

    /** Removes the last take. While recording, discards what the take in progress recorded (or the previous take if it is empty): recording goes on. */
    external fun undoLastTake()

    external fun clear()

    /**
     * The sequence in recording order, [EVENT_STRIDE] ints per event: each note-off follows its
     * note-on, even when notes of several loop passes overlap in time.
     */
    external fun getEvents(): IntArray

    /**
     * Replaces the sequence with events packed like [getEvents], and stops the transport.
     * [autoLengthBars]: the loop length while the length setting is AUTO, as in [SequencerStatus]; 0 fits the events.
     */
    external fun setEvents(packed: IntArray, autoLengthBars: Int)

    /** The sequence as a Standard MIDI File, one track per slot. */
    external fun exportStandardMidiFile(): ByteArray

    /** Replaces the sequence and tempo with a Standard MIDI File. Returns false if it cannot be read. */
    external fun importStandardMidiFile(data: ByteArray): Boolean

    private external fun nativeSetSettings(values: DoubleArray)

    private external fun nativeGetSettings(out: DoubleArray)

    private external fun nativeGetStatus(out: LongArray)
}
