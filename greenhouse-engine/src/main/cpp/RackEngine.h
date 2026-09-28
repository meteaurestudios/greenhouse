#pragma once

#include "engine/OboeEngine.h"
#include <aap/core/host/plugin-instance.h>
#include <aap/core/host/plugin-host.h>
#include <array>

namespace aaphost
{

constexpr int32_t MAX_RACK_SLOTS = 16;
constexpr int32_t INSTRUMENT_SLOT_INDEX = 0;
constexpr int32_t MIN_DSP_BLOCK_FRAMES = 1;
// Plugins are prepared with this many frames (MAX_HOST_BUFFER_FRAMES on the Kotlin side)
constexpr int32_t MAX_DSP_BLOCK_FRAMES = 4096;
constexpr int32_t DEFAULT_FRAMES_PER_CALLBACK = 256;
constexpr int32_t BUFFER_SIZE_SAFETY_FACTOR = 2;
// 0 = no host-imposed deadline for the remote process() call
constexpr int32_t PLUGIN_PROCESS_TIMEOUT_NS = 0;
constexpr double NANOS_PER_SECOND = 1e9;
constexpr double DSP_LOAD_EMA_PREVIOUS_WEIGHT = 0.85;
constexpr double DSP_LOAD_EMA_CURRENT_WEIGHT = 1.0 - DSP_LOAD_EMA_PREVIOUS_WEIGHT;
constexpr float METER_DECAY_FACTOR = 0.80f;
constexpr float METER_MIN_THRESHOLD = 0.0001f;
// ADPF workload units: fixed engine cost plus one unit per active plugin slot
constexpr int32_t ADPF_BASE_WORKLOAD = 1;
constexpr int32_t ADPF_WORKLOAD_PER_ACTIVE_SLOT = 1;

/** Indices of the first two audio input / output ports of a plugin. */
struct AudioPorts
{
    int32_t mIn[STEREO_CHANNEL_COUNT]{-1, -1};
    int32_t mOut[STEREO_CHANNEL_COUNT]{-1, -1};
    int32_t mInCount{0};  // capped at STEREO_CHANNEL_COUNT
    int32_t mOutCount{0}; // capped at STEREO_CHANNEL_COUNT
};

struct RackSlot
{
    // Published to the audio thread. mPorts is only written while mInstance is null and the
    // audio thread is quiescent, so the audio thread always sees ports matching the instance.
    std::atomic<aap::PluginInstance*> mInstance{nullptr};
    AudioPorts mPorts;
    int32_t mPreparedSampleRate{0};
    std::atomic<bool> mIsBypassed{false};

    // Written by the audio thread, read by the UI
    std::atomic<float> mCpuLoad{0.0f};
    std::atomic<float> mPeakL{0.0f};
    std::atomic<float> mPeakR{0.0f};
    std::atomic<int32_t> mInvalidBlocks{0}; // output blocks dropped for NaN or infinite samples since the plugin was set

    // Audio thread only
    double mSmoothedLoad{0.0};
};

/**
 * Serial rack of AAP plugins: the instrument in slot 0, then effect slots that process its output
 * in place. Plugins render in fixed-size blocks that are independent of the device burst size.
 */
class RackEngine : public OboeEngine
{
public:
    RackEngine() = default;
    ~RackEngine() override;

    /** Empties every slot and reopens the stream (not started), so getSampleRate() is known before plugins are prepared. */
    void configure(int32_t framesPerCallback, int32_t numSlots);

    /** Closes the stream and empties every slot, releasing the audio device and all plugin references. */
    void shutdown();

    void setFramesPerCallback(int32_t framesPerCallback);

    /**
     * Blocks until the audio thread no longer uses the previous instance, so it can be destroyed.
     * sampleRate is the rate the plugin was prepared at: the slot stays silent while it differs from
     * the stream's, until the host reloads the plugin at getSampleRate().
     */
    void setSlotPlugin(int32_t slotIndex, aap::PluginClient* client, int32_t instanceId, int32_t sampleRate);
    void setSlotBypassed(int32_t slotIndex, bool bypassed);
    void sendUmpToSlot(int32_t slotIndex, const uint8_t* data, int32_t size);

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
    bool renderSlot(RackSlot& slot, bool isInstrument, int32_t frames);

    std::array<RackSlot, MAX_RACK_SLOTS> mSlots;
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

} // namespace aaphost
