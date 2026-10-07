#include "sequencer/StandardMidiFile.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <string>

namespace greenhouse::smf
{

namespace
{

constexpr char HEADER_CHUNK_ID[] = "MThd";
constexpr char TRACK_CHUNK_ID[] = "MTrk";
constexpr int32_t CHUNK_ID_SIZE = 4;
constexpr uint32_t HEADER_CHUNK_LENGTH = 6;
constexpr uint16_t FORMAT_MULTI_TRACK = 1;
constexpr uint16_t SMPTE_DIVISION_FLAG = 0x8000;

constexpr uint8_t STATUS_FLAG = 0x80;
constexpr uint8_t SYSTEM_MESSAGE_MIN = 0xF0;
constexpr uint8_t SYSEX_START = 0xF0;
constexpr uint8_t SYSEX_CONTINUATION = 0xF7;
constexpr uint8_t META_EVENT = 0xFF;
constexpr uint8_t META_TRACK_NAME = 0x03;
constexpr uint8_t META_END_OF_TRACK = 0x2F;
constexpr uint8_t META_TEMPO = 0x51;
constexpr uint8_t META_TIME_SIGNATURE = 0x58;
constexpr uint32_t TEMPO_DATA_LENGTH = 3;
constexpr uint32_t BITS_PER_BYTE = 8;
// 4/4, 24 MIDI clocks per metronome click, 8 32nd notes per quarter note
constexpr std::array<uint8_t, 4> TIME_SIGNATURE_4_4{4, 2, 24, 8};
constexpr double MICROSECONDS_PER_MINUTE = 60000000.0;

constexpr int32_t VLQ_MAX_BYTES = 4;
constexpr uint32_t VLQ_DATA_BITS = 7;
constexpr uint32_t VLQ_DATA_MASK = 0x7F;
constexpr uint32_t VLQ_MAX_VALUE = 0x0FFFFFFF;
constexpr uint8_t VLQ_CONTINUATION_FLAG = 0x80;

constexpr uint32_t STATUS_NIBBLE_SHIFT = 4;
constexpr uint32_t MIDI1_DATA_BITS = 7;
constexpr uint32_t MIDI1_PITCH_BEND_BITS = 14;
constexpr uint32_t MIDI2_VELOCITY_BITS = 16;
constexpr uint32_t MIDI2_DATA_BITS = 32;
constexpr uint32_t MIDI2_VELOCITY_SHIFT = 16; // note velocity: upper half of word 1
constexpr uint32_t MIDI2_PROGRAM_SHIFT = 24;
constexpr uint32_t MIDI2_BANK_MSB_SHIFT = 8;
constexpr uint32_t MIDI2_BANK_VALID_FLAG = 0x1;
constexpr uint8_t CC_BANK_SELECT_MSB = 0;
constexpr uint8_t CC_BANK_SELECT_LSB = 32;
// A MIDI 1.0 note-on at velocity 0 is a note-off
constexpr uint8_t MIN_MIDI1_NOTE_ON_VELOCITY = 1;
constexpr int32_t MAX_MIDI1_MESSAGES_PER_EVENT = 3;

constexpr char SLOT_TRACK_NAME_PREFIX[] = "Slot ";
constexpr int32_t DECIMAL_BASE = 10;

struct Midi1Message
{
    std::array<uint8_t, 3> mBytes{};
    int32_t mLength{0};
};

// -- MIDI 1.0 <-> MIDI 2.0 value scaling (MIDI 2.0 spec, "Min-Center-Max" scaling) --

uint32_t scaleDown(uint32_t value, uint32_t sourceBits, uint32_t targetBits)
{
    return value >> (sourceBits - targetBits);
}

uint32_t scaleUp(uint32_t value, uint32_t sourceBits, uint32_t targetBits)
{
    auto scaleBits = targetBits - sourceBits;
    auto shifted = static_cast<uint64_t>(value) << scaleBits;
    auto sourceCenter = uint32_t{1} << (sourceBits - 1);

    if (value <= sourceCenter) {
        return static_cast<uint32_t>(shifted);
    }

    // Above the center, repeat the lower bits so the maximum maps to the maximum
    auto repeatBits = sourceBits - 1;
    auto repeatValue = static_cast<uint64_t>(value) & ((uint64_t{1} << repeatBits) - 1);

    if (scaleBits > repeatBits) {
        repeatValue <<= scaleBits - repeatBits;
    } else {
        repeatValue >>= repeatBits - scaleBits;
    }

    while (repeatValue != 0) {
        shifted |= repeatValue;
        repeatValue >>= repeatBits;
    }

    return static_cast<uint32_t>(shifted);
}

// -- Writing --

void writeU16(std::vector<uint8_t>& out, uint16_t value)
{
    out.push_back(static_cast<uint8_t>(value >> BITS_PER_BYTE));
    out.push_back(static_cast<uint8_t>(value));
}

void writeU32(std::vector<uint8_t>& out, uint32_t value)
{
    for (auto byteIndex = static_cast<int32_t>(sizeof(value)) - 1; byteIndex >= 0; byteIndex--) {
        out.push_back(static_cast<uint8_t>(value >> (byteIndex * BITS_PER_BYTE)));
    }
}

void writeVlq(std::vector<uint8_t>& out, uint64_t value)
{
    auto remaining = static_cast<uint32_t>(std::min<uint64_t>(value, VLQ_MAX_VALUE));
    std::array<uint8_t, VLQ_MAX_BYTES> groups{};
    auto count = 0;

    do {
        groups[count++] = static_cast<uint8_t>(remaining & VLQ_DATA_MASK);
        remaining >>= VLQ_DATA_BITS;
    } while (remaining != 0 && count < VLQ_MAX_BYTES);

    for (auto i = count - 1; i >= 0; i--) {
        out.push_back(static_cast<uint8_t>(groups[i] | (i > 0 ? VLQ_CONTINUATION_FLAG : 0)));
    }
}

void writeMeta(std::vector<uint8_t>& track, uint64_t delta, uint8_t type, const uint8_t* data, uint32_t length)
{
    writeVlq(track, delta);
    track.push_back(META_EVENT);
    track.push_back(type);
    writeVlq(track, length);
    track.insert(track.end(), data, data + length);
}

void writeChunk(std::vector<uint8_t>& out, const char* id, const std::vector<uint8_t>& data)
{
    out.insert(out.end(), id, id + CHUNK_ID_SIZE);
    writeU32(out, static_cast<uint32_t>(data.size()));
    out.insert(out.end(), data.begin(), data.end());
}

Midi1Message makeMessage(uint8_t status, uint8_t data1)
{
    return Midi1Message{{status, data1, 0}, 2};
}

Midi1Message makeMessage(uint8_t status, uint8_t data1, uint8_t data2)
{
    return Midi1Message{{status, data1, data2}, 3};
}

/** MIDI 1.0 form of an event: none for events that have none, up to 3 for a program change with a bank. */
int32_t toMidi1(const ump::Packet& packet, std::array<Midi1Message, MAX_MIDI1_MESSAGES_PER_EVENT>& out)
{
    auto word0 = packet.mWords[0];

    if (!ump::isChannelVoice(word0)) {
        return 0;
    }

    auto status = ump::getStatus(word0);
    auto statusByte = static_cast<uint8_t>((status << STATUS_NIBBLE_SHIFT) | ump::getChannel(word0));
    auto data1 = static_cast<uint8_t>((word0 >> ump::NOTE_SHIFT) & ump::DATA7_MASK);

    if (ump::isMidi1(word0)) {
        auto data2 = static_cast<uint8_t>(word0 & ump::DATA7_MASK);
        auto isSingleDataByte = status == ump::STATUS_PROGRAM_CHANGE || status == ump::STATUS_CHANNEL_PRESSURE;
        out[0] = isSingleDataByte ? makeMessage(statusByte, data1) : makeMessage(statusByte, data1, data2);
        return 1;
    }

    auto word1 = packet.mWords[1];
    auto data7 = static_cast<uint8_t>(scaleDown(word1, MIDI2_DATA_BITS, MIDI1_DATA_BITS));
    auto velocity7 = static_cast<uint8_t>(scaleDown(word1 >> MIDI2_VELOCITY_SHIFT, MIDI2_VELOCITY_BITS, MIDI1_DATA_BITS));

    switch (status) {
        case ump::STATUS_NOTE_OFF:
            out[0] = makeMessage(statusByte, data1, velocity7);
            return 1;
        case ump::STATUS_NOTE_ON:
            out[0] = makeMessage(statusByte, data1, std::max(velocity7, MIN_MIDI1_NOTE_ON_VELOCITY));
            return 1;
        case ump::STATUS_POLY_PRESSURE:
        case ump::STATUS_CONTROL_CHANGE:
            out[0] = makeMessage(statusByte, data1, data7);
            return 1;
        case ump::STATUS_CHANNEL_PRESSURE:
            out[0] = makeMessage(statusByte, data7);
            return 1;
        case ump::STATUS_PITCH_BEND: {
            auto value14 = scaleDown(word1, MIDI2_DATA_BITS, MIDI1_PITCH_BEND_BITS);
            out[0] = makeMessage(statusByte, static_cast<uint8_t>(value14 & ump::DATA7_MASK),
                                 static_cast<uint8_t>(value14 >> MIDI1_DATA_BITS));
            return 1;
        }
        case ump::STATUS_PROGRAM_CHANGE: {
            auto count = 0;
            auto controlChange = static_cast<uint8_t>((ump::STATUS_CONTROL_CHANGE << STATUS_NIBBLE_SHIFT) | ump::getChannel(word0));

            if ((word0 & MIDI2_BANK_VALID_FLAG) != 0) {
                out[count++] = makeMessage(controlChange, CC_BANK_SELECT_MSB,
                                           static_cast<uint8_t>((word1 >> MIDI2_BANK_MSB_SHIFT) & ump::DATA7_MASK));
                out[count++] = makeMessage(controlChange, CC_BANK_SELECT_LSB, static_cast<uint8_t>(word1 & ump::DATA7_MASK));
            }

            out[count++] = makeMessage(statusByte, static_cast<uint8_t>((word1 >> MIDI2_PROGRAM_SHIFT) & ump::DATA7_MASK));
            return count;
        }
        default:
            return 0;
    }
}

// -- Reading --

class ByteReader
{
public:
    ByteReader(const uint8_t* data, int32_t size) : mData(data), mSize(size)
    {
    }

    bool isAtEnd() const
    {
        return mPosition >= mSize;
    }

    bool readU8(uint8_t& value)
    {
        if (mPosition + 1 > mSize) {
            return false;
        }

        value = mData[mPosition++];
        return true;
    }

    bool readU16(uint16_t& value)
    {
        uint8_t high;
        uint8_t low;

        if (!readU8(high) || !readU8(low)) {
            return false;
        }

        value = static_cast<uint16_t>((high << BITS_PER_BYTE) | low);
        return true;
    }

    bool readU32(uint32_t& value)
    {
        value = 0;

        for (uint32_t i = 0; i < sizeof(value); i++) {
            uint8_t byte;

            if (!readU8(byte)) {
                return false;
            }

            value = (value << BITS_PER_BYTE) | byte;
        }

        return true;
    }

    bool readVlq(uint32_t& value)
    {
        value = 0;

        for (int32_t i = 0; i < VLQ_MAX_BYTES; i++) {
            uint8_t byte;

            if (!readU8(byte)) {
                return false;
            }

            value = (value << VLQ_DATA_BITS) | (byte & VLQ_DATA_MASK);

            if ((byte & VLQ_CONTINUATION_FLAG) == 0) {
                return true;
            }
        }

        return false;
    }

    /** Points data at the next length bytes and skips them. */
    bool readBytes(uint32_t length, const uint8_t*& data)
    {
        if (length > static_cast<uint32_t>(mSize - mPosition)) {
            return false;
        }

        data = mData + mPosition;
        mPosition += static_cast<int32_t>(length);
        return true;
    }

private:
    const uint8_t* mData;
    int32_t mSize;
    int32_t mPosition{0};
};

ump::Packet toUmp(uint8_t statusByte, uint8_t data1, uint8_t data2)
{
    auto status = static_cast<uint32_t>(statusByte >> STATUS_NIBBLE_SHIFT);
    auto channel = static_cast<uint32_t>(statusByte) & ump::NIBBLE_MASK;

    // A MIDI 1.0 note-on at velocity 0 is a note-off; MIDI 2.0 has no such convention
    if (status == ump::STATUS_NOTE_ON && data2 == 0) {
        status = ump::STATUS_NOTE_OFF;
    }

    ump::Packet packet;
    packet.mWordCount = ump::WORD_COUNT_BY_MESSAGE_TYPE[ump::MESSAGE_TYPE_MIDI2_CHANNEL_VOICE];
    auto& word0 = packet.mWords[0];
    auto& word1 = packet.mWords[1];
    word0 = (ump::MESSAGE_TYPE_MIDI2_CHANNEL_VOICE << ump::MESSAGE_TYPE_SHIFT) | (status << ump::STATUS_SHIFT) |
            (channel << ump::CHANNEL_SHIFT);

    switch (status) {
        case ump::STATUS_NOTE_OFF:
        case ump::STATUS_NOTE_ON:
            word0 |= static_cast<uint32_t>(data1) << ump::NOTE_SHIFT;
            word1 = scaleUp(data2, MIDI1_DATA_BITS, MIDI2_VELOCITY_BITS) << MIDI2_VELOCITY_SHIFT;
            break;
        case ump::STATUS_POLY_PRESSURE:
        case ump::STATUS_CONTROL_CHANGE:
            word0 |= static_cast<uint32_t>(data1) << ump::NOTE_SHIFT;
            word1 = scaleUp(data2, MIDI1_DATA_BITS, MIDI2_DATA_BITS);
            break;
        case ump::STATUS_PROGRAM_CHANGE:
            word1 = static_cast<uint32_t>(data1) << MIDI2_PROGRAM_SHIFT;
            break;
        case ump::STATUS_CHANNEL_PRESSURE:
            word1 = scaleUp(data1, MIDI1_DATA_BITS, MIDI2_DATA_BITS);
            break;
        case ump::STATUS_PITCH_BEND:
            word1 = scaleUp(data1 | (static_cast<uint32_t>(data2) << MIDI1_DATA_BITS), MIDI1_PITCH_BEND_BITS, MIDI2_DATA_BITS);
            break;
        default:
            break;
    }

    return packet;
}

/** Slot index for a track named "Slot N" (1-based), or the instrument slot. */
int32_t parseSlotTrackName(const uint8_t* data, uint32_t length, int32_t slotCount)
{
    auto prefixLength = static_cast<uint32_t>(std::strlen(SLOT_TRACK_NAME_PREFIX));

    if (length <= prefixLength || std::memcmp(data, SLOT_TRACK_NAME_PREFIX, prefixLength) != 0) {
        return 0;
    }

    auto number = 0;

    for (auto i = prefixLength; i < length; i++) {
        if (data[i] < '0' || data[i] > '9') {
            return 0;
        }

        number = number * DECIMAL_BASE + (data[i] - '0');

        if (number > slotCount) {
            return 0;
        }
    }

    return number >= 1 ? number - 1 : 0;
}

int64_t toSequencerTicks(uint64_t fileTicks, uint16_t division)
{
    return static_cast<int64_t>((fileTicks * SEQUENCER_PPQ + division / 2) / division);
}

bool readTrack(ByteReader& track, uint16_t division, int32_t slotCount, ImportedSequence& result, bool& hasTempo)
{
    std::vector<SequenceEvent> events;
    uint64_t fileTick = 0;
    uint8_t runningStatus = 0;
    auto slot = 0;

    while (!track.isAtEnd()) {
        uint32_t delta;
        uint8_t first;

        if (!track.readVlq(delta) || !track.readU8(first)) {
            return false;
        }

        fileTick += delta;

        if (first == META_EVENT) {
            uint8_t type;
            uint32_t length;
            const uint8_t* data;

            if (!track.readU8(type) || !track.readVlq(length) || !track.readBytes(length, data)) {
                return false;
            }

            if (type == META_TEMPO && length == TEMPO_DATA_LENGTH && !hasTempo) {
                auto microsecondsPerQuarter = (static_cast<uint32_t>(data[0]) << (2 * BITS_PER_BYTE)) |
                                              (static_cast<uint32_t>(data[1]) << BITS_PER_BYTE) | data[2];

                if (microsecondsPerQuarter > 0) {
                    result.mBpm = std::clamp(MICROSECONDS_PER_MINUTE / microsecondsPerQuarter, MIN_SEQUENCER_BPM, MAX_SEQUENCER_BPM);
                    hasTempo = true;
                }
            } else if (type == META_TRACK_NAME) {
                slot = parseSlotTrackName(data, length, slotCount);
            } else if (type == META_END_OF_TRACK) {
                break;
            }

            continue;
        }

        if (first == SYSEX_START || first == SYSEX_CONTINUATION) {
            uint32_t length;
            const uint8_t* data;

            if (!track.readVlq(length) || !track.readBytes(length, data)) {
                return false;
            }

            continue;
        }

        uint8_t status;
        uint8_t data1;

        if ((first & STATUS_FLAG) != 0) {
            // Other system messages cannot appear in a MIDI file
            if (first >= SYSTEM_MESSAGE_MIN) {
                return false;
            }

            status = first;

            if (!track.readU8(data1)) {
                return false;
            }
        } else {
            if (runningStatus == 0) {
                return false;
            }

            status = runningStatus;
            data1 = first;
        }

        runningStatus = status;

        auto statusNibble = static_cast<uint32_t>(status >> STATUS_NIBBLE_SHIFT);
        auto isSingleDataByte = statusNibble == ump::STATUS_PROGRAM_CHANGE || statusNibble == ump::STATUS_CHANNEL_PRESSURE;
        uint8_t data2 = 0;

        if (!isSingleDataByte && !track.readU8(data2)) {
            return false;
        }

        SequenceEvent event;
        event.mTick = toSequencerTicks(fileTick, division);
        event.mPacket = toUmp(status, static_cast<uint8_t>(data1 & ump::DATA7_MASK), static_cast<uint8_t>(data2 & ump::DATA7_MASK));
        events.push_back(event);
    }

    for (auto& event : events) {
        event.mSlot = slot;

        if (static_cast<int32_t>(result.mEvents.size()) >= MAX_SEQUENCE_EVENTS) {
            break;
        }

        result.mEvents.push_back(event);
    }

    return true;
}

} // namespace

std::vector<uint8_t> write(const std::vector<SequenceEvent>& events, double bpm, int64_t lengthTicks)
{
    std::vector<uint8_t> file;
    std::vector<std::vector<uint8_t>> tracks;

    // Conductor track: time signature and tempo, lasting the whole sequence
    std::vector<uint8_t> conductor;
    auto microsecondsPerQuarter = static_cast<uint32_t>(std::lround(MICROSECONDS_PER_MINUTE / bpm));
    std::array<uint8_t, TEMPO_DATA_LENGTH> tempo{
        static_cast<uint8_t>(microsecondsPerQuarter >> (2 * BITS_PER_BYTE)),
        static_cast<uint8_t>(microsecondsPerQuarter >> BITS_PER_BYTE),
        static_cast<uint8_t>(microsecondsPerQuarter)};
    writeMeta(conductor, 0, META_TIME_SIGNATURE, TIME_SIGNATURE_4_4.data(), TIME_SIGNATURE_4_4.size());
    writeMeta(conductor, 0, META_TEMPO, tempo.data(), TEMPO_DATA_LENGTH);
    writeMeta(conductor, static_cast<uint64_t>(std::max<int64_t>(lengthTicks, 0)), META_END_OF_TRACK, nullptr, 0);
    tracks.push_back(std::move(conductor));

    for (int32_t slot = 0; slot < MAX_SEQUENCER_SLOTS; slot++) {
        std::vector<uint8_t> track;
        int64_t lastTick = 0;
        auto hasEvents = false;
        std::array<Midi1Message, MAX_MIDI1_MESSAGES_PER_EVENT> messages;

        for (const auto& event : events) {
            if (event.mSlot != slot) {
                continue;
            }

            auto count = toMidi1(event.mPacket, messages);

            if (count == 0) {
                continue;
            }

            if (!hasEvents) {
                auto name = std::string(SLOT_TRACK_NAME_PREFIX) + std::to_string(slot + 1);
                writeMeta(track, 0, META_TRACK_NAME, reinterpret_cast<const uint8_t*>(name.data()), static_cast<uint32_t>(name.size()));
                hasEvents = true;
            }

            auto tick = std::max(event.mTick, lastTick);

            for (int32_t i = 0; i < count; i++) {
                writeVlq(track, static_cast<uint64_t>(i == 0 ? tick - lastTick : 0));
                track.insert(track.end(), messages[i].mBytes.begin(), messages[i].mBytes.begin() + messages[i].mLength);
            }

            lastTick = tick;
        }

        if (!hasEvents) {
            continue;
        }

        writeMeta(track, static_cast<uint64_t>(std::max<int64_t>(lengthTicks - lastTick, 0)), META_END_OF_TRACK, nullptr, 0);
        tracks.push_back(std::move(track));
    }

    std::vector<uint8_t> header;
    writeU16(header, FORMAT_MULTI_TRACK);
    writeU16(header, static_cast<uint16_t>(tracks.size()));
    writeU16(header, static_cast<uint16_t>(SEQUENCER_PPQ));
    writeChunk(file, HEADER_CHUNK_ID, header);

    for (const auto& track : tracks) {
        writeChunk(file, TRACK_CHUNK_ID, track);
    }

    return file;
}

bool read(const uint8_t* data, int32_t size, int32_t slotCount, ImportedSequence& result)
{
    ByteReader reader(data, size);
    const uint8_t* id;
    uint32_t headerLength;
    uint16_t format;
    uint16_t trackCount;
    uint16_t division;

    if (!reader.readBytes(CHUNK_ID_SIZE, id) || std::memcmp(id, HEADER_CHUNK_ID, CHUNK_ID_SIZE) != 0 ||
        !reader.readU32(headerLength) || headerLength < HEADER_CHUNK_LENGTH ||
        !reader.readU16(format) || !reader.readU16(trackCount) || !reader.readU16(division)) {
        return false;
    }

    // SMPTE time division has no tempo-relative ticks to map to bars
    if ((division & SMPTE_DIVISION_FLAG) != 0 || division == 0) {
        return false;
    }

    const uint8_t* skipped;

    if (!reader.readBytes(headerLength - HEADER_CHUNK_LENGTH, skipped)) {
        return false;
    }

    result = ImportedSequence{};
    auto hasTempo = false;
    auto tracksRead = 0;

    while (!reader.isAtEnd() && tracksRead < trackCount) {
        uint32_t chunkLength;
        const uint8_t* chunk;

        if (!reader.readBytes(CHUNK_ID_SIZE, id) || !reader.readU32(chunkLength) || !reader.readBytes(chunkLength, chunk)) {
            return false;
        }

        // Unknown chunks are skipped, as the format requires
        if (std::memcmp(id, TRACK_CHUNK_ID, CHUNK_ID_SIZE) != 0) {
            continue;
        }

        ByteReader track(chunk, static_cast<int32_t>(chunkLength));

        if (!readTrack(track, division, slotCount, result, hasTempo)) {
            return false;
        }

        tracksRead++;
    }

    sortByTick(result.mEvents);
    return tracksRead > 0;
}

} // namespace greenhouse::smf
