#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>

// Stand-in for the Oboe-based engine, without a device: the tests drive the audio thread themselves
// through renderCallback(), so it is never running when the control thread waits for it, and every
// retired buffer can be freed at once.
namespace aaphost
{

constexpr int32_t STEREO_CHANNEL_COUNT = 2;
constexpr int32_t DEFAULT_SAMPLE_RATE = 48000;
constexpr int32_t STUB_FRAMES_PER_BURST = 192;

class OboeEngine
{
public:
    OboeEngine() = default;
    virtual ~OboeEngine() = default;

    OboeEngine(const OboeEngine&) = delete;
    OboeEngine& operator=(const OboeEngine&) = delete;

    bool openStream()
    {
        std::lock_guard<std::mutex> lock(mControlMutex);
        openStreamLocked();
        return true;
    }

    bool startStreaming()
    {
        std::lock_guard<std::mutex> lock(mControlMutex);
        openStreamLocked();

        if (!mShouldStream.load()) {
            streamStarting();
            mShouldStream.store(true);
        }

        return true;
    }

    void stopStreaming()
    {
        std::lock_guard<std::mutex> lock(mControlMutex);
        stopLocked();
    }

    void closeStream()
    {
        std::lock_guard<std::mutex> lock(mControlMutex);
        stopLocked();
        mIsOpen = false;
    }

    bool isStreaming() const
    {
        return mShouldStream.load(std::memory_order_relaxed);
    }

    int32_t getSampleRate() const
    {
        return mSampleRate.load(std::memory_order_relaxed);
    }

    int32_t getFramesPerBurst() const
    {
        return mIsOpen ? STUB_FRAMES_PER_BURST : 0;
    }

    uint64_t getAudioThreadMarker() const
    {
        return 0;
    }

    bool hasAudioThreadPassed(uint64_t marker) const
    {
        return true;
    }

    /** The rate the "device" runs at from the next time the stream opens. */
    void setDeviceSampleRate(int32_t sampleRate)
    {
        mDeviceSampleRate = sampleRate;
    }

    /** Runs one audio callback. */
    void renderCallback(float* output, int32_t numFrames)
    {
        process(output, numFrames);
    }

protected:
    virtual void prepareToPlay(int32_t sampleRate, int32_t framesPerBurst)
    {
    }

    virtual void streamStarting()
    {
    }

    virtual void process(float* output, int32_t numFrames)
    {
    }

    virtual void flushState()
    {
    }

    virtual int32_t estimateWorkload() const
    {
        return 0;
    }

    void setMinimumBufferSize(int32_t frames)
    {
    }

    void waitForAudioThreadQuiescence() const
    {
    }

    std::mutex mControlMutex;

private:
    void openStreamLocked()
    {
        if (mIsOpen) {
            return;
        }

        mIsOpen = true;
        mSampleRate.store(mDeviceSampleRate);
        prepareToPlay(mDeviceSampleRate, STUB_FRAMES_PER_BURST);
    }

    void stopLocked()
    {
        if (mShouldStream.exchange(false)) {
            flushState();
        }
    }

    bool mIsOpen{false};
    int32_t mDeviceSampleRate{DEFAULT_SAMPLE_RATE};
    std::atomic<bool> mShouldStream{false};
    std::atomic<int32_t> mSampleRate{0};
};

} // namespace aaphost
