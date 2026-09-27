#pragma once

#include <oboe/Oboe.h>
#include <oboe/LatencyTuner.h>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>

namespace aaphost
{

constexpr int32_t STEREO_CHANNEL_COUNT = 2;
constexpr int32_t DEFAULT_SAMPLE_RATE = 48000;
constexpr std::chrono::microseconds QUIESCENCE_POLL_INTERVAL{200};
constexpr std::chrono::milliseconds MMAP_RETRY_DELAY{100};

/**
 * Owns a low-latency stereo float Oboe output stream and its lifecycle: opening, starting and
 * stopping it, and reopening it after errors such as a device change. The stream runs at the
 * device's native sample rate (no resampling), which can change when the stream is reopened.
 * Subclasses provide the audio through the hooks below.
 *
 * Threading: the public methods may be called from any non-realtime thread and are serialized by
 * mControlMutex, which subclasses also use for their own control operations. The audio callback
 * never locks.
 *
 * Subclasses must call closeStream() in their destructor, so the callback stops before they are destroyed.
 */
class OboeEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback
{
public:
    ~OboeEngine() override = default;

    OboeEngine(const OboeEngine&) = delete;
    OboeEngine& operator=(const OboeEngine&) = delete;

    /** Opens the stream without starting it, so its sample rate is known. Returns false if the device could not be opened. */
    bool openStream();

    /** Opens the stream if needed and starts it. Returns false if the device could not be started. */
    bool startStreaming();

    /** Stops the stream but keeps it open, so it can restart quickly. */
    void stopStreaming();

    /** Stops the stream and releases the audio device. */
    void closeStream();

    /** Whether the stream is wanted running. It turns false if the stream cannot be restarted after an error. Lock-free. */
    bool isStreaming() const
    {
        return mShouldStream.load(std::memory_order_relaxed);
    }

    /** Native sample rate of the last opened stream, 0 before the first open. Lock-free. */
    int32_t getSampleRate() const
    {
        return mSampleRate.load(std::memory_order_relaxed);
    }

    /** Burst size of the last opened stream, 0 before the first open. Lock-free. */
    int32_t getFramesPerBurst() const
    {
        return mFramesPerBurst.load(std::memory_order_relaxed);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) final;
    void onErrorAfterClose(oboe::AudioStream* audioStream, oboe::Result error) final;

protected:
    OboeEngine() = default;

    /** The stream was just opened and is not started yet. mControlMutex is held. */
    virtual void prepareToPlay(int32_t sampleRate, int32_t framesPerBurst) = 0;

    /** The stream is about to start. mControlMutex is held. */
    virtual void streamStarting() = 0;

    /** Audio thread: fills numFrames of interleaved stereo. */
    virtual void process(float* output, int32_t numFrames) = 0;

    /** The stream is stopped and the audio thread is idle. mControlMutex is held. */
    virtual void flushState() = 0;

    /** Audio thread: ADPF workload hint for the upcoming process() call, so the CPU boosts before the work lands. 0 disables it. */
    virtual int32_t estimateWorkload() const
    {
        return 0;
    }

    /**
     * Lowest buffer size the latency tuner may use; it grows the buffer from there when underruns
     * occur. Applied when the stream opens, then at the next callback. Any thread.
     */
    void setMinimumBufferSize(int32_t frames);

    /**
     * Blocks until the audio thread is outside process(). Call it after unpublishing a pointer
     * that process() reads, before freeing what it points to.
     */
    void waitForAudioThreadQuiescence() const;

    std::mutex mControlMutex;

private:
    bool startLocked();
    oboe::Result startStreamLocked();
    bool openStreamLocked();
    void stopLocked();
    void closeStreamLocked();
    void setBufferSizeLocked(int32_t frames);
    void applyMinimumBufferSize();

    // Guarded by mControlMutex
    std::shared_ptr<oboe::AudioStream> mStream;

    // Created with the stream, before any callback, then used only by the audio thread (LatencyTuner is not thread-safe)
    std::unique_ptr<oboe::LatencyTuner> mLatencyTuner;
    int32_t mAppliedMinimumBufferSize{0};

    std::atomic<int32_t> mMinimumBufferSize{0};

    // Written under mControlMutex, read lock-free
    std::atomic<bool> mShouldStream{false}; // after an error, the stream is restarted only if it was streaming
    std::atomic<int32_t> mSampleRate{0};
    std::atomic<int32_t> mFramesPerBurst{0};

    // Odd while onAudioReady runs (see waitForAudioThreadQuiescence)
    std::atomic<uint64_t> mCallbackSequence{0};
};

} // namespace aaphost
