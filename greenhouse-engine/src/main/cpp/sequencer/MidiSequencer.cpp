#include "sequencer/MidiSequencer.h"
#include "sequencer/StandardMidiFile.h"
#include "utils/Logging.h"
#include <cmath>
#include <limits>
#include <unordered_map>

namespace greenhouse
{

namespace
{

// The published playhead packs the loop pass above the tick position
constexpr uint32_t PLAYHEAD_TICK_BITS = 40;
constexpr uint64_t PLAYHEAD_TICK_MASK = (uint64_t{1} << PLAYHEAD_TICK_BITS) - 1;

uint64_t packPlayhead(int32_t pass, int64_t tick)
{
    return (static_cast<uint64_t>(pass) << PLAYHEAD_TICK_BITS) | (static_cast<uint64_t>(std::max<int64_t>(tick, 0)) & PLAYHEAD_TICK_MASK);
}

int32_t unpackPass(uint64_t playhead)
{
    return static_cast<int32_t>(playhead >> PLAYHEAD_TICK_BITS);
}

int64_t unpackTick(uint64_t playhead)
{
    return static_cast<int64_t>(playhead & PLAYHEAD_TICK_MASK);
}

// Amplitude ratio: 20 dB per decade
constexpr float DECIBELS_PER_DECADE = 20.0f;
constexpr float DECIBEL_BASE = 10.0f;

/** Linear gain of a metronome level in dB: the lowest level mutes it. */
float metronomeGain(float levelDb)
{
    if (levelDb <= MIN_METRONOME_LEVEL_DB) {
        return METRONOME_SILENT_GAIN;
    }

    return std::pow(DECIBEL_BASE, levelDb / DECIBELS_PER_DECADE);
}

double framesPerTick(int32_t sampleRate, double bpm)
{
    return static_cast<double>(sampleRate) * SECONDS_PER_MINUTE / (bpm * SEQUENCER_PPQ);
}

bool isValidSlot(int32_t slotIndex)
{
    return slotIndex >= 0 && slotIndex < MAX_SEQUENCER_SLOTS;
}

/** Last tick an event needs in the loop: a note-off may sit on the loop end. */
int64_t getContentTick(const SequenceEvent& event)
{
    if (ump::classify(event.mPacket) == ump::EventKind::NOTE_OFF) {
        return std::max<int64_t>(event.mTick - 1, 0);
    }

    return event.mTick;
}

int64_t getNoteKeyOfSlot(int32_t slotIndex, const ump::Packet& packet)
{
    return static_cast<int64_t>(slotIndex) * ump::NOTE_KEY_COUNT + ump::getNoteKey(packet.mWords[0]);
}

} // namespace

MidiSequencer::MidiSequencer(const OboeEngine& engine) : mEngine(engine)
{
    for (auto& capacity : mSlotWordCapacities) {
        capacity.store(MAX_SLOT_EVENT_WORDS, std::memory_order_relaxed);
    }

    mMetronomeGain.store(metronomeGain(mSettings.mMetronomeLevelDb), std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(mMutex);
    publishLocked(false);
}

MidiSequencer::~MidiSequencer()
{
    // The owner closed the stream first: the audio thread no longer reads the buffers
    delete mPublished.exchange(nullptr);

    for (auto& retired : mRetired) {
        delete retired.mBuffer;
    }
}

// ---------------------------------------------------------------------------------------------
// Control thread
// ---------------------------------------------------------------------------------------------

void MidiSequencer::setSettings(const SequencerSettings& settings)
{
    std::lock_guard<std::mutex> lock(mMutex);

    auto clamped = settings;
    clamped.mBpm = std::clamp(settings.mBpm, MIN_SEQUENCER_BPM, MAX_SEQUENCER_BPM);
    clamped.mLengthBars = std::clamp(settings.mLengthBars, AUTO_LENGTH_BARS, MAX_SEQUENCE_LENGTH_BARS);
    clamped.mQuantizeTicks = std::clamp(settings.mQuantizeTicks, 1, static_cast<int32_t>(SEQUENCER_TICKS_PER_BAR));
    clamped.mMetronomeLevelDb = std::clamp(settings.mMetronomeLevelDb, MIN_METRONOME_LEVEL_DB, MAX_METRONOME_LEVEL_DB);

    // The metronome level only changes how loud the next clicks are: the sequence is untouched, and
    // not republished. It is a saved setting all the same: the revision marks the session modified.
    if (clamped.mMetronomeLevelDb != mSettings.mMetronomeLevelDb) {
        mSettings.mMetronomeLevelDb = clamped.mMetronomeLevelDb;
        mMetronomeGain.store(metronomeGain(clamped.mMetronomeLevelDb), std::memory_order_relaxed);
        mRevision++;
    }

    // Moved or unreachable events could leave notes hanging: they are released
    auto isTimingChanged = clamped.mLengthBars != mSettings.mLengthBars || clamped.mIsQuantizing != mSettings.mIsQuantizing ||
                           clamped.mQuantizeTicks != mSettings.mQuantizeTicks;

    if (!isTimingChanged && clamped.mBpm == mSettings.mBpm) {
        return;
    }

    auto previousLengthTicks = getLengthTicksLocked();
    mSettings = clamped;
    mBpm.store(clamped.mBpm, std::memory_order_relaxed);
    auto lengthTicks = getLengthTicksLocked();

    if (lengthTicks > previousLengthTicks) {
        endHeldNotesAtLoopEndLocked(previousLengthTicks);
    }

    // Choosing a length while a take is setting it ends that
    if (clamped.mLengthBars != AUTO_LENGTH_BARS) {
        mIsTakeSettingLength = false;
    }

    commitChangeLocked(isTimingChanged);
}

SequencerSettings MidiSequencer::getSettings()
{
    std::lock_guard<std::mutex> lock(mMutex);
    return mSettings;
}

void MidiSequencer::startPlayback()
{
    std::lock_guard<std::mutex> lock(mMutex);
    commitTakeLocked();
    pushCommandLocked(Command::PLAY);
}

void MidiSequencer::startRecording()
{
    std::lock_guard<std::mutex> lock(mMutex);

    if (mRecordingTake != NO_TAKE) {
        return;
    }

    // Stopped: start from the top at the first note. Playing: punch in.
    if (getRequestedStateLocked() == TransportState::STOPPED) {
        pushCommandLocked(Command::ARM);
    }

    mRecordingTake = ++mLastTake;
    mFirstRecordingTake = mRecordingTake;
    // In AUTO, the first take sets the length; later takes loop over it
    mIsTakeSettingLength = mSettings.mLengthBars == AUTO_LENGTH_BARS && mEvents.empty();
    publishLocked(false);
}

void MidiSequencer::stopRecording()
{
    std::lock_guard<std::mutex> lock(mMutex);

    // Armed, nothing plays yet: without a take to wait for, the transport stops
    if (isAwaitingFirstNoteLocked()) {
        pushCommandLocked(Command::STOP);
    }

    commitTakeLocked();
}

void MidiSequencer::stop()
{
    std::lock_guard<std::mutex> lock(mMutex);
    stopLocked();
}

void MidiSequencer::stopLocked()
{
    commitTakeLocked();
    pushCommandLocked(Command::STOP);
}

void MidiSequencer::undoLastTake()
{
    std::lock_guard<std::mutex> lock(mMutex);

    // While recording: discard what the take in progress recorded, or the previous take if nothing
    // was played yet. Recording goes on.
    if (mRecordingTake != NO_TAKE) {
        auto eventCount = mEvents.size();
        eraseTakeLocked(mRecordingTake);

        if (mEvents.size() != eventCount) {
            // The notes still held are no longer part of the take
            for (auto& notes : mRecordedNotes) {
                notes.clear();
            }

            eventsRemovedLocked();
            return;
        }
    }

    if (mEvents.empty()) {
        return;
    }

    auto lastTake = std::max_element(mEvents.begin(), mEvents.end(), [](const SequenceEvent& a, const SequenceEvent& b) {
        return a.mTake < b.mTake;
    })->mTake;

    eraseTakeLocked(lastTake);
    eventsRemovedLocked();
}

void MidiSequencer::clear()
{
    std::lock_guard<std::mutex> lock(mMutex);

    if (mRecordingTake != NO_TAKE || mEvents.empty()) {
        return;
    }

    mEvents.clear();
    eventsRemovedLocked();
}

void MidiSequencer::recordInput(int32_t slotIndex, const uint8_t* data, int32_t size)
{
    if (!isValidSlot(slotIndex) || data == nullptr || size <= 0) {
        return;
    }

    std::lock_guard<std::mutex> lock(mMutex);

    if (mRecordingTake == NO_TAKE) {
        return;
    }

    auto offset = 0;
    ump::Packet packet;

    while (ump::readPacket(data, size, offset, packet)) {
        auto kind = ump::classify(packet);

        if (kind == ump::EventKind::NONE) {
            continue;
        }

        // An armed recording starts with the first note, which lands on the first beat
        if (isAwaitingFirstNoteLocked()) {
            if (kind != ump::EventKind::NOTE_ON) {
                continue;
            }

            pushCommandLocked(Command::ROLL);
        }

        // Notes started before recording are not part of the take
        if (!mRecordedNotes[slotIndex].accepts(packet)) {
            continue;
        }

        if (static_cast<int32_t>(mEvents.size()) >= MAX_SEQUENCE_EVENTS) {
            LOGW("Sequence is full (%d events): input not recorded", MAX_SEQUENCE_EVENTS);
            break;
        }

        auto position = getRecordPositionLocked();

        if (kind == ump::EventKind::NOTE_ON) {
            startPhraseIfPausedLocked(position);
        }

        mRecordedNotes[slotIndex].track(packet);
        mEvents.push_back(SequenceEvent{position.mTick, slotIndex, mRecordingTake, position.mPass, packet});

        if (kind == ump::EventKind::NOTE_OFF) {
            mLastNoteEndTicks = getRecordedTicksLocked(position);
        }

        mIsPlaybackStale = true;
        mRevision++;
    }
}

SequencerStatus MidiSequencer::getStatus()
{
    std::lock_guard<std::mutex> lock(mMutex);

    if (mIsPlaybackStale) {
        publishLocked(false);
    }

    sweepRetiredLocked();

    SequencerStatus status;
    status.mState = getRequestedStateLocked();
    status.mIsRecording = mRecordingTake != NO_TAKE;
    status.mLengthTicks = mPublished.load(std::memory_order_relaxed)->mLengthTicks;

    if (mIsTakeSettingLength) {
        status.mLengthTicks = std::max(status.mLengthTicks, (getPositionTick() / SEQUENCER_TICKS_PER_BAR + 1) * SEQUENCER_TICKS_PER_BAR);
    }

    status.mAutoLengthBars = mAutoLengthBars;
    status.mEventCount = static_cast<int32_t>(mEvents.size());
    status.mRevision = mRevision;
    return status;
}

int64_t MidiSequencer::getPositionTick() const
{
    return unpackTick(mPublishedPlayhead.load(std::memory_order_relaxed));
}

std::vector<SequenceEvent> MidiSequencer::getEvents()
{
    std::lock_guard<std::mutex> lock(mMutex);
    return mEvents;
}

void MidiSequencer::setEvents(std::vector<SequenceEvent> events, int32_t autoLengthBars)
{
    std::lock_guard<std::mutex> lock(mMutex);
    stopLocked();

    events.erase(std::remove_if(events.begin(), events.end(), [](const SequenceEvent& event) {
        return !isValidSlot(event.mSlot) || event.mTick < 0 || event.mPacket.mWordCount <= 0 ||
               event.mPacket.mWordCount != ump::getWordCount(event.mPacket.mWords[0]);
    }), events.end());

    if (static_cast<int32_t>(events.size()) > MAX_SEQUENCE_EVENTS) {
        events.resize(MAX_SEQUENCE_EVENTS);
    }

    mLastTake = NO_TAKE;

    for (auto& event : events) {
        event.mRecordedPass = 0;
        mLastTake = std::max(mLastTake, event.mTake);
    }

    mEvents = std::move(events);
    mAutoLengthBars = mEvents.empty() ? 0 : std::clamp(autoLengthBars, 0, MAX_SEQUENCE_LENGTH_BARS);
    commitChangeLocked(true);
}

std::vector<uint8_t> MidiSequencer::exportStandardMidiFile()
{
    std::lock_guard<std::mutex> lock(mMutex);
    // As it plays: quantized when quantize is on
    auto lengthTicks = getLengthTicksLocked();
    return smf::write(buildPlaybackEventsLocked(lengthTicks), mSettings.mBpm, lengthTicks);
}

bool MidiSequencer::importStandardMidiFile(const uint8_t* data, int32_t size)
{
    smf::ImportedSequence imported;

    if (data == nullptr || size <= 0 || !smf::read(data, size, MAX_SEQUENCER_SLOTS, imported)) {
        return false;
    }

    std::lock_guard<std::mutex> lock(mMutex);
    stopLocked();

    mEvents = std::move(imported.mEvents);
    mLastTake = mEvents.empty() ? NO_TAKE : 0;
    mSettings.mBpm = imported.mBpm;
    mBpm.store(imported.mBpm, std::memory_order_relaxed);
    // The length setting stays: in AUTO, the loop fits the file
    mAutoLengthBars = 0;
    commitChangeLocked(true);
    return true;
}

void MidiSequencer::reset()
{
    std::lock_guard<std::mutex> lock(mMutex);
    endTakeLocked();
    mEvents.clear();
    mLastTake = NO_TAKE;
    mAutoLengthBars = 0;
    mSettings = SequencerSettings{};
    mBpm.store(mSettings.mBpm, std::memory_order_relaxed);
    mMetronomeGain.store(metronomeGain(mSettings.mMetronomeLevelDb), std::memory_order_relaxed);
    resetAudioStateLocked();
    commitChangeLocked(false);
}

void MidiSequencer::onStreamStopped()
{
    std::lock_guard<std::mutex> lock(mMutex);
    commitTakeLocked();
    resetAudioStateLocked();
}

/** The audio thread is idle: its state is reset directly. Its plugins were deactivated, so held notes need no note-off. */
void MidiSequencer::resetAudioStateLocked()
{
    mCommandRead.store(mCommandWrite.load(std::memory_order_relaxed), std::memory_order_release);
    mRequestedState = TransportState::STOPPED;
    mState = TransportState::STOPPED;
    mPositionFrames = 0;
    mPass = 0;

    for (auto& notes : mPlayingNotes) {
        notes.clear();
    }

    mMetronome.reset();
    mPublishedState.store(static_cast<int32_t>(TransportState::STOPPED), std::memory_order_release);
    mPublishedPlayhead.store(0, std::memory_order_relaxed);
}

/** Publishes a change of the sequence or the settings, which the UI sees through the revision. */
void MidiSequencer::commitChangeLocked(bool releaseHeldNotes)
{
    publishLocked(releaseHeldNotes);
    mRevision++;
}

void MidiSequencer::publishLocked(bool releaseHeldNotes)
{
    auto buffer = new PlaybackBuffer();
    buffer->mLengthTicks = getLengthTicksLocked();
    buffer->mEvents = buildPlaybackEventsLocked(buffer->mLengthTicks);
    buffer->mFirstRecordingTake = mFirstRecordingTake;
    buffer->mIsTakeSettingLength = mIsTakeSettingLength;

    if (releaseHeldNotes) {
        mFlushSerial++;
    }

    buffer->mFlushSerial = mFlushSerial;
    mIsPlaybackStale = false;

    auto previous = mPublished.exchange(buffer);

    if (previous != nullptr) {
        mRetired.push_back(RetiredBuffer{previous, mEngine.getAudioThreadMarker()});
    }

    sweepRetiredLocked();
}

void MidiSequencer::sweepRetiredLocked()
{
    mRetired.erase(std::remove_if(mRetired.begin(), mRetired.end(), [this](const RetiredBuffer& retired) {
        if (!mEngine.hasAudioThreadPassed(retired.mMarker)) {
            return false;
        }

        delete retired.mBuffer;
        return true;
    }), mRetired.end());
}

/**
 * The events in time order, with the quantize grid applied to note starts (note ends move with their
 * starts, paired in recording order). In a loop, note ends at or past the loop end are played just before it.
 */
std::vector<SequenceEvent> MidiSequencer::buildPlaybackEventsLocked(int64_t lengthTicks) const
{
    auto events = mEvents;

    auto grid = mSettings.mIsQuantizing ? static_cast<int64_t>(mSettings.mQuantizeTicks) : 0;

    // How far a quantized note-on moved, for its note-off to move as far
    struct NoteShift
    {
        int64_t mTicks{0};
        bool mIsWrapped{false}; // quantized onto the loop end: it plays from the start
    };

    std::unordered_map<int64_t, NoteShift> noteShifts;

    for (auto& event : events) {
        auto kind = ump::classify(event.mPacket);

        if (kind != ump::EventKind::NOTE_ON && kind != ump::EventKind::NOTE_OFF) {
            continue;
        }

        auto originalTick = event.mTick;

        if (grid > 0 && kind == ump::EventKind::NOTE_ON) {
            auto quantized = std::llround(static_cast<double>(event.mTick) / static_cast<double>(grid)) * grid;
            auto isWrapped = quantized >= lengthTicks && originalTick < lengthTicks;
            noteShifts[getNoteKeyOfSlot(event.mSlot, event.mPacket)] = NoteShift{quantized - event.mTick, isWrapped};
            event.mTick = quantized;
        } else if (grid > 0) {
            auto shift = noteShifts.find(getNoteKeyOfSlot(event.mSlot, event.mPacket));

            if (shift != noteShifts.end()) {
                event.mTick = std::max<int64_t>(event.mTick + shift->second.mTicks, 0);

                // Released in the pass its note-on wrapped from: it wraps with it, and the note stays as long
                if (shift->second.mIsWrapped && event.mTick >= lengthTicks) {
                    event.mTick -= lengthTicks;
                }

                noteShifts.erase(shift);
            }
        }

        if (event.mTick < lengthTicks) {
            continue;
        }

        if (kind == ump::EventKind::NOTE_OFF) {
            event.mTick = lengthTicks - 1;
        } else if (originalTick < lengthTicks) {
            // Quantized past the loop end: it belongs at the start of the loop
            event.mTick -= lengthTicks;
        }
    }

    sortByTick(events);
    return events;
}

/** In AUTO before a take set the length: whole bars holding every event, at least one. */
int64_t MidiSequencer::getLengthTicksLocked() const
{
    if (mSettings.mLengthBars != AUTO_LENGTH_BARS) {
        return mSettings.mLengthBars * SEQUENCER_TICKS_PER_BAR;
    }

    if (mAutoLengthBars > 0) {
        return mAutoLengthBars * SEQUENCER_TICKS_PER_BAR;
    }

    int64_t contentEnd = 0;

    for (const auto& event : mEvents) {
        contentEnd = std::max(contentEnd, getContentTick(event));
    }

    return (contentEnd / SEQUENCER_TICKS_PER_BAR + 1) * SEQUENCER_TICKS_PER_BAR;
}

/** Ends the current take: closes the notes still held, and sets the loop length if the take was setting it. */
void MidiSequencer::commitTakeLocked()
{
    if (mRecordingTake == NO_TAKE) {
        return;
    }

    auto take = mRecordingTake;
    auto position = getRecordPositionLocked();

    for (int32_t slot = 0; slot < MAX_SEQUENCER_SLOTS; slot++) {
        mRecordedNotes[slot].release([&](const ump::Packet& noteOff) {
            if (static_cast<int32_t>(mEvents.size()) < MAX_SEQUENCE_EVENTS) {
                mEvents.push_back(SequenceEvent{position.mTick, slot, take, position.mPass, noteOff});
            }
        });
    }

    // Every phrase of the recording
    auto firstTake = mFirstRecordingTake;
    auto hasTakeEvents = std::any_of(mEvents.begin(), mEvents.end(), [firstTake](const SequenceEvent& event) {
        return event.mTake >= firstTake;
    });

    // Like a looper: the loop is as many bars as were recorded, rounded to the nearest bar.
    // What was played past it wraps to the start.
    if (mIsTakeSettingLength && hasTakeEvents) {
        auto bars = std::llround(static_cast<double>(position.mTick) / static_cast<double>(SEQUENCER_TICKS_PER_BAR));
        mAutoLengthBars = static_cast<int32_t>(std::clamp<int64_t>(bars, 1, MAX_SEQUENCE_LENGTH_BARS));
        auto lengthTicks = getLengthTicksLocked();

        for (auto& event : mEvents) {
            if (event.mTake >= firstTake && event.mTick >= lengthTicks) {
                event.mTick %= lengthTicks;
            }
        }
    }

    endTakeLocked();
    commitChangeLocked(false);
}

/**
 * Extending the loop leaves the new bars empty, for overdubs: notes held across the previous loop
 * end stop at it instead of sounding through the new bars.
 */
void MidiSequencer::endHeldNotesAtLoopEndLocked(int64_t previousLengthTicks)
{
    // Pair note-ons with their note-offs in recording order: a note-off before its note-on was held across the loop end
    std::unordered_map<int64_t, int64_t> noteOnTicks;

    for (auto& event : mEvents) {
        auto kind = ump::classify(event.mPacket);
        auto key = getNoteKeyOfSlot(event.mSlot, event.mPacket);

        if (kind == ump::EventKind::NOTE_ON) {
            noteOnTicks[key] = event.mTick;
            continue;
        }

        auto noteOn = noteOnTicks.find(key);

        if (kind != ump::EventKind::NOTE_OFF || noteOn == noteOnTicks.end()) {
            continue;
        }

        if (event.mTick < noteOn->second) {
            event.mTick = previousLengthTicks;
        }

        noteOnTicks.erase(noteOn);
    }
}

void MidiSequencer::endTakeLocked()
{
    mRecordingTake = NO_TAKE;
    mFirstRecordingTake = NO_TAKE;
    mIsTakeSettingLength = false;

    for (auto& notes : mRecordedNotes) {
        notes.clear();
    }
}

void MidiSequencer::eraseTakeLocked(int32_t take)
{
    mEvents.erase(std::remove_if(mEvents.begin(), mEvents.end(), [take](const SequenceEvent& event) {
        return event.mTake == take;
    }), mEvents.end());
}

void MidiSequencer::eventsRemovedLocked()
{
    // Removing every take lets the next one, or the one being recorded, set the length again
    if (mEvents.empty()) {
        mAutoLengthBars = 0;
        mIsTakeSettingLength = mRecordingTake != NO_TAKE && mSettings.mLengthBars == AUTO_LENGTH_BARS;
    }

    commitChangeLocked(true);
}

void MidiSequencer::pushCommandLocked(Command command)
{
    auto write = mCommandWrite.load(std::memory_order_relaxed);

    if (write - mCommandRead.load(std::memory_order_acquire) >= TRANSPORT_COMMAND_CAPACITY) {
        LOGW("Sequencer command queue full: command dropped");
        return;
    }

    mCommands[write % TRANSPORT_COMMAND_CAPACITY] = command;
    mCommandWrite.store(write + 1, std::memory_order_release);

    switch (command) {
        case Command::PLAY:
        case Command::ROLL:
            mRequestedState = TransportState::PLAYING;
            break;
        case Command::ARM:
            mRequestedState = TransportState::ARMED;
            break;
        case Command::STOP:
            mRequestedState = TransportState::STOPPED;
            break;
    }
}

bool MidiSequencer::hasPendingCommandsLocked() const
{
    return mCommandWrite.load(std::memory_order_relaxed) != mCommandRead.load(std::memory_order_acquire);
}

/** The state the transport is in, or will be once the audio thread has taken the pending commands. */
TransportState MidiSequencer::getRequestedStateLocked() const
{
    if (hasPendingCommandsLocked()) {
        return mRequestedState;
    }

    return static_cast<TransportState>(mPublishedState.load(std::memory_order_acquire));
}

bool MidiSequencer::isAwaitingFirstNoteLocked() const
{
    return mRecordingTake != NO_TAKE && getRequestedStateLocked() == TransportState::ARMED;
}

/**
 * Where live input lands in the sequence: at the start of the next block, which is when the plugin
 * receives it, so the take plays back exactly as it was heard. Input before the transport rolls
 * (armed, pending start) lands on the first beat.
 */
MidiSequencer::RecordPosition MidiSequencer::getRecordPositionLocked() const
{
    if (hasPendingCommandsLocked() || getRequestedStateLocked() != TransportState::PLAYING) {
        return RecordPosition{};
    }

    auto playhead = mPublishedPlayhead.load(std::memory_order_relaxed);
    return RecordPosition{unpackPass(playhead), unpackTick(playhead)};
}

/** Ticks played since the transport started, across loop passes: the distance between two positions. */
int64_t MidiSequencer::getRecordedTicksLocked(const RecordPosition& position) const
{
    return static_cast<int64_t>(position.mPass) * mPublished.load(std::memory_order_relaxed)->mLengthTicks + position.mTick;
}

/**
 * A note played after a pause starts a new phrase: no recorded note held, and at least
 * PHRASE_GAP_TICKS since the last one ended. An empty phrase (just started, or undone) goes on.
 */
void MidiSequencer::startPhraseIfPausedLocked(const RecordPosition& position)
{
    auto isPhraseEmpty = mEvents.empty() || mEvents.back().mTake != mRecordingTake;
    auto isNoteHeld = std::any_of(mRecordedNotes.begin(), mRecordedNotes.end(), [](const HeldNotes& notes) {
        return !notes.isEmpty();
    });

    if (isPhraseEmpty || isNoteHeld || getRecordedTicksLocked(position) - mLastNoteEndTicks < PHRASE_GAP_TICKS) {
        return;
    }

    mRecordingTake = ++mLastTake;
}

// ---------------------------------------------------------------------------------------------
// Audio thread
// ---------------------------------------------------------------------------------------------

void MidiSequencer::beginBlock(int32_t frames, int32_t sampleRate)
{
    mSlotWordCounts.fill(0);
    mSlotEventCounts.fill(0);
    mSlotLastJrTicks.fill(0);
    mBlockSampleRate = sampleRate;

    // Keep the musical position when the tempo or the device's sample rate changes
    auto framesPerTickNow = framesPerTick(sampleRate, mBpm.load(std::memory_order_relaxed));

    if (framesPerTickNow != mFramesPerTick) {
        if (mFramesPerTick > 0.0) {
            mPositionFrames = std::llround(static_cast<double>(mPositionFrames) * framesPerTickNow / mFramesPerTick);
        }

        mFramesPerTick = framesPerTickNow;
    }

    auto buffer = mPublished.load(std::memory_order_acquire);

    if (buffer->mFlushSerial != mAppliedFlushSerial) {
        mAppliedFlushSerial = buffer->mFlushSerial;
        releaseHeldNotes();
    }

    processCommands();

    if (mState == TransportState::PLAYING) {
        advance(*buffer, frames);
    }

    auto tick = mState == TransportState::PLAYING ? std::llround(static_cast<double>(mPositionFrames) / mFramesPerTick) : 0;
    mPublishedPlayhead.store(packPlayhead(mPass, tick), std::memory_order_relaxed);
    mPublishedState.store(static_cast<int32_t>(mState), std::memory_order_release);
}

void MidiSequencer::processCommands()
{
    auto read = mCommandRead.load(std::memory_order_relaxed);
    auto write = mCommandWrite.load(std::memory_order_acquire);

    for (; read != write; read++) {
        switch (mCommands[read % TRANSPORT_COMMAND_CAPACITY]) {
            case Command::PLAY:
                resetTransport(TransportState::PLAYING);
                break;
            case Command::ARM:
                resetTransport(TransportState::ARMED);
                break;
            case Command::ROLL:
                if (mState == TransportState::ARMED) {
                    mState = TransportState::PLAYING;
                }

                break;
            case Command::STOP:
                resetTransport(TransportState::STOPPED);
                break;
        }
    }

    mCommandRead.store(read, std::memory_order_release);
}

/** Moves to state at the start of the sequence, releasing the notes held. */
void MidiSequencer::resetTransport(TransportState state)
{
    releaseHeldNotes();
    mState = state;
    mPositionFrames = 0;
    mPass = 0;
}

/** Plays the block in segments that end at the loop end or the block end. The metronome clicks while recording. */
void MidiSequencer::advance(const PlaybackBuffer& buffer, int32_t frames)
{
    auto lengthFrames = std::max<int64_t>(1, tickToFrame(buffer.mLengthTicks));
    auto boundary = buffer.mIsTakeSettingLength ? std::numeric_limits<int64_t>::max() : lengthFrames;
    auto isClicking = buffer.mFirstRecordingTake != NO_TAKE;
    auto offset = 0;

    while (offset < frames) {
        auto segmentStart = mPositionFrames;
        // The length can shrink below the position: the segment is then empty and the boundary handled at once
        auto segmentEnd = std::min(segmentStart + (frames - offset), std::max(boundary, segmentStart));

        emitEvents(buffer, segmentStart, segmentEnd, offset);

        if (isClicking) {
            scheduleClicks(segmentStart, segmentEnd, offset);
        }

        offset += static_cast<int32_t>(segmentEnd - segmentStart);
        mPositionFrames = segmentEnd;

        // At the loop end
        if (mPositionFrames >= boundary) {
            mPositionFrames %= lengthFrames;
            mPass++;
        }
    }
}

void MidiSequencer::emitEvents(const PlaybackBuffer& buffer, int64_t segmentStart, int64_t segmentEnd, int32_t blockOffset)
{
    const auto& events = buffer.mEvents;

    // First event at or after the segment start (frames grow with ticks)
    auto it = std::partition_point(events.begin(), events.end(), [this, segmentStart](const SequenceEvent& event) {
        return tickToFrame(event.mTick) < segmentStart;
    });

    for (; it != events.end(); ++it) {
        auto frame = tickToFrame(it->mTick);

        if (frame >= segmentEnd) {
            break;
        }

        // Recorded in this pass (by any phrase of the recording): it was just heard live, and plays from the next pass on
        if (buffer.mFirstRecordingTake != NO_TAKE && it->mTake >= buffer.mFirstRecordingTake && it->mRecordedPass >= mPass) {
            continue;
        }

        if (!isValidSlot(it->mSlot)) {
            continue;
        }

        // Notes are tracked only once sent: an event that does not fit the block leaves them as they were
        auto& playingNotes = mPlayingNotes[it->mSlot];
        auto frameOffset = blockOffset + static_cast<int32_t>(frame - segmentStart);
        ump::Packet noteOff;

        if (playingNotes.getRetriggerNoteOff(it->mPacket, noteOff) && writeEvent(it->mSlot, frameOffset, noteOff)) {
            playingNotes.track(noteOff);
        }

        // Note-offs of notes the sequencer did not start (e.g. held across the loop end, on the first pass) are dropped
        if (playingNotes.accepts(it->mPacket) && writeEvent(it->mSlot, frameOffset, it->mPacket)) {
            playingNotes.track(it->mPacket);
        }
    }
}

void MidiSequencer::scheduleClicks(int64_t segmentStart, int64_t segmentEnd, int32_t blockOffset)
{
    auto framesPerBeat = mFramesPerTick * SEQUENCER_PPQ;

    if (framesPerBeat <= 0.0) {
        return;
    }

    for (auto beat = static_cast<int64_t>(std::floor(static_cast<double>(segmentStart) / framesPerBeat));; beat++) {
        auto frame = std::llround(static_cast<double>(beat) * framesPerBeat);

        if (frame < segmentStart) {
            continue;
        }

        if (frame >= segmentEnd) {
            break;
        }

        mMetronome.schedule(blockOffset + static_cast<int32_t>(frame - segmentStart), beat % SEQUENCER_BEATS_PER_BAR == 0);
    }
}

/** Returns false if the event does not fit the slot's MIDI2 input in this block. */
bool MidiSequencer::writeEvent(int32_t slotIndex, int32_t frameOffset, const ump::Packet& packet)
{
    auto& wordCount = mSlotWordCounts[slotIndex];
    auto capacity = std::min(MAX_SLOT_EVENT_WORDS, mSlotWordCapacities[slotIndex].load(std::memory_order_relaxed));

    if (mSlotEventCounts[slotIndex] >= MAX_SEQUENCER_EVENTS_PER_BLOCK || wordCount + 1 + packet.mWordCount > capacity) {
        return false;
    }

    auto& words = mSlotWords[slotIndex];
    auto jrTicks = static_cast<uint32_t>(static_cast<int64_t>(frameOffset) * ump::JR_TIMESTAMP_TICKS_PER_SECOND / mBlockSampleRate);
    auto& lastJrTicks = mSlotLastJrTicks[slotIndex];

    if (jrTicks > lastJrTicks) {
        words[wordCount++] = ump::makeJrTimestamp(std::min(jrTicks - lastJrTicks, ump::JR_TIMESTAMP_MAX_TICKS));
        lastJrTicks = jrTicks;
    }

    for (int32_t i = 0; i < packet.mWordCount; i++) {
        words[wordCount++] = packet.mWords[i];
    }

    mSlotEventCounts[slotIndex]++;
    return true;
}

void MidiSequencer::setSlotEventCapacity(int32_t slotIndex, int32_t wordCount)
{
    if (isValidSlot(slotIndex)) {
        mSlotWordCapacities[slotIndex].store(wordCount, std::memory_order_relaxed);
    }
}

/** At the start of the block. */
void MidiSequencer::releaseHeldNotes()
{
    for (int32_t slot = 0; slot < MAX_SEQUENCER_SLOTS; slot++) {
        releaseSlotNotes(slot);
    }
}

void MidiSequencer::releaseSlotNotes(int32_t slotIndex)
{
    if (!isValidSlot(slotIndex)) {
        return;
    }

    mPlayingNotes[slotIndex].release([&](const ump::Packet& noteOff) {
        writeEvent(slotIndex, 0, noteOff);
    });
}

const uint32_t* MidiSequencer::getSlotEvents(int32_t slotIndex, int32_t& wordCount) const
{
    if (!isValidSlot(slotIndex)) {
        wordCount = 0;
        return nullptr;
    }

    wordCount = mSlotWordCounts[slotIndex];
    return mSlotWords[slotIndex].data();
}

void MidiSequencer::renderMetronome(float* interleaved, int32_t frames)
{
    mMetronome.render(interleaved, frames, mBlockSampleRate, mMetronomeGain.load(std::memory_order_relaxed));
}

int64_t MidiSequencer::tickToFrame(int64_t tick) const
{
    return std::llround(static_cast<double>(tick) * mFramesPerTick);
}

} // namespace greenhouse
