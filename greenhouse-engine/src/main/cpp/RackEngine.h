#pragma once

#include "engine/OboeEngine.h"
#include "sequencer/MidiSequencer.h"
#include "slot/SlotProcessor.h"
#include <array>
#include <memory>

namespace greenhouse
{

constexpr int32_t MAX_RACK_SLOTS = 16;
constexpr int32_t INSTRUMENT_SLOT_INDEX = 0;
// The sequencer ignores the other slots
static_assert(INSTRUMENT_SLOT_INDEX < MAX_SEQUENCER_SLOTS, "the sequencer must cover the instrument slot");
constexpr int32_t MIN_DSP_BLOCK_FRAMES = 1;
// Plugins are prepared with this many frames (MAX_HOST_BUFFER_FRAMES on the Kotlin side)
constexpr int32_t MAX_DSP_BLOCK_FRAMES = 4096;
constexpr int32_t DEFAULT_FRAMES_PER_CALLBACK = 256;
constexpr int32_t BUFFER_SIZE_SAFETY_FACTOR = 2;
constexpr double NANOS_PER_SECOND = 1e9;
constexpr double DSP_LOAD_EMA_PREVIOUS_WEIGHT = 0.85;
constexpr double DSP_LOAD_EMA_CURRENT_WEIGHT = 1.0 - DSP_LOAD_EMA_PREVIOUS_WEIGHT;
constexpr float METER_DECAY_FACTOR = 0.80f;
constexpr float METER_MIN_THRESHOLD = 0.0001f;
// ADPF workload units: fixed engine cost plus one unit per active plugin slot
constexpr int32_t ADPF_BASE_WORKLOAD = 1;
constexpr int32_t ADPF_WORKLOAD_PER_ACTIVE_SLOT = 1;
// Host level applied to a slot's output (linear gain) and dry / wet mix (0 = dry only, 1 = wet only)
constexpr float UNITY_GAIN = 1.0f;
constexpr float SILENT_GAIN = 0.0f;
constexpr float MAX_SLOT_GAIN = 4.0f; // about +12 dB
constexpr float DRY_ONLY_MIX = 0.0f;
constexpr float WET_ONLY_MIX = 1.0f;

struct RackSlot
{
    // Owned by the control thread, published to the audio thread through mProcessor
    std::unique_ptr<SlotProcessor> mOwnedProcessor;
    std::atomic<SlotProcessor*> mProcessor{nullptr};
    std::atomic<bool> mIsBypassed{false};
    // Host settings of the slot, set by the host (setSlotProcessor leaves them alone). The audio thread ramps to them over a block.
    std::atomic<float> mGain{UNITY_GAIN};
    std::atomic<float> mMix{WET_ONLY_MIX};

    // Written by the audio thread, read by the UI
    std::atomic<float> mCpuLoad{0.0f};
    std::atomic<float> mPeakL{0.0f};
    std::atomic<float> mPeakR{0.0f};
    std::atomic<int32_t> mInvalidBlocks{0}; // output blocks dropped for NaN or infinite samples since the plugin was set

    // Audio thread only
    double mSmoothedLoad{0.0};
    float mCurrentGain{UNITY_GAIN};
    float mCurrentMix{WET_ONLY_MIX};
    bool mWasBypassed{false};
};

/**
 * Serial rack of slot processors (AAP plugins or DSP running in the engine): the instrument in
 * slot 0, then effect slots that process its output in place. Slots render in fixed-size blocks
 * that are independent of the device burst size.
 */
class RackEngine : public OboeEngine
{
public:
    RackEngine() = default;
    ~RackEngine() override;

    /** Empties every slot and reopens the stream (not started), so getSampleRate() is known before plugins are prepared. */
    void configure(int32_t framesPerCallback, int32_t numSlots);

    /** Closes the stream and empties every slot, releasing the audio device and destroying every slot processor. */
    void shutdown();

    void setFramesPerCallback(int32_t framesPerCallback);

    /**
     * Replaces the slot's processor; null empties the slot. Blocks until the audio thread no longer
     * uses the previous processor, then destroys it. Bypass is turned off; level and mix are kept.
     */
    void setSlotProcessor(int32_t slotIndex, std::unique_ptr<SlotProcessor> processor);
    void setSlotBypassed(int32_t slotIndex, bool bypassed);
    /** Linear gain applied to the slot's output, clamped to [SILENT_GAIN, MAX_SLOT_GAIN]. */
    void setSlotGain(int32_t slotIndex, float gain);
    /** Dry / wet balance of the slot, clamped to [DRY_ONLY_MIX, WET_ONLY_MIX]. */
    void setSlotMix(int32_t slotIndex, float mix);
    /** Queues MIDI for the slot's next block. The sequencer records its notes if it is recording. */
    void sendUmpToSlot(int32_t slotIndex, const uint8_t* data, int32_t size);

    MidiSequencer& getSequencer()
    {
        return mSequencer;
    }

    float getTotalCpuLoad() const;
    float getSlotCpuLoad(int32_t slotIndex) const;
    int32_t getSlotInvalidBlocks(int32_t slotIndex) const;
    void getAllSlotLevels(float* outLevels, int32_t maxSlots) const;

protected:
    void prepareToPlay(int32_t sampleRate, int32_t framesPerBurst) override;
    void streamStarting() override;
    void process(float* output, int32_t numFrames) override;
    void flushState() override;
    int32_t estimateWorkload() const override;

private:
    bool isValidSlot(int32_t slotIndex) const;
    void clearSlotsLocked();
    void updateMinimumBufferSizeLocked();

    void renderBlock(int32_t frames);
    bool renderSlot(int32_t slotIndex, int32_t frames);

    std::array<RackSlot, MAX_RACK_SLOTS> mSlots;
    MidiSequencer mSequencer{*this};
    std::atomic<int32_t> mNumSlots{0};
    std::atomic<int32_t> mFramesPerCallback{DEFAULT_FRAMES_PER_CALLBACK};

    // Audio thread state, reset by the control thread while the stream is not running.
    // mBlockBuffer holds the last rendered block (interleaved stereo); mBlockReadFrame is how much of it was consumed.
    int32_t mRenderSampleRate{DEFAULT_SAMPLE_RATE};
    std::array<float, MAX_DSP_BLOCK_FRAMES * STEREO_CHANNEL_COUNT> mBlockBuffer{};
    int32_t mBlockFrames{0};
    int32_t mBlockReadFrame{0};
    double mSmoothedTotalLoad{0.0};
    std::atomic<float> mTotalCpuLoad{0.0f};
};

/** The process-wide engine: the one the app drives through the JNI bindings. */
RackEngine& getEngine();

} // namespace greenhouse
