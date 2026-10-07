#pragma once

#include <array>
#include <cstdint>
#include <cstring>

/** Minimal Universal MIDI Packet helpers for the sequencer (words in host byte order). */
namespace greenhouse::ump
{

constexpr int32_t MAX_PACKET_WORDS = 4;
constexpr int32_t BYTES_PER_WORD = 4;

// Bit fields of the first word
constexpr uint32_t MESSAGE_TYPE_SHIFT = 28;
constexpr uint32_t GROUP_SHIFT = 24;
constexpr uint32_t STATUS_SHIFT = 20;
constexpr uint32_t STATUS_BYTE_SHIFT = 16;
constexpr uint32_t CHANNEL_SHIFT = 16;
constexpr uint32_t NOTE_SHIFT = 8;
constexpr uint32_t NIBBLE_MASK = 0xF;
constexpr uint32_t DATA7_MASK = 0x7F;

constexpr uint32_t MESSAGE_TYPE_MIDI1_CHANNEL_VOICE = 0x2;
constexpr uint32_t MESSAGE_TYPE_MIDI2_CHANNEL_VOICE = 0x4;

// Packet size in words, indexed by message type (UMP spec)
constexpr std::array<int32_t, 16> WORD_COUNT_BY_MESSAGE_TYPE{1, 1, 1, 2, 2, 4, 1, 1, 2, 2, 2, 3, 3, 4, 4, 4};

constexpr uint32_t STATUS_NOTE_OFF = 0x8;
constexpr uint32_t STATUS_NOTE_ON = 0x9;
constexpr uint32_t STATUS_POLY_PRESSURE = 0xA;
constexpr uint32_t STATUS_CONTROL_CHANGE = 0xB;
constexpr uint32_t STATUS_PROGRAM_CHANGE = 0xC;
constexpr uint32_t STATUS_CHANNEL_PRESSURE = 0xD;
constexpr uint32_t STATUS_PITCH_BEND = 0xE;

// JR Timestamps count 1/31250 s. In AAP MIDI2 port buffers each one is relative to the previous one.
constexpr int32_t JR_TIMESTAMP_TICKS_PER_SECOND = 31250;
constexpr uint32_t JR_TIMESTAMP_STATUS_BYTE = 0x20;
constexpr uint32_t JR_TIMESTAMP_MAX_TICKS = 0xFFFF;

constexpr int32_t GROUP_COUNT = 16;
constexpr int32_t CHANNEL_COUNT = 16;
constexpr int32_t NOTE_COUNT = 128;
// One key per group, channel and note
constexpr int32_t NOTE_KEY_COUNT = GROUP_COUNT * CHANNEL_COUNT * NOTE_COUNT;

/** One UMP packet, 1 to 4 words. */
struct Packet
{
    std::array<uint32_t, MAX_PACKET_WORDS> mWords{};
    int32_t mWordCount{0};
};

enum class EventKind
{
    NONE,
    NOTE_ON,
    NOTE_OFF
};

inline uint32_t getMessageType(uint32_t word0)
{
    return word0 >> MESSAGE_TYPE_SHIFT;
}

inline uint32_t getGroup(uint32_t word0)
{
    return (word0 >> GROUP_SHIFT) & NIBBLE_MASK;
}

inline uint32_t getStatus(uint32_t word0)
{
    return (word0 >> STATUS_SHIFT) & NIBBLE_MASK;
}

inline uint32_t getChannel(uint32_t word0)
{
    return (word0 >> CHANNEL_SHIFT) & NIBBLE_MASK;
}

inline uint32_t getNote(uint32_t word0)
{
    return (word0 >> NOTE_SHIFT) & DATA7_MASK;
}

inline int32_t getWordCount(uint32_t word0)
{
    return WORD_COUNT_BY_MESSAGE_TYPE[getMessageType(word0)];
}

inline bool isChannelVoice(uint32_t word0)
{
    auto type = getMessageType(word0);
    return type == MESSAGE_TYPE_MIDI1_CHANNEL_VOICE || type == MESSAGE_TYPE_MIDI2_CHANNEL_VOICE;
}

inline bool isMidi1(uint32_t word0)
{
    return getMessageType(word0) == MESSAGE_TYPE_MIDI1_CHANNEL_VOICE;
}

inline EventKind classify(const Packet& packet)
{
    auto word0 = packet.mWords[0];

    if (!isChannelVoice(word0)) {
        return EventKind::NONE;
    }

    auto status = getStatus(word0);

    if (status == STATUS_NOTE_ON) {
        // A MIDI 1.0 note-on with velocity 0 is a note-off; in MIDI 2.0 it is a regular note-on
        auto isMidi1ZeroVelocity = isMidi1(word0) && (word0 & DATA7_MASK) == 0;
        return isMidi1ZeroVelocity ? EventKind::NOTE_OFF : EventKind::NOTE_ON;
    }

    if (status == STATUS_NOTE_OFF) {
        return EventKind::NOTE_OFF;
    }

    return EventKind::NONE;
}

/** Index of a note in a NOTE_KEY_COUNT table. Only meaningful for note messages. */
inline int32_t getNoteKey(uint32_t word0)
{
    return static_cast<int32_t>((getGroup(word0) * CHANNEL_COUNT + getChannel(word0)) * NOTE_COUNT + getNote(word0));
}

/** Note-off for a note key, in the protocol of the note-on it ends. */
inline Packet makeNoteOff(int32_t noteKey, bool isMidi1Note)
{
    auto note = static_cast<uint32_t>(noteKey % NOTE_COUNT);
    auto channel = static_cast<uint32_t>((noteKey / NOTE_COUNT) % CHANNEL_COUNT);
    auto group = static_cast<uint32_t>(noteKey / (NOTE_COUNT * CHANNEL_COUNT));
    auto type = isMidi1Note ? MESSAGE_TYPE_MIDI1_CHANNEL_VOICE : MESSAGE_TYPE_MIDI2_CHANNEL_VOICE;

    Packet packet;
    packet.mWords[0] = (type << MESSAGE_TYPE_SHIFT) | (group << GROUP_SHIFT) | (STATUS_NOTE_OFF << STATUS_SHIFT) |
                       (channel << CHANNEL_SHIFT) | (note << NOTE_SHIFT);
    packet.mWordCount = WORD_COUNT_BY_MESSAGE_TYPE[type];
    return packet;
}

inline uint32_t makeJrTimestamp(uint32_t ticks)
{
    return (JR_TIMESTAMP_STATUS_BYTE << STATUS_BYTE_SHIFT) | (ticks & JR_TIMESTAMP_MAX_TICKS);
}

/**
 * Reads the next packet of a UMP byte stream. Returns false at the end, or if the remaining bytes
 * do not hold a whole packet.
 */
inline bool readPacket(const uint8_t* data, int32_t size, int32_t& offset, Packet& packet)
{
    if (offset + BYTES_PER_WORD > size) {
        return false;
    }

    uint32_t word0;
    std::memcpy(&word0, data + offset, sizeof(word0));
    auto wordCount = getWordCount(word0);
    auto byteCount = wordCount * BYTES_PER_WORD;

    if (offset + byteCount > size) {
        return false;
    }

    packet.mWords = {};
    std::memcpy(packet.mWords.data(), data + offset, static_cast<size_t>(byteCount));
    packet.mWordCount = wordCount;
    offset += byteCount;
    return true;
}

} // namespace greenhouse::ump
