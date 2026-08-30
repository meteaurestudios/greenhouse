#pragma once

#include <oboe/Oboe.h>
#include <aap/core/host/plugin-instance.h>
#include <aap/core/host/plugin-host.h>
#include <atomic>
#include <vector>
#include <memory>
#include <time.h>
#include <unistd.h>
#include <android/log.h>

namespace aaphost
{

constexpr int32_t DEFAULT_NUM_RACK_SLOTS = 3;
constexpr int32_t DEFAULT_FRAMES_PER_CALLBACK = 256;
constexpr int32_t MIN_DSP_BLOCK_FRAMES = 1;
constexpr int32_t MAX_DSP_BLOCK_FRAMES = 4096;
constexpr size_t FIFO_CAPACITY_FRAMES = 16384;
constexpr int32_t FIFO_PRIME_BLOCKS = 2;
constexpr int32_t BUFFER_CAPACITY_SAFETY_FACTOR = 2;
constexpr double DSP_LOAD_EMA_PREVIOUS_WEIGHT = 0.85;
constexpr double DSP_LOAD_EMA_CURRENT_WEIGHT = 0.15;
constexpr uint64_t QUIESCENT_EPOCHS_TO_WAIT = 2;
constexpr useconds_t QUIESCENT_POLL_INTERVAL_US = 500;
constexpr int32_t MAX_QUIESCENT_WAIT_ATTEMPTS = 50; // 50 * 500us = 25ms maximum watchdog timeout

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
        mIsBypassed.store(false, std::memory_order_release);

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

    int32_t getFramesPerCallback() const
    {
        return mFramesPerCallback.load(std::memory_order_relaxed);
    }

    int32_t getFramesPerBurst() const
    {
        if (mStream) {
            return mStream->getFramesPerBurst();
        }

        return 0;
    }

    void setFramesPerCallback(int32_t framesPerCallback);

    void setSlotPlugin(int32_t slotIndex, aap::PluginClient* client, int32_t instanceId);
    void setSlotBypassed(int32_t slotIndex, bool bypassed);
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
    void renderDspBlock(int32_t blockFrames);
    void primeFifo();
    void pushFifo(const float* data, size_t frames);
    void pullFifo(float* outData, size_t frames);
    void resetFifo();

    int32_t mSampleRate;
    std::atomic<int32_t> mFramesPerCallback;
    int32_t mChannelCount;
    int32_t mNumSlots;

    std::shared_ptr<oboe::AudioStream> mStream;
    std::atomic<bool> mIsProcessing{false};

    std::vector<std::unique_ptr<RackSlot>> mSlots;

    // Pre-allocated scratch buffers
    std::vector<float> mIntermediateStereoBuffer;

    // Pre-allocated FIFO decoupled ring buffer
    static constexpr size_t FIFO_CAPACITY_FRAMES = 16384;
    std::vector<float> mFifoBuffer;
    size_t mFifoCapacityFrames{FIFO_CAPACITY_FRAMES};
    size_t mFifoReadPos{0};
    size_t mFifoWritePos{0};
    size_t mFifoAvailableFrames{0};

    // Realtime render epoch tracking for lock-free quiescent state synchronization
    std::atomic<uint64_t> mRenderEpoch{0};

    // Performance & CPU tracking
    std::atomic<float> mTotalCpuLoad{0.0f};
    double mSmoothedTotalLoad{0.0};
    std::vector<double> mSmoothedSlotLoad;
};

} // namespace aaphost
