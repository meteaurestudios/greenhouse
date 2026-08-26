#pragma once

#include <oboe/Oboe.h>
#include <aap/core/host/plugin-instance.h>
#include <aap/core/host/plugin-host.h>
#include <atomic>
#include <vector>
#include <memory>
#include <time.h>
#include <android/log.h>

namespace aaphost
{

constexpr int32_t DEFAULT_NUM_RACK_SLOTS = 3;

struct RackSlot
{
    std::atomic<aap::PluginInstance*> mInstance{nullptr};
    std::atomic<bool> mIsBypassed{false};
    std::atomic<int32_t> mInPort0{-1};
    std::atomic<int32_t> mInPort1{-1};
    std::atomic<int32_t> mOutPort0{-1};
    std::atomic<int32_t> mOutPort1{-1};
    std::atomic<int32_t> mInPortCount{0};
    std::atomic<int32_t> mOutPortCount{0};
    std::atomic<float> mCpuLoad{0.0f};

    RackSlot() = default;

    void setInstance(aap::PluginInstance* inst)
    {
        if (inst == nullptr) {
            mInstance.store(nullptr, std::memory_order_release);
            mInPortCount.store(0, std::memory_order_release);
            mOutPortCount.store(0, std::memory_order_release);
            mInPort0.store(-1, std::memory_order_release);
            mInPort1.store(-1, std::memory_order_release);
            mOutPort0.store(-1, std::memory_order_release);
            mOutPort1.store(-1, std::memory_order_release);
            return;
        }

        refreshPorts(inst);
        mInstance.store(inst, std::memory_order_release);
    }

    void refreshPorts(aap::PluginInstance* inst)
    {
        if (inst == nullptr) {
            return;
        }

        int32_t inCount = 0;
        int32_t outCount = 0;
        int32_t in0 = -1;
        int32_t in1 = -1;
        int32_t out0 = -1;
        int32_t out1 = -1;

        int32_t numPorts = inst->getNumPorts();

        for (int32_t i = 0; i < numPorts; i++) {
            auto port = inst->getPort(i);

            if (port != nullptr && port->getContentType() == AAP_CONTENT_TYPE_AUDIO) {
                if (port->getPortDirection() == AAP_PORT_DIRECTION_INPUT) {
                    if (inCount == 0) {
                        in0 = i;
                    } else if (inCount == 1) {
                        in1 = i;
                    }

                    inCount++;
                } else if (port->getPortDirection() == AAP_PORT_DIRECTION_OUTPUT) {
                    if (outCount == 0) {
                        out0 = i;
                    } else if (outCount == 1) {
                        out1 = i;
                    }

                    outCount++;
                }
            }
        }

        mInPort0.store(in0, std::memory_order_release);
        mInPort1.store(in1, std::memory_order_release);
        mOutPort0.store(out0, std::memory_order_release);
        mOutPort1.store(out1, std::memory_order_release);
        mInPortCount.store(inCount, std::memory_order_release);
        mOutPortCount.store(outCount, std::memory_order_release);
    }
};

class NativeAudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback
{
public:
    NativeAudioEngine(int32_t sampleRate, int32_t framesPerCallback, int32_t channelCount = 2, int32_t numSlots = DEFAULT_NUM_RACK_SLOTS);
    virtual ~NativeAudioEngine();

    bool start();
    void pause();
    bool isProcessing() const
    {
        return mIsProcessing.load();
    }

    int32_t getSlotCount() const
    {
        return mNumSlots;
    }

    void setSlotPlugin(int32_t slotIndex, aap::PluginClient* client, int32_t instanceId);
    void setSlotBypassed(int32_t slotIndex, bool bypassed);
    void setSampleAudioData(const float* data, size_t sizeInFloats);
    void playSampleAudio();
    void sendUmpToSlot(int32_t slotIndex, const uint8_t* data, size_t size);

    float getTotalCpuLoad() const
    {
        return mTotalCpuLoad.load();
    }

    float getSlotCpuLoad(int32_t slotIndex) const
    {
        if (slotIndex >= 0 && slotIndex < mNumSlots) {
            return mSlots[slotIndex]->mCpuLoad.load();
        }

        return 0.0f;
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override;

private:
    int32_t mSampleRate;
    int32_t mFramesPerCallback;
    int32_t mChannelCount;
    int32_t mNumSlots;

    std::shared_ptr<oboe::AudioStream> mStream;
    std::atomic<bool> mIsProcessing{false};

    std::vector<std::unique_ptr<RackSlot>> mSlots;

    // Pre-allocated scratch buffers
    std::vector<float> mIntermediateStereoBuffer;

    // Sample audio test playback (lock-free)
    std::vector<float> mSampleAudioData;
    std::atomic<const float*> mSampleAudioDataPtr{nullptr};
    std::atomic<size_t> mSampleAudioDataSize{0};
    std::atomic<size_t> mSampleAudioPos{0};
    std::atomic<bool> mIsPlayingSampleAudio{false};

    // Performance & CPU tracking
    double mBufferDurationNs;
    std::atomic<float> mTotalCpuLoad{0.0f};
    double mSmoothedTotalLoad{0.0};
    std::vector<double> mSmoothedSlotLoad;
};

} // namespace aaphost
