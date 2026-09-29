#include "sequencer/Metronome.h"
#include "engine/OboeEngine.h"
#include <algorithm>
#include <cmath>

namespace aaphost
{

namespace
{

constexpr float TWO_PI = 6.283185307179586f;

} // namespace

void Metronome::reset()
{
    mPendingCount = 0;
    mRemainingFrames = 0;
}

void Metronome::schedule(int32_t frameOffset, bool isAccent)
{
    if (mPendingCount >= MAX_PENDING_CLICKS) {
        return;
    }

    mPending[mPendingCount++] = Click{frameOffset, isAccent};
}

void Metronome::startClick(bool isAccent, int32_t sampleRate)
{
    auto frequency = isAccent ? METRONOME_ACCENT_FREQUENCY_HZ : METRONOME_BEAT_FREQUENCY_HZ;
    auto clickFrames = std::max(1, static_cast<int32_t>(METRONOME_CLICK_SECONDS * static_cast<float>(sampleRate)));

    mRemainingFrames = clickFrames;
    mPhase = 0.0f;
    mPhaseIncrement = TWO_PI * frequency / static_cast<float>(sampleRate);
    mLevel = METRONOME_GAIN;
    mDecay = std::pow(METRONOME_CLICK_END_LEVEL, 1.0f / static_cast<float>(clickFrames));
}

void Metronome::render(float* interleaved, int32_t frames, int32_t sampleRate)
{
    auto nextClick = 0;

    for (int32_t i = 0; i < frames; i++) {
        while (nextClick < mPendingCount && mPending[nextClick].mFrameOffset <= i) {
            startClick(mPending[nextClick].mIsAccent, sampleRate);
            nextClick++;
        }

        if (mRemainingFrames <= 0) {
            continue;
        }

        auto sample = std::sin(mPhase) * mLevel;
        interleaved[i * STEREO_CHANNEL_COUNT] += sample;
        interleaved[i * STEREO_CHANNEL_COUNT + 1] += sample;

        mPhase += mPhaseIncrement;

        if (mPhase >= TWO_PI) {
            mPhase -= TWO_PI;
        }

        mLevel *= mDecay;
        mRemainingFrames--;
    }

    mPendingCount = 0;
}

} // namespace aaphost
