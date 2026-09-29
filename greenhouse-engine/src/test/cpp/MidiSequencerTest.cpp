// Desktop tests of the native MIDI sequencer and Standard MIDI File conversion. See CMakeLists.txt.

#include "sequencer/MidiSequencer.h"
#include "sequencer/StandardMidiFile.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <vector>

using namespace aaphost;

namespace
{

constexpr int32_t SAMPLE_RATE = 48000;
constexpr int32_t BLOCK_FRAMES = 256;
constexpr int64_t BAR = SEQUENCER_TICKS_PER_BAR;
constexpr int64_t BEAT = SEQUENCER_PPQ;
// JR timestamps count 1/31250 s: event positions read back are exact to about 1.5 frames at 48 kHz
constexpr int64_t FRAME_TOLERANCE = 2;
constexpr uint32_t MIDI2_NOTE_ON = 0x40900000u;
constexpr uint32_t MIDI2_NOTE_OFF = 0x40800000u;
constexpr uint32_t MIDI2_CONTROL_CHANGE = 0x40B00000u;
constexpr uint32_t FULL_VELOCITY = 0xFFFF0000u;
constexpr uint32_t HALF_VALUE = 0x80000000u;
constexpr uint32_t CC_VOLUME = 7;
constexpr uint32_t CC_ALL_NOTES_OFF = 123;
constexpr int32_t SLOT_COUNT = 3;

int gFailures = 0;

void check(bool condition, const char* description, int line)
{
    if (!condition) {
        std::printf("FAIL line %d: %s\n", line, description);
        gFailures++;
    }
}

#define CHECK(condition) check((condition), #condition, __LINE__)

ump::Packet makePacket(uint32_t word0, uint32_t word1)
{
    ump::Packet packet;
    packet.mWords[0] = word0;
    packet.mWords[1] = word1;
    packet.mWordCount = ump::getWordCount(word0);
    return packet;
}

ump::Packet noteOn(uint32_t note)
{
    return makePacket(MIDI2_NOTE_ON | (note << ump::NOTE_SHIFT), FULL_VELOCITY);
}

ump::Packet noteOff(uint32_t note)
{
    return makePacket(MIDI2_NOTE_OFF | (note << ump::NOTE_SHIFT), 0);
}

bool isNoteOn(uint32_t word0)
{
    return ump::getStatus(word0) == ump::STATUS_NOTE_ON;
}

SequenceEvent event(int64_t tick, const ump::Packet& packet, int32_t take = 0)
{
    return SequenceEvent{tick, 0, take, 0, packet};
}

/** A sequencer driven block by block, as the engine does, with slot 0's events read back as the plugin gets them. */
class Harness
{
public:
    struct Played
    {
        int64_t mFrame;
        uint32_t mWord0;
    };

    OboeEngine mEngine;
    MidiSequencer mSequencer{mEngine};
    int64_t mFrame{0};

    explicit Harness(int32_t lengthBars = 1)
    {
        SequencerSettings settings;
        settings.mLengthBars = lengthBars;
        mSequencer.setSettings(settings);
    }

    void send(const ump::Packet& packet)
    {
        mSequencer.recordInput(0, reinterpret_cast<const uint8_t*>(packet.mWords.data()), packet.mWordCount * ump::BYTES_PER_WORD);
    }

    /** Renders blocks, polling the status as the UI does (it publishes what was recorded). */
    std::vector<Played> run(int32_t blocks)
    {
        std::vector<Played> played;
        std::vector<float> audio(BLOCK_FRAMES * STEREO_CHANNEL_COUNT);

        for (int32_t block = 0; block < blocks; block++) {
            mSequencer.getStatus();
            mSequencer.beginBlock(BLOCK_FRAMES, SAMPLE_RATE);
            readSlotEvents(played);
            mSequencer.renderMetronome(audio.data(), BLOCK_FRAMES);
            mFrame += BLOCK_FRAMES;
        }

        return played;
    }

    std::vector<Played> runTicks(int64_t ticks, double bpm = DEFAULT_SEQUENCER_BPM)
    {
        auto frames = static_cast<double>(ticks) * SAMPLE_RATE * SECONDS_PER_MINUTE / (bpm * SEQUENCER_PPQ);
        return run(static_cast<int32_t>(frames / BLOCK_FRAMES));
    }

    void readSlotEvents(std::vector<Played>& played) const
    {
        int32_t wordCount = 0;
        auto words = mSequencer.getSlotEvents(0, wordCount);
        uint32_t jrTicks = 0;

        for (int32_t i = 0; i < wordCount; i += ump::getWordCount(words[i])) {
            if (ump::getMessageType(words[i]) == 0) {
                jrTicks += words[i] & ump::JR_TIMESTAMP_MAX_TICKS;
                continue;
            }

            played.push_back(Played{mFrame + static_cast<int64_t>(jrTicks) * SAMPLE_RATE / ump::JR_TIMESTAMP_TICKS_PER_SECOND, words[i]});
        }
    }
};

int64_t ticksToFrames(int64_t ticks)
{
    return ticks * SAMPLE_RATE * static_cast<int64_t>(SECONDS_PER_MINUTE) / (static_cast<int64_t>(DEFAULT_SEQUENCER_BPM) * SEQUENCER_PPQ);
}

int32_t countNoteOns(const std::vector<Harness::Played>& played)
{
    auto count = 0;

    for (const auto& event : played) {
        count += isNoteOn(event.mWord0) ? 1 : 0;
    }

    return count;
}

/** Notes whose note-ons outnumber their note-offs after some passes, as a voice-counting synth hears them. */
int32_t countStuckNotes(Harness& harness, int32_t passes)
{
    std::map<uint32_t, int32_t> voices;

    for (int32_t pass = 0; pass < passes; pass++) {
        for (const auto& event : harness.runTicks(BAR)) {
            voices[ump::getNote(event.mWord0)] += isNoteOn(event.mWord0) ? 1 : -1;
        }
    }

    auto stuck = 0;

    for (const auto& voice : voices) {
        stuck += voice.second > 1 ? 1 : 0;
    }

    return stuck;
}

void testArmedAutoLengthTake()
{
    Harness harness(AUTO_LENGTH_BARS);
    auto& sequencer = harness.mSequencer;

    sequencer.startRecording();
    harness.run(1);
    CHECK(sequencer.getStatus().mState == TransportState::ARMED);

    // The first note starts the take; it was heard live, so it is not replayed in this pass
    harness.send(noteOn(60));
    CHECK(harness.run(1).empty());
    CHECK(sequencer.getStatus().mState == TransportState::PLAYING);
    harness.runTicks(BAR + BAR / 2);
    harness.send(noteOff(60));
    harness.runTicks(BAR / 2);
    sequencer.stopRecording();

    // Stopped at ~2 bars: the take sets a 2-bar loop, and the setting stays AUTO
    auto status = sequencer.getStatus();
    CHECK(!status.mIsRecording);
    CHECK(status.mAutoLengthBars == 2 && status.mLengthTicks == 2 * BAR);
    CHECK(sequencer.getSettings().mLengthBars == AUTO_LENGTH_BARS);
    // One note per loop
    CHECK(countNoteOns(harness.runTicks(2 * BAR)) == 1);

    // Overdubs loop over the 2 bars
    sequencer.startRecording();
    harness.send(noteOn(62));
    harness.runTicks(3 * BAR);
    harness.send(noteOff(62));
    sequencer.stopRecording();
    CHECK(sequencer.getStatus().mAutoLengthBars == 2);

    sequencer.stop();
    harness.run(1);
    sequencer.clear();
    CHECK(sequencer.getStatus().mAutoLengthBars == 0);
}

void testTiming()
{
    Harness harness;
    auto& sequencer = harness.mSequencer;
    std::vector<SequenceEvent> events{event(BEAT, noteOn(64)), event(2 * BEAT, noteOff(64))};

    sequencer.setEvents(events, 0);
    harness.run(1);
    harness.mFrame = 0;
    sequencer.startPlayback();
    auto played = harness.runTicks(BAR);
    CHECK(played.size() == 2);
    CHECK(std::llabs(played[0].mFrame - ticksToFrames(BEAT)) <= FRAME_TOLERANCE);
    CHECK(std::llabs(played[1].mFrame - ticksToFrames(2 * BEAT)) <= FRAME_TOLERANCE);

    // Quantized to 1/16: the note start moves to the grid, its end moves with it
    auto sixteenth = BEAT / 4;
    events = {event(BEAT + sixteenth / 2 - 1, noteOn(64)), event(2 * BEAT, noteOff(64))};
    sequencer.setEvents(events, 0);
    SequencerSettings settings = sequencer.getSettings();
    settings.mIsQuantizing = true;
    settings.mQuantizeTicks = static_cast<int32_t>(sixteenth);
    sequencer.setSettings(settings);
    harness.run(1);
    harness.mFrame = 0;
    sequencer.startPlayback();
    played = harness.runTicks(BAR);
    CHECK(played.size() == 2);
    CHECK(std::llabs(played[0].mFrame - ticksToFrames(BEAT)) <= FRAME_TOLERANCE);
    CHECK(std::llabs(played[1].mFrame - ticksToFrames(2 * BEAT - sixteenth / 2 + 1)) <= FRAME_TOLERANCE);
}

void testOverdubAndMetronome()
{
    Harness harness;
    auto& sequencer = harness.mSequencer;

    sequencer.startRecording();
    harness.run(1);
    harness.send(noteOn(50));
    harness.run(1);
    harness.send(noteOff(50));
    harness.runTicks(BEAT);
    harness.send(noteOn(67));
    harness.runTicks(BEAT / 2);
    harness.send(noteOff(67));

    // Recorded in this pass: nothing replayed until the next pass, which plays both notes
    CHECK(harness.runTicks(BAR - 2 * BEAT).empty());
    CHECK(countNoteOns(harness.runTicks(BAR)) == 2);

    // The metronome clicks while recording only
    auto hasClicks = [&harness]() {
        std::vector<float> audio(BLOCK_FRAMES * STEREO_CHANNEL_COUNT);
        auto isAudible = false;

        for (int64_t frames = 0; frames < ticksToFrames(BAR); frames += BLOCK_FRAMES) {
            std::fill(audio.begin(), audio.end(), 0.0f);
            harness.mSequencer.beginBlock(BLOCK_FRAMES, SAMPLE_RATE);
            harness.mSequencer.renderMetronome(audio.data(), BLOCK_FRAMES);
            isAudible = isAudible || std::any_of(audio.begin(), audio.end(), [](float sample) {
                return sample != 0.0f;
            });
        }

        return isAudible;
    };

    CHECK(hasClicks());
    sequencer.stopRecording();
    harness.run(1);
    CHECK(!hasClicks());

    // Punching out closes the notes still held; notes held before punching in are not recorded
    harness.send(noteOn(70));
    sequencer.startRecording();
    harness.run(2);
    harness.send(noteOn(72));
    harness.run(2);
    sequencer.stop();
    auto events = sequencer.getEvents();
    auto countNote = [&events](uint32_t note) {
        return std::count_if(events.begin(), events.end(), [note](const SequenceEvent& event) {
            return ump::getNote(event.mPacket.mWords[0]) == note;
        });
    };
    CHECK(countNote(72) == 2);
    CHECK(countNote(70) == 0);
}

void testRecordsNotesOnly()
{
    Harness harness;
    auto& sequencer = harness.mSequencer;

    sequencer.startRecording();
    harness.run(1);
    harness.send(noteOn(60));
    harness.run(2);
    harness.send(makePacket(MIDI2_CONTROL_CHANGE | (CC_ALL_NOTES_OFF << ump::NOTE_SHIFT), 0));
    harness.send(makePacket(MIDI2_CONTROL_CHANGE | (CC_VOLUME << ump::NOTE_SHIFT), HALF_VALUE));
    harness.send(noteOff(60));
    CHECK(sequencer.getEvents().size() == 2);

    sequencer.stop();
    harness.run(1);
    sequencer.reset();
    CHECK(sequencer.getEvents().empty() && sequencer.getSettings().mLengthBars == AUTO_LENGTH_BARS);
}

void testStopRecordingWhileArmed()
{
    Harness harness;
    auto& sequencer = harness.mSequencer;

    sequencer.startRecording();
    harness.run(1);
    sequencer.stopRecording();
    harness.run(1);
    CHECK(sequencer.getStatus().mState == TransportState::STOPPED);
}

void testChangingTheLength()
{
    Harness harness(2);
    auto& sequencer = harness.mSequencer;
    // The last note is held across the loop end
    std::vector<SequenceEvent> events{event(1000, noteOn(64)), event(2000, noteOff(64)), event(7000, noteOn(60)), event(100, noteOff(60))};

    // Extending the loop leaves the new bars empty: the held note stops at the previous loop end
    sequencer.setEvents(events, 0);
    SequencerSettings settings = sequencer.getSettings();
    settings.mLengthBars = 4;
    sequencer.setSettings(settings);
    auto extended = sequencer.getEvents();
    CHECK(extended.size() == 4 && extended[3].mTick == 2 * BAR);

    // Changing the length while recording is allowed
    sequencer.startPlayback();
    harness.run(1);
    sequencer.startRecording();
    harness.run(1);
    settings.mLengthBars = 1;
    sequencer.setSettings(settings);
    harness.run(4);
    harness.send(noteOn(62));
    harness.run(2);
    harness.send(noteOff(62));
    sequencer.stopRecording();
    auto recorded = sequencer.getEvents();
    CHECK(recorded.size() == 6 && recorded[4].mTick < BAR);

    sequencer.startPlayback();
    auto played = harness.runTicks(BAR);
    CHECK(countNoteOns(played) == 2 && played.size() == 4);
}

void testNoStuckNotes()
{
    // The same pitch overlapping itself from two takes
    Harness overlap;
    overlap.mSequencer.setEvents({event(1000, noteOn(60)), event(2000, noteOff(60)), event(1500, noteOn(60), 1), event(2500, noteOff(60), 1)}, 0);
    overlap.run(1);
    overlap.mSequencer.startPlayback();
    CHECK(countStuckNotes(overlap, 4) == 0);

    // Quantized notes of the same pitch from two passes, overlapping in time: paired in recording order
    Harness quantized;
    SequencerSettings settings = quantized.mSequencer.getSettings();
    settings.mIsQuantizing = true;
    settings.mQuantizeTicks = BEAT;
    quantized.mSequencer.setSettings(settings);
    quantized.mSequencer.setEvents({event(1400, noteOn(62)), event(1500, noteOff(62)), event(1300, noteOn(62)), event(2400, noteOff(62))}, 0);
    quantized.run(1);
    quantized.mSequencer.startPlayback();
    CHECK(countStuckNotes(quantized, 4) == 0);

    // A slot too small for a block's events: what does not fit is dropped untracked, notes still end
    Harness small;
    std::vector<SequenceEvent> chord;

    // Only the first note-ons fit; the chord is released highest first, so their note-offs come last
    for (uint32_t note = 60; note < 72; note++) {
        chord.push_back(event(0, noteOn(note)));
    }

    for (uint32_t note = 71; note >= 60; note--) {
        chord.push_back(event(BEAT, noteOff(note)));
    }

    small.mSequencer.setSlotEventCapacity(0, ump::MAX_PACKET_WORDS * 2);
    small.mSequencer.setEvents(chord, 0);
    small.run(1);
    small.mSequencer.startPlayback();
    CHECK(countStuckNotes(small, 4) == 0);
}

void testReleaseSlotNotes()
{
    Harness harness;
    auto& sequencer = harness.mSequencer;

    auto noteEnd = 3 * BEAT;
    sequencer.setEvents({event(0, noteOn(60)), event(noteEnd, noteOff(60))}, 0);
    harness.run(1);
    sequencer.startPlayback();
    harness.run(4);

    // Bypassing the slot: its held note ends now, and its own note-off is not sent again
    sequencer.beginBlock(BLOCK_FRAMES, SAMPLE_RATE);
    sequencer.releaseSlotNotes(0);
    std::vector<Harness::Played> released;
    harness.readSlotEvents(released);
    CHECK(released.size() == 1 && !isNoteOn(released[0].mWord0));
    CHECK(harness.runTicks(noteEnd).empty());
}

void testUndo()
{
    Harness harness;
    auto& sequencer = harness.mSequencer;

    sequencer.startRecording();
    harness.run(1);
    harness.send(noteOn(60));
    harness.run(4);
    harness.send(noteOff(60));
    sequencer.stopRecording();

    // While recording, undo discards what the take recorded, and recording goes on
    sequencer.startRecording();
    harness.run(2);
    harness.send(noteOn(64));
    harness.run(2);
    harness.send(noteOff(64));
    sequencer.undoLastTake();
    CHECK(sequencer.getEvents().size() == 2 && sequencer.getStatus().mIsRecording);

    // Nothing recorded in the take since: undo removes the previous take
    sequencer.undoLastTake();
    CHECK(sequencer.getEvents().empty() && sequencer.getStatus().mIsRecording);

    // Armed and undone: stays armed
    sequencer.stop();
    harness.run(1);
    sequencer.startRecording();
    harness.run(1);
    sequencer.undoLastTake();
    harness.run(1);
    CHECK(sequencer.getStatus().mState == TransportState::ARMED);
}

void testUndoingEveryTakeWhileRecordingInAuto()
{
    Harness harness(AUTO_LENGTH_BARS);
    auto& sequencer = harness.mSequencer;

    sequencer.startRecording();
    harness.run(1);
    harness.send(noteOn(60));
    harness.runTicks(4 * BAR);
    harness.send(noteOff(60));
    sequencer.stopRecording();
    CHECK(sequencer.getStatus().mAutoLengthBars == 4);

    // Punched in, then every take undone: the take being recorded sets the length again
    sequencer.startRecording();
    harness.run(1);
    sequencer.undoLastTake();
    harness.runTicks(3 * BAR);
    CHECK(sequencer.getStatus().mLengthTicks > BAR);
}

void testStandardMidiFile()
{
    constexpr double FILE_BPM = 97.0;
    auto controlChange = makePacket(MIDI2_CONTROL_CHANGE | (CC_VOLUME << ump::NOTE_SHIFT), HALF_VALUE);
    auto onSlot1 = event(BEAT, controlChange);
    onSlot1.mSlot = 1;
    auto bytes = smf::write({event(0, noteOn(60)), event(BEAT / 2, noteOff(60)), onSlot1}, FILE_BPM, BAR);

    smf::ImportedSequence imported;
    CHECK(smf::read(bytes.data(), static_cast<int32_t>(bytes.size()), SLOT_COUNT, imported));
    CHECK(std::fabs(imported.mBpm - FILE_BPM) < 1e-2);
    CHECK(imported.mEvents.size() == 3);

    if (imported.mEvents.size() == 3) {
        CHECK(imported.mEvents[0].mTick == 0 && imported.mEvents[0].mPacket.mWords[1] == FULL_VELOCITY);
        CHECK(imported.mEvents[1].mTick == BEAT / 2 && !isNoteOn(imported.mEvents[1].mPacket.mWords[0]));
        CHECK(imported.mEvents[2].mSlot == 1 && imported.mEvents[2].mPacket.mWords[1] == HALF_VALUE);
    }

    std::vector<uint8_t> junk{1, 2, 3, 4, 5, 6, 7, 8, 9};
    CHECK(!smf::read(junk.data(), static_cast<int32_t>(junk.size()), SLOT_COUNT, imported));

    // Imported in AUTO: the loop fits the file
    Harness harness(AUTO_LENGTH_BARS);
    CHECK(harness.mSequencer.importStandardMidiFile(bytes.data(), static_cast<int32_t>(bytes.size())));
    CHECK(harness.mSequencer.getStatus().mLengthTicks == BAR);
}

} // namespace

int main()
{
    testArmedAutoLengthTake();
    testTiming();
    testOverdubAndMetronome();
    testRecordsNotesOnly();
    testStopRecordingWhileArmed();
    testChangingTheLength();
    testNoStuckNotes();
    testReleaseSlotNotes();
    testUndo();
    testUndoingEveryTakeWhileRecordingInAuto();
    testStandardMidiFile();

    if (gFailures > 0) {
        std::printf("%d failure(s)\n", gFailures);
        return EXIT_FAILURE;
    }

    std::printf("All passed\n");
    return EXIT_SUCCESS;
}
