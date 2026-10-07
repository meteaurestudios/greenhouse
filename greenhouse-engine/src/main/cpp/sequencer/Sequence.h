#pragma once

#include "sequencer/Ump.h"
#include <algorithm>
#include <cstdint>
#include <vector>

namespace greenhouse
{

// Musical time: ticks per quarter note (PPQ), in 4/4 bars
constexpr int32_t SEQUENCER_PPQ = 960;
constexpr int32_t SEQUENCER_BEATS_PER_BAR = 4;
constexpr int64_t SEQUENCER_TICKS_PER_BAR = static_cast<int64_t>(SEQUENCER_PPQ) * SEQUENCER_BEATS_PER_BAR;

constexpr double DEFAULT_SEQUENCER_BPM = 120.0;
constexpr double MIN_SEQUENCER_BPM = 20.0;
constexpr double MAX_SEQUENCER_BPM = 300.0;
constexpr double SECONDS_PER_MINUTE = 60.0;

// Loop length in bars; AUTO_LENGTH_BARS lets the first take set it
constexpr int32_t AUTO_LENGTH_BARS = 0;
constexpr int32_t MAX_SEQUENCE_LENGTH_BARS = 999;
constexpr int32_t MAX_SEQUENCE_EVENTS = 65536;
// Slots the sequencer records and plays. Only the instrument slot gets notes; events, sessions and
// .mid files keep a slot index so this can grow (e.g. for automation on effect slots).
constexpr int32_t MAX_SEQUENCER_SLOTS = 1;

constexpr int32_t NO_TAKE = -1;
constexpr int32_t DEFAULT_QUANTIZE_TICKS = SEQUENCER_PPQ / 4; // 1/16

// Metronome level in dB, 0 dB peaking at -12 dBFS (see METRONOME_GAIN). It only clicks while
// recording, so the range stays audible: the lowest level mutes it, the highest peaks at full scale.
constexpr float MIN_METRONOME_LEVEL_DB = -30.0f;
constexpr float MAX_METRONOME_LEVEL_DB = 12.0f;
constexpr float DEFAULT_METRONOME_LEVEL_DB = 0.0f;

/** Sequencer settings, saved with the session. */
struct SequencerSettings
{
    double mBpm{DEFAULT_SEQUENCER_BPM};
    int32_t mLengthBars{AUTO_LENGTH_BARS};
    bool mIsQuantizing{false}; // quantize note starts on playback
    int32_t mQuantizeTicks{DEFAULT_QUANTIZE_TICKS}; // quantize grid
    float mMetronomeLevelDb{DEFAULT_METRONOME_LEVEL_DB};
};

/** A MIDI event of the sequence: a UMP packet sent to a rack slot at a musical position. */
struct SequenceEvent
{
    int64_t mTick{0};
    int32_t mSlot{0};
    int32_t mTake{0};
    // Loop pass the event was recorded in (not saved): it is not played back until the next pass
    int32_t mRecordedPass{0};
    ump::Packet mPacket;
};

inline void sortByTick(std::vector<SequenceEvent>& events)
{
    std::stable_sort(events.begin(), events.end(), [](const SequenceEvent& a, const SequenceEvent& b) {
        return a.mTick < b.mTick;
    });
}

} // namespace greenhouse
