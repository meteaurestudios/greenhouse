#include "NativeAudioEngine.h"
#include "ScopedNoDenormals.h"
#include "AudioSimd.h"
#include <algorithm>
#include <cstring>

#define LOG_TAG "AAPHostEngineNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace aaphost
{

NativeAudioEngine::NativeAudioEngine(int32_t sampleRate, int32_t framesPerCallback, int32_t channelCount, int32_t numSlots)
    : mSampleRate(sampleRate),
      mFramesPerCallback(framesPerCallback),
      mChannelCount(channelCount),
      mNumSlots(std::max(1, numSlots)),
      mIntermediateStereoBuffer(std::max<size_t>(static_cast<size_t>(framesPerCallback * channelCount * 4), 8192), 0.0f),
      mSmoothedSlotLoad(std::max(1, numSlots), 0.0)
{
    mBufferDurationNs = (static_cast<double>(framesPerCallback) / static_cast<double>(sampleRate)) * 1e9;

    mSlots.reserve(mNumSlots);

    for (int32_t i = 0; i < mNumSlots; i++) {
        mSlots.push_back(std::make_unique<RackSlot>());
    }

    LOGI("NativeAudioEngine created: sampleRate=%d, framesPerCallback=%d, channelCount=%d, numSlots=%d",
         sampleRate, framesPerCallback, channelCount, mNumSlots);
}

NativeAudioEngine::~NativeAudioEngine()
{
    pause();
    LOGI("NativeAudioEngine destroyed");
}

bool NativeAudioEngine::start()
{
    if (mIsProcessing.load()) {
        return true;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(mChannelCount)
        ->setSampleRate(mSampleRate)
        ->setFramesPerCallback(mFramesPerCallback)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    auto result = builder.openStream(mStream);

    if (result != oboe::Result::OK) {
        LOGE("Failed to open Oboe audio stream: %s", oboe::convertToText(result));
        return false;
    }

    // Activate all loaded plugins on control thread
    for (int32_t i = 0; i < mNumSlots; i++) {
        auto inst = mSlots[i]->mInstance.load(std::memory_order_acquire);

        if (inst != nullptr) {
            mSlots[i]->refreshPorts(inst);

            if (inst->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_INACTIVE) {
                inst->activate();
            }
        }
    }

    result = mStream->requestStart();

    if (result != oboe::Result::OK) {
        LOGE("Failed to start Oboe audio stream: %s", oboe::convertToText(result));
        mStream->close();
        mStream.reset();
        return false;
    }

    mIsProcessing.store(true);
    LOGI("Native Audio Engine started successfully (Buffer Latency: %.2f ms)",
         (static_cast<float>(mFramesPerCallback) / static_cast<float>(mSampleRate)) * 1000.0f);
    return true;
}

void NativeAudioEngine::pause()
{
    if (!mIsProcessing.load()) {
        return;
    }

    mIsProcessing.store(false);

    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }

    for (int32_t i = 0; i < mNumSlots; i++) {
        auto inst = mSlots[i]->mInstance.load(std::memory_order_acquire);

        if (inst != nullptr) {
            if (inst->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_ACTIVE) {
                inst->deactivate();
            }
        }
    }

    LOGI("Native Audio Engine paused");
}

void NativeAudioEngine::onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error)
{
    LOGW("Oboe stream closed with error: %s", oboe::convertToText(error));

    if (mIsProcessing.load()) {
        mIsProcessing.store(false);
        start();
    }
}

void NativeAudioEngine::setSlotPlugin(int32_t slotIndex, aap::PluginClient* client, int32_t instanceId)
{
    if (slotIndex < 0 || slotIndex >= mNumSlots) {
        return;
    }

    aap::PluginInstance* instance = nullptr;

    if (client != nullptr && instanceId >= 0) {
        instance = client->getInstanceById(instanceId);
    }

    mSlots[slotIndex]->setInstance(instance);

    if (instance != nullptr) {
        LOGI("Slot %d plugin set: %p (instanceId=%d, inPorts=%d, outPorts=%d, state=%d)",
             slotIndex, instance, instanceId,
             mSlots[slotIndex]->mInPortCount.load(std::memory_order_relaxed),
             mSlots[slotIndex]->mOutPortCount.load(std::memory_order_relaxed),
             static_cast<int>(instance->getInstanceState()));

        if (mIsProcessing.load()) {
            if (instance->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_INACTIVE) {
                instance->activate();
            }
        }
    } else {
        LOGI("Slot %d cleared", slotIndex);
    }
}

void NativeAudioEngine::setSlotBypassed(int32_t slotIndex, bool bypassed)
{
    if (slotIndex >= 0 && slotIndex < mNumSlots) {
        mSlots[slotIndex]->mIsBypassed.store(bypassed);
    }
}

void NativeAudioEngine::setSampleAudioData(const float* data, size_t sizeInFloats)
{
    mSampleAudioData.assign(data, data + sizeInFloats);
    mSampleAudioDataSize.store(sizeInFloats, std::memory_order_release);
    mSampleAudioDataPtr.store(mSampleAudioData.data(), std::memory_order_release);
    mSampleAudioPos.store(0, std::memory_order_release);
    mIsPlayingSampleAudio.store(false, std::memory_order_release);
    LOGI("Sample audio loaded into native engine (%zu samples)", sizeInFloats);
}

void NativeAudioEngine::playSampleAudio()
{
    if (mNumSlots > 0) {
        auto inst0 = mSlots[0]->mInstance.load(std::memory_order_acquire);

        if (inst0 != nullptr && !mSlots[0]->mIsBypassed.load(std::memory_order_relaxed)) {
            if (inst0->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_ACTIVE) {
                return;
            }
        }
    }

    mSampleAudioPos.store(0, std::memory_order_release);
    mIsPlayingSampleAudio.store(true, std::memory_order_release);
}

void NativeAudioEngine::sendUmpToSlot(int32_t slotIndex, const uint8_t* data, size_t size)
{
    if (slotIndex < 0 || slotIndex >= mNumSlots || data == nullptr || size == 0) {
        return;
    }

    auto inst = mSlots[slotIndex]->mInstance.load(std::memory_order_acquire);

    if (inst != nullptr) {
        inst->addEventUmpInput((void*) data, size);
    }
}

oboe::DataCallbackResult NativeAudioEngine::onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames)
{
    ScopedNoDenormals noDenormals;

    auto outStream = static_cast<float*>(audioData);
    auto numSamples = static_cast<size_t>(numFrames * mChannelCount);

    struct timespec start_total, end_total;
    clock_gettime(CLOCK_MONOTONIC, &start_total);

    // ----------------------------------------------------
    // STEP 1: SLOT 0 (SYNTH / SAMPLE AUDIO)
    // ----------------------------------------------------
    struct timespec slot_start, slot_end;
    clock_gettime(CLOCK_MONOTONIC, &slot_start);

    bool renderedSlot0 = false;

    if (mNumSlots > 0) {
        auto inst0 = mSlots[0]->mInstance.load(std::memory_order_acquire);

        if (inst0 != nullptr && !mSlots[0]->mIsBypassed.load(std::memory_order_relaxed)) {
            if (inst0->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_ACTIVE) {
                inst0->process(numFrames, 0);
                auto aapBuffer = inst0->getAudioPluginBuffer();

                auto outCount = mSlots[0]->mOutPortCount.load(std::memory_order_relaxed);
                auto out0 = mSlots[0]->mOutPort0.load(std::memory_order_relaxed);
                auto out1 = mSlots[0]->mOutPort1.load(std::memory_order_relaxed);

                if (outCount >= 2 && out0 >= 0 && out1 >= 0 && aapBuffer != nullptr) {
                    auto outL = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, out0));
                    auto outR = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, out1));

                    if (outL != nullptr && outR != nullptr) {
                        simd::interleaveStereo(outL, outR, mIntermediateStereoBuffer.data(), numFrames);
                        renderedSlot0 = true;
                    }
                } else if (outCount == 1 && out0 >= 0 && aapBuffer != nullptr) {
                    auto outL = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, out0));

                    if (outL != nullptr) {
                        simd::interleaveMonoToStereo(outL, mIntermediateStereoBuffer.data(), numFrames);
                        renderedSlot0 = true;
                    }
                }
            }
        }
    }

    if (!renderedSlot0) {
        if (mIsPlayingSampleAudio.load(std::memory_order_relaxed)) {
            auto sampleData = mSampleAudioDataPtr.load(std::memory_order_acquire);
            auto totalSamples = mSampleAudioDataSize.load(std::memory_order_relaxed);
            auto currentPos = mSampleAudioPos.load(std::memory_order_relaxed);

            if (sampleData != nullptr && totalSamples > 0) {
                auto remaining = totalSamples - currentPos;

                if (remaining >= numSamples) {
                    std::memcpy(mIntermediateStereoBuffer.data(), sampleData + currentPos, numSamples * sizeof(float));
                    currentPos += numSamples;
                } else {
                    std::memcpy(mIntermediateStereoBuffer.data(), sampleData + currentPos, remaining * sizeof(float));
                    std::fill(mIntermediateStereoBuffer.begin() + remaining, mIntermediateStereoBuffer.begin() + numSamples, 0.0f);
                    mIsPlayingSampleAudio.store(false, std::memory_order_relaxed);
                    currentPos = 0;
                }

                mSampleAudioPos.store(currentPos, std::memory_order_relaxed);
            } else {
                std::fill(mIntermediateStereoBuffer.begin(), mIntermediateStereoBuffer.begin() + numSamples, 0.0f);
            }
        } else {
            std::fill(mIntermediateStereoBuffer.begin(), mIntermediateStereoBuffer.begin() + numSamples, 0.0f);
        }
    }

    if (mNumSlots > 0) {
        clock_gettime(CLOCK_MONOTONIC, &slot_end);
        auto slot0_ns = (slot_end.tv_sec - slot_start.tv_sec) * 1e9 + (slot_end.tv_nsec - slot_start.tv_nsec);
        auto slot0_load = slot0_ns / mBufferDurationNs;
        mSmoothedSlotLoad[0] = (mSmoothedSlotLoad[0] * 0.9) + (slot0_load * 0.1);
        mSlots[0]->mCpuLoad.store(static_cast<float>(mSmoothedSlotLoad[0]));
    }

    // ----------------------------------------------------
    // STEP 2 & BEYOND: EFFECT PLUGINS (SLOT 1 to mNumSlots - 1)
    // ----------------------------------------------------
    for (int32_t slotIdx = 1; slotIdx < mNumSlots; slotIdx++) {
        clock_gettime(CLOCK_MONOTONIC, &slot_start);

        auto inst = mSlots[slotIdx]->mInstance.load(std::memory_order_acquire);

        if (inst != nullptr && !mSlots[slotIdx]->mIsBypassed.load(std::memory_order_relaxed)) {
            if (inst->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_ACTIVE) {
                auto inCount = mSlots[slotIdx]->mInPortCount.load(std::memory_order_relaxed);
                auto in0 = mSlots[slotIdx]->mInPort0.load(std::memory_order_relaxed);
                auto in1 = mSlots[slotIdx]->mInPort1.load(std::memory_order_relaxed);
                auto outCount = mSlots[slotIdx]->mOutPortCount.load(std::memory_order_relaxed);
                auto out0 = mSlots[slotIdx]->mOutPort0.load(std::memory_order_relaxed);
                auto out1 = mSlots[slotIdx]->mOutPort1.load(std::memory_order_relaxed);

                auto aapBuffer = inst->getAudioPluginBuffer();

                if (inCount > 0 && outCount > 0 && aapBuffer != nullptr) {
                    // Copy intermediate stereo buffer into effect inputs (SIMD deinterleaving)
                    if (inCount >= 2 && in0 >= 0 && in1 >= 0) {
                        auto inL = static_cast<float*>(aapBuffer->get_buffer(aapBuffer, in0));
                        auto inR = static_cast<float*>(aapBuffer->get_buffer(aapBuffer, in1));

                        if (inL != nullptr && inR != nullptr) {
                            simd::deinterleaveStereo(mIntermediateStereoBuffer.data(), inL, inR, numFrames);
                        }
                    } else if (inCount == 1 && in0 >= 0) {
                        auto inM = static_cast<float*>(aapBuffer->get_buffer(aapBuffer, in0));

                        if (inM != nullptr) {
                            simd::deinterleaveStereoToMono(mIntermediateStereoBuffer.data(), inM, numFrames);
                        }
                    }

                    // Process plugin
                    inst->process(numFrames, 0);

                    // Read effect outputs back into intermediate stereo buffer (SIMD interleaving)
                    if (outCount >= 2 && out0 >= 0 && out1 >= 0) {
                        auto outL = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, out0));
                        auto outR = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, out1));

                        if (outL != nullptr && outR != nullptr) {
                            simd::interleaveStereo(outL, outR, mIntermediateStereoBuffer.data(), numFrames);
                        }
                    } else if (outCount == 1 && out0 >= 0) {
                        auto outM = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, out0));

                        if (outM != nullptr) {
                            simd::interleaveMonoToStereo(outM, mIntermediateStereoBuffer.data(), numFrames);
                        }
                    }
                }
            }
        }

        clock_gettime(CLOCK_MONOTONIC, &slot_end);
        auto slot_ns = (slot_end.tv_sec - slot_start.tv_sec) * 1e9 + (slot_end.tv_nsec - slot_start.tv_nsec);
        auto slot_load = slot_ns / mBufferDurationNs;
        mSmoothedSlotLoad[slotIdx] = (mSmoothedSlotLoad[slotIdx] * 0.9) + (slot_load * 0.1);
        mSlots[slotIdx]->mCpuLoad.store(static_cast<float>(mSmoothedSlotLoad[slotIdx]));
    }

    // ----------------------------------------------------
    // STEP 4: OUTPUT AUDIO TO OBOE STREAM
    // ----------------------------------------------------
    std::memcpy(outStream, mIntermediateStereoBuffer.data(), numSamples * sizeof(float));

    clock_gettime(CLOCK_MONOTONIC, &end_total);
    auto total_elapsed_ns = (end_total.tv_sec - start_total.tv_sec) * 1e9 + (end_total.tv_nsec - start_total.tv_nsec);
    auto instant_total_load = total_elapsed_ns / mBufferDurationNs;
    mSmoothedTotalLoad = (mSmoothedTotalLoad * 0.9) + (instant_total_load * 0.1);
    mTotalCpuLoad.store(static_cast<float>(mSmoothedTotalLoad));

    return oboe::DataCallbackResult::Continue;
}

} // namespace aaphost
