#pragma once

#include "sequencer/Ump.h"
#include <cstdint>

/**
 * Reading the events a SlotProcessor receives: UMP words in host byte order, from queueEvents()
 * (live input, as bytes) and process() (the sequencer's events of the block). Timed events follow a
 * JR timestamp; parameter changes are AAP parameter SysEx8 messages, as aap-core sends them to
 * plugins (aapMidi2ParameterSysex8() in aap/ext/midi.h).
 */
namespace greenhouse
{

constexpr uint32_t MESSAGE_TYPE_DATA128 = 0x5;
constexpr uint32_t BYTE_MASK = 0xFF;
// A JR timestamp's message type, group and status byte
constexpr uint32_t JR_TIMESTAMP_HEADER_MASK = 0xF0FF0000;

// AAP parameter SysEx8: universal SysEx ID in the low byte of word 0, then 0x7F and the channel in word 1
constexpr int32_t PARAMETER_SYSEX8_WORD_COUNT = 4;
constexpr uint32_t PARAMETER_SYSEX8_UNIVERSAL_ID = 0x7E;
constexpr uint32_t PARAMETER_SYSEX8_WORD1_BASE = 0x7F000000;
constexpr uint32_t PARAMETER_ID_MASK = 0xFFFF;
// Normalized values travel as unsigned 32-bit integers, 0 to 1 mapped over the whole range
constexpr double MAX_TRANSPORT_VALUE = 4294967295.0;

/** A parameter change sent by the host (SlotDevice.setParameterValues() on the Kotlin side). */
struct ParameterChange
{
    uint32_t mParameterId{0};
    /** 0 to 1 over the parameter's range: see toPlainValue(). */
    double mNormalizedValue{0.0};
};

inline bool isJrTimestamp(uint32_t word0)
{
    return (word0 & JR_TIMESTAMP_HEADER_MASK) == (ump::JR_TIMESTAMP_STATUS_BYTE << ump::STATUS_BYTE_SHIFT);
}

/** Ticks of a JR timestamp (1/31250 s), relative to the previous one, or to the block start for the first one. */
inline uint32_t getJrTimestampTicks(uint32_t word0)
{
    return word0 & ump::JR_TIMESTAMP_MAX_TICKS;
}

/** Frames in ticks of JR timestamps at sampleRate. */
inline int32_t jrTicksToFrames(uint32_t ticks, int32_t sampleRate)
{
    return static_cast<int32_t>(static_cast<int64_t>(ticks) * sampleRate / ump::JR_TIMESTAMP_TICKS_PER_SECOND);
}

/** Reads the packet at words (ump::getWordCount(words[0]) words) as a parameter change. Returns false for any other message. */
inline bool readParameterChange(const uint32_t* words, int32_t wordCount, ParameterChange& change)
{
    if (wordCount < PARAMETER_SYSEX8_WORD_COUNT || ump::getMessageType(words[0]) != MESSAGE_TYPE_DATA128) {
        return false;
    }

    auto channel = words[1] & ump::NIBBLE_MASK;

    if ((words[0] & BYTE_MASK) != PARAMETER_SYSEX8_UNIVERSAL_ID || words[1] != PARAMETER_SYSEX8_WORD1_BASE + channel) {
        return false;
    }

    change.mParameterId = words[2] & PARAMETER_ID_MASK;
    change.mNormalizedValue = static_cast<double>(words[3]) / MAX_TRANSPORT_VALUE;
    return true;
}

/** The value in the parameter's range, as the host meant it. */
inline double toPlainValue(double normalizedValue, double minValue, double maxValue)
{
    return minValue + normalizedValue * (maxValue - minValue);
}

} // namespace greenhouse
