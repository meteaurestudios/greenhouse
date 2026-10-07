#pragma once

#include "sequencer/Sequence.h"
#include <cstdint>
#include <vector>

/**
 * Standard MIDI File (.mid) conversion of a sequence. Files are written as format 1: a conductor
 * track with the tempo, then one track per slot, named "Slot N". MIDI 2.0 events are converted to
 * MIDI 1.0 and back; plugin parameter automation has no MIDI 1.0 form and is left out.
 */
namespace greenhouse::smf
{

struct ImportedSequence
{
    std::vector<SequenceEvent> mEvents; // in time order, all in take 0
    double mBpm{DEFAULT_SEQUENCER_BPM};
};

std::vector<uint8_t> write(const std::vector<SequenceEvent>& events, double bpm, int64_t lengthTicks);

/** Tracks named "Slot N" go to that slot, others to the instrument slot. Returns false if the file cannot be read. */
bool read(const uint8_t* data, int32_t size, int32_t slotCount, ImportedSequence& result);

} // namespace greenhouse::smf
