#pragma once

#include "engine/OboeEngine.h"
#include "slot/SlotProcessor.h"
#include <aap/core/host/plugin-instance.h>
#include <aap/core/host/plugin-host.h>
#include <memory>

namespace greenhouse
{

// 0 = no host-imposed deadline for the remote process() call
constexpr int32_t PLUGIN_PROCESS_TIMEOUT_NS = 0;

/** Indices of the first two audio input / output ports of a plugin, and of its MIDI2 input. */
struct AudioPorts
{
    int32_t mIn[STEREO_CHANNEL_COUNT]{-1, -1};
    int32_t mOut[STEREO_CHANNEL_COUNT]{-1, -1};
    int32_t mInCount{0};  // capped at STEREO_CHANNEL_COUNT
    int32_t mOutCount{0}; // capped at STEREO_CHANNEL_COUNT
    // The first MIDI2 input, where aap-core also merges the queued live input; -1 if none
    int32_t mMidiIn{-1};
};

/**
 * An AAP plugin instance in a rack slot. It does not own the instance: the host destroys it once
 * the processor is out of the rack (RackEngine::setSlotProcessor() returned).
 */
class AapSlotProcessor final : public SlotProcessor
{
public:
    /** sampleRate: the rate the plugin was prepared at. Null if the client has no such instance. */
    static std::unique_ptr<AapSlotProcessor> create(aap::PluginClient* client, int32_t instanceId, int32_t sampleRate);

    AapSlotProcessor(aap::PluginInstance* instance, int32_t sampleRate);

    void prepareToPlay(int32_t sampleRate, int32_t maxBlockFrames) override;
    void activate() override;
    void deactivate() override;
    int32_t getAudioInputCount() const override;
    int32_t getAudioOutputCount() const override;
    int32_t getEventCapacityWords() const override;
    bool queueEvents(const uint8_t* data, int32_t size) override;

    bool isActive() const override;
    bool canProcess(int32_t frames, int32_t sampleRate) const override;
    bool process(const float* input, int32_t frames, const uint32_t* events, int32_t eventWordCount, SlotOutput& out) override;

private:
    void writeSequencerEvents(aap_buffer_t* buffer, const uint32_t* events, int32_t eventWordCount);

    aap::PluginInstance* mInstance;
    AudioPorts mPorts;
    int32_t mPreparedSampleRate;
};

} // namespace greenhouse
