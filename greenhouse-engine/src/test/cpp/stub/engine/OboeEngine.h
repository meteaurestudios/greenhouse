#pragma once

#include <cstdint>

// Stand-in for the Oboe-based engine: the sequencer only needs its audio thread markers, and the
// tests drive the audio thread themselves, so every retired buffer can be freed at once.
namespace aaphost
{

constexpr int32_t STEREO_CHANNEL_COUNT = 2;
constexpr int32_t DEFAULT_SAMPLE_RATE = 48000;

class OboeEngine
{
public:
    uint64_t getAudioThreadMarker() const
    {
        return 0;
    }

    bool hasAudioThreadPassed(uint64_t marker) const
    {
        return true;
    }
};

} // namespace aaphost
