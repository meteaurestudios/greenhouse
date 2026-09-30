#pragma once

#include "engine/OboeEngine.h"
#include "sequencer/Metronome.h"
#include "sequencer/Sequence.h"
#include <array>
#include <atomic>
#include <mutex>
#include <vector>

namespace aaphost
{

// Events the sequencer can send to one slot in one block, and the words they take with their JR timestamps
constexpr int32_t MAX_SEQUENCER_EVENTS_PER_BLOCK = 256;
constexpr int32_t MAX_SLOT_EVENT_WORDS = MAX_SEQUENCER_EVENTS_PER_BLOCK * (1 + ump::MAX_PACKET_WORDS);
constexpr uint32_t TRANSPORT_COMMAND_CAPACITY = 32;
// While recording, a note played after this long with no key held starts a new phrase (the unit of undo)
constexpr int64_t PHRASE_GAP_TICKS = SEQUENCER_TICKS_PER_BAR;

enum class TransportState : int32_t
{
    STOPPED = 0,
    ARMED = 1, // recording, waiting for the first note to start
    PLAYING = 2
};

/** What the UI shows of the sequencer. */
struct SequencerStatus
{
    TransportState mState{TransportState::STOPPED};
    bool mIsRecording{false};
    int64_t mLengthTicks{SEQUENCER_TICKS_PER_BAR}; // while a take sets the length: the bars recorded so far
    int32_t mAutoLengthBars{0}; // length a take set while the length is AUTO_LENGTH_BARS, 0 if none
    int32_t mEventCount{0};
    int32_t mRevision{0};
};

/** One bit per MIDI note key (group, channel, note). */
class NoteSet
{
public:
    void set(int32_t key)
    {
        mWords[key / BITS_PER_WORD] |= bit(key);
    }

    void reset(int32_t key)
    {
        mWords[key / BITS_PER_WORD] &= ~bit(key);
    }

    bool test(int32_t key) const
    {
        return (mWords[key / BITS_PER_WORD] & bit(key)) != 0;
    }

    void clear()
    {
        mWords.fill(0);
    }

    template <typename Function>
    void forEach(Function&& function) const
    {
        for (int32_t w = 0; w < WORD_COUNT; w++) {
            auto word = mWords[w];

            while (word != 0) {
                function(w * BITS_PER_WORD + __builtin_ctzll(word));
                word &= word - 1;
            }
        }
    }

private:
    static constexpr int32_t BITS_PER_WORD = 64;
    static constexpr int32_t WORD_COUNT = ump::NOTE_KEY_COUNT / BITS_PER_WORD;

    static uint64_t bit(int32_t key)
    {
        return uint64_t{1} << (key % BITS_PER_WORD);
    }

    std::array<uint64_t, WORD_COUNT> mWords{};
};

/** Notes started and not ended yet, remembering their protocol to end them with a matching note-off. */
class HeldNotes
{
public:
    /** False for a note-off of a note that is not held: it has no note-on to end. */
    bool accepts(const ump::Packet& packet) const
    {
        return ump::classify(packet) != ump::EventKind::NOTE_OFF || mNotes.test(ump::getNoteKey(packet.mWords[0]));
    }

    /** Tracks a note-on or note-off that was sent. */
    void track(const ump::Packet& packet)
    {
        auto kind = ump::classify(packet);
        auto key = ump::getNoteKey(packet.mWords[0]);

        if (kind == ump::EventKind::NOTE_ON) {
            mCount += mNotes.test(key) ? 0 : 1;
            mNotes.set(key);

            if (ump::isMidi1(packet.mWords[0])) {
                mMidi1Notes.set(key);
            } else {
                mMidi1Notes.reset(key);
            }
        } else if (kind == ump::EventKind::NOTE_OFF && mNotes.test(key)) {
            mNotes.reset(key);
            mCount--;
        }
    }

    /**
     * For a note-on of a note already held (e.g. the same pitch overlapping from another take), the
     * note-off that ends it first: each note-on the plugin gets then has its note-off.
     */
    bool getRetriggerNoteOff(const ump::Packet& packet, ump::Packet& noteOff) const
    {
        auto key = ump::getNoteKey(packet.mWords[0]);

        if (ump::classify(packet) != ump::EventKind::NOTE_ON || !mNotes.test(key)) {
            return false;
        }

        noteOff = ump::makeNoteOff(key, mMidi1Notes.test(key));
        return true;
    }

    /** Calls function with a note-off for each held note, then forgets them. */
    template <typename Function>
    void release(Function&& function)
    {
        if (mCount == 0) {
            return;
        }

        mNotes.forEach([&](int32_t key) {
            function(ump::makeNoteOff(key, mMidi1Notes.test(key)));
        });
        clear();
    }

    bool isEmpty() const
    {
        return mCount == 0;
    }

    void clear()
    {
        mNotes.clear();
        mMidi1Notes.clear();
        mCount = 0;
    }

private:
    NoteSet mNotes;
    NoteSet mMidi1Notes;
    int32_t mCount{0};
};

/**
 * MIDI sequencer of the rack: records the notes played live on the slots and plays them back in a loop, in
 * time with the audio. Events are sent to the plugins with sample-accurate JR timestamps, written
 * straight into their MIDI2 input port by the audio thread.
 *
 * Threading: the control methods may be called from any non-realtime thread and are serialized by
 * mMutex. The audio thread never locks: it reads the sequence from an immutable PlaybackBuffer
 * published through an atomic pointer (old buffers are freed once the audio thread has moved on)
 * and receives transport commands through a lock-free queue.
 */
class MidiSequencer
{
public:
    explicit MidiSequencer(const OboeEngine& engine);
    ~MidiSequencer();

    MidiSequencer(const MidiSequencer&) = delete;
    MidiSequencer& operator=(const MidiSequencer&) = delete;

    // -- Control thread --

    void setSettings(const SequencerSettings& settings);
    SequencerSettings getSettings();

    /** Plays from the start. Ends recording. */
    void startPlayback();
    /** When stopped, starts at the first note played; when playing, punches in. The metronome clicks while recording. */
    void startRecording();
    /** Punches out: playback continues. Armed, it stops the transport. */
    void stopRecording();
    /** Stops the transport and ends recording. */
    void stop();
    /**
     * Removes the last take. While recording, a take is a phrase (notes played with less than
     * PHRASE_GAP_TICKS of silence between them): undo discards the phrase in progress, or the
     * previous take if it is empty, and recording goes on.
     */
    void undoLastTake();
    void clear();

    /** Records the notes of input sent live to a slot, if recording. */
    void recordInput(int32_t slotIndex, const uint8_t* data, int32_t size);

    /** Also publishes what was recorded since the last call: the UI polls it. */
    SequencerStatus getStatus();
    /** Tick the next block starts at while playing, 0 otherwise. Lock-free. */
    int64_t getPositionTick() const;

    /**
     * The sequence in recording order: each note-off follows its note-on, even when notes of several
     * loop passes overlap in time. setEvents() keeps the order.
     */
    std::vector<SequenceEvent> getEvents();
    /**
     * Replaces the sequence (e.g. from a saved session) and stops the transport. autoLengthBars: the
     * loop length while the length setting is AUTO_LENGTH_BARS (0: fit the events).
     */
    void setEvents(std::vector<SequenceEvent> events, int32_t autoLengthBars);

    /** The sequence as it plays (quantized when quantize is on), as a Standard MIDI File. */
    std::vector<uint8_t> exportStandardMidiFile();
    /** Replaces the sequence and tempo with a Standard MIDI File. Returns false if it cannot be read. */
    bool importStandardMidiFile(const uint8_t* data, int32_t size);

    /** Empty sequence, default settings. The audio thread must be idle (stream closed). */
    void reset();
    /** The stream stopped and the audio thread is idle: stops the transport and ends recording. */
    void onStreamStopped();

    // -- Audio thread --

    /** Works out the events and metronome clicks of the next block. Call before rendering the slots. */
    void beginBlock(int32_t frames, int32_t sampleRate);

    /** UMP words (JR timestamps and events) for a slot's MIDI2 input in the current block. */
    const uint32_t* getSlotEvents(int32_t slotIndex, int32_t& wordCount) const;

    /** Adds note-offs for the notes played on a slot to its events of the current block, e.g. before the slot is bypassed. */
    void releaseSlotNotes(int32_t slotIndex);

    /** Most UMP words a slot's MIDI2 input takes in one block, set when its plugin changes. Any thread. */
    void setSlotEventCapacity(int32_t slotIndex, int32_t wordCount);

    void renderMetronome(float* interleaved, int32_t frames);

private:
    enum class Command : int32_t
    {
        PLAY,
        ARM,
        ROLL, // the first note of an armed recording arrived
        STOP
    };

    /** What the audio thread plays: never changed once published. */
    struct PlaybackBuffer
    {
        std::vector<SequenceEvent> mEvents; // in time order, quantized
        int64_t mLengthTicks{SEQUENCER_TICKS_PER_BAR};
        bool mIsTakeSettingLength{false}; // plays on past the length instead of looping
        // First take (phrase) of the recording in progress, NO_TAKE if none: it and the later ones are being recorded
        int32_t mFirstRecordingTake{NO_TAKE};
        // Changes when held notes must be released because events moved or went away
        uint64_t mFlushSerial{0};
    };

    struct RetiredBuffer
    {
        PlaybackBuffer* mBuffer{nullptr};
        uint64_t mMarker{0};
    };

    struct RecordPosition
    {
        int32_t mPass{0};
        int64_t mTick{0};
    };

    // Control thread, mMutex held
    void commitChangeLocked(bool releaseHeldNotes);
    void publishLocked(bool releaseHeldNotes);
    void sweepRetiredLocked();
    std::vector<SequenceEvent> buildPlaybackEventsLocked(int64_t lengthTicks) const;
    int64_t getLengthTicksLocked() const;
    void commitTakeLocked();
    void endHeldNotesAtLoopEndLocked(int64_t previousLengthTicks);
    void endTakeLocked();
    void eraseTakeLocked(int32_t take);
    void eventsRemovedLocked();
    void stopLocked();
    void resetAudioStateLocked();
    void pushCommandLocked(Command command);
    bool hasPendingCommandsLocked() const;
    TransportState getRequestedStateLocked() const;
    bool isAwaitingFirstNoteLocked() const;
    RecordPosition getRecordPositionLocked() const;
    int64_t getRecordedTicksLocked(const RecordPosition& position) const;
    void startPhraseIfPausedLocked(const RecordPosition& position);

    // Audio thread
    void processCommands();
    void resetTransport(TransportState state);
    void advance(const PlaybackBuffer& buffer, int32_t frames);
    void emitEvents(const PlaybackBuffer& buffer, int64_t segmentStart, int64_t segmentEnd, int32_t blockOffset);
    void scheduleClicks(int64_t segmentStart, int64_t segmentEnd, int32_t blockOffset);
    bool writeEvent(int32_t slotIndex, int32_t frameOffset, const ump::Packet& packet);
    void releaseHeldNotes();
    int64_t tickToFrame(int64_t tick) const;

    const OboeEngine& mEngine;

    // -- Control state, guarded by mMutex --
    std::mutex mMutex;
    SequencerSettings mSettings;
    std::vector<SequenceEvent> mEvents; // in recording order
    // Loop length the first take set while the length setting is AUTO_LENGTH_BARS; 0 until then
    int32_t mAutoLengthBars{0};
    int32_t mLastTake{NO_TAKE};
    // Take of the phrase being recorded, and the first one of this recording (NO_TAKE when not recording)
    int32_t mRecordingTake{NO_TAKE};
    int32_t mFirstRecordingTake{NO_TAKE};
    // When the last recorded note ended, in ticks played since the start of the transport (see getRecordedTicksLocked())
    int64_t mLastNoteEndTicks{0};
    bool mIsTakeSettingLength{false};
    // Recorded events wait for the next getStatus() to be published: they only play from the next pass
    bool mIsPlaybackStale{false};
    // The transport state once the pending commands are taken
    TransportState mRequestedState{TransportState::STOPPED};
    // Notes the current take recorded a note-on for and no note-off yet, per slot
    std::array<HeldNotes, MAX_SEQUENCER_SLOTS> mRecordedNotes;
    uint64_t mFlushSerial{0};
    std::vector<RetiredBuffer> mRetired;
    int32_t mRevision{0};

    // -- Shared with the audio thread --
    std::atomic<PlaybackBuffer*> mPublished{nullptr};
    std::atomic<double> mBpm{DEFAULT_SEQUENCER_BPM};
    // Linear, from the level in dB (set from the default settings on construction)
    std::atomic<float> mMetronomeGain{METRONOME_SILENT_GAIN};
    // Single-producer (control thread, under mMutex), single-consumer (audio thread) queue
    std::array<Command, TRANSPORT_COMMAND_CAPACITY> mCommands{};
    std::atomic<uint32_t> mCommandWrite{0};
    std::atomic<uint32_t> mCommandRead{0};
    // Written by the audio thread after each block: where the next block starts
    std::atomic<int32_t> mPublishedState{static_cast<int32_t>(TransportState::STOPPED)};
    std::atomic<uint64_t> mPublishedPlayhead{0};
    // Room in each slot's MIDI2 input, in words (set by the engine when the slot's plugin changes)
    std::array<std::atomic<int32_t>, MAX_SEQUENCER_SLOTS> mSlotWordCapacities;

    // -- Audio thread only (reset by the control thread while it is idle) --
    TransportState mState{TransportState::STOPPED};
    int64_t mPositionFrames{0};
    int32_t mPass{0};
    double mFramesPerTick{0.0};
    int32_t mBlockSampleRate{DEFAULT_SAMPLE_RATE};
    uint64_t mAppliedFlushSerial{0};
    // Notes the sequencer started and has not ended yet, per slot
    std::array<HeldNotes, MAX_SEQUENCER_SLOTS> mPlayingNotes;
    std::array<std::array<uint32_t, MAX_SLOT_EVENT_WORDS>, MAX_SEQUENCER_SLOTS> mSlotWords{};
    std::array<int32_t, MAX_SEQUENCER_SLOTS> mSlotWordCounts{};
    std::array<int32_t, MAX_SEQUENCER_SLOTS> mSlotEventCounts{};
    std::array<uint32_t, MAX_SEQUENCER_SLOTS> mSlotLastJrTicks{};
    Metronome mMetronome;
};

} // namespace aaphost
