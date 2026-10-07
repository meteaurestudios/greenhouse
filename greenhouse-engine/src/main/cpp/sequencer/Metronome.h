#pragma once

#include <array>
#include <cstdint>

namespace greenhouse
{

// Click peak at 0 dB: -12 dBFS, so the highest level (+12 dB) peaks at full scale
constexpr float METRONOME_GAIN = 0.25f;
constexpr float METRONOME_SILENT_GAIN = 0.0f;
constexpr float METRONOME_BEAT_FREQUENCY_HZ = 1000.0f;
constexpr float METRONOME_ACCENT_FREQUENCY_HZ = 1600.0f;
constexpr float METRONOME_CLICK_SECONDS = 0.03f;
// The click decays to this fraction of its level over METRONOME_CLICK_SECONDS
constexpr float METRONOME_CLICK_END_LEVEL = 0.001f;
constexpr int32_t MAX_PENDING_CLICKS = 8;

/**
 * Short sine clicks mixed on top of the rack output. Audio thread only: schedule() the clicks of
 * a block, then render() it. A click longer than a block carries over to the next one.
 */
class Metronome
{
public:
    void reset();

    /** Queues a click at a frame of the current block. Clicks must be scheduled in time order. */
    void schedule(int32_t frameOffset, bool isAccent);

    /** Adds the clicks to interleaved stereo and clears the queue. gain: linear, for the clicks starting in this block. */
    void render(float* interleaved, int32_t frames, int32_t sampleRate, float gain);

private:
    struct Click
    {
        int32_t mFrameOffset{0};
        bool mIsAccent{false};
    };

    void startClick(bool isAccent, int32_t sampleRate, float gain);

    std::array<Click, MAX_PENDING_CLICKS> mPending{};
    int32_t mPendingCount{0};

    int32_t mRemainingFrames{0};
    float mPhase{0.0f};
    float mPhaseIncrement{0.0f};
    float mLevel{0.0f};
    float mDecay{0.0f};
};

} // namespace greenhouse
