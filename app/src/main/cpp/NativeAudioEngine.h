#pragma once

#include <oboe/Oboe.h>
#include <aap/core/host/plugin-instance.h>
#include <aap/core/host/plugin-host.h>
#include <atomic>
#include <vector>
#include <mutex>
#include <memory>
#include <time.h>
#include <android/log.h>

namespace aaphost
{

constexpr int32_t DEFAULT_NUM_RACK_SLOTS = 3;

struct RackSlot
{
    std::mutex mMutex;
    aap::PluginInstance* mInstance{nullptr};
    std::atomic<bool> mIsBypassed{false};
    std::vector<int32_t> mAudioInPorts;
    std::vector<int32_t> mAudioOutPorts;
    std::atomic<float> mCpuLoad{0.0f};

    RackSlot() = default;

    void setInstance(aap::PluginInstance* inst)
    {
        std::lock_guard<std::mutex> lock(mMutex);
        mInstance = inst;
        mAudioInPorts.clear();
        mAudioOutPorts.clear();

        if (mInstance != nullptr) {
            refreshPorts();
        }
    }

    void refreshPorts()
    {
        mAudioInPorts.clear();
        mAudioOutPorts.clear();

        if (mInstance != nullptr) {
            int32_t numPorts = mInstance->getNumPorts();

            for (int32_t i = 0; i < numPorts; i++) {
                auto port = mInstance->getPort(i);

                if (port != nullptr && port->getContentType() == AAP_CONTENT_TYPE_AUDIO) {
                    if (port->getPortDirection() == AAP_PORT_DIRECTION_INPUT) {
                        mAudioInPorts.push_back(i);
                    } else if (port->getPortDirection() == AAP_PORT_DIRECTION_OUTPUT) {
                        mAudioOutPorts.push_back(i);
                    }
                }
            }
        }
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

    // Sample audio test playback
    std::mutex mSampleAudioMutex;
    std::vector<float> mSampleAudioData;
    std::atomic<size_t> mSampleAudioPos{0};
    std::atomic<bool> mIsPlayingSampleAudio{false};

    // Performance & CPU tracking
    double mBufferDurationNs;
    std::atomic<float> mTotalCpuLoad{0.0f};
    double mSmoothedTotalLoad{0.0};
    std::vector<double> mSmoothedSlotLoad;
};

} // namespace aaphost
