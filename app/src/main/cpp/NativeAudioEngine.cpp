#include "NativeAudioEngine.h"
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
      mIntermediateStereoBuffer(framesPerCallback * channelCount, 0.0f),
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

    // Activate all loaded plugins
    for (int32_t i = 0; i < mNumSlots; i++) {
        std::lock_guard<std::mutex> lock(mSlots[i]->mMutex);

        if (mSlots[i]->mInstance != nullptr) {
            mSlots[i]->refreshPorts();

            if (mSlots[i]->mInstance->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_INACTIVE) {
                mSlots[i]->mInstance->activate();
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
        std::lock_guard<std::mutex> lock(mSlots[i]->mMutex);

        if (mSlots[i]->mInstance != nullptr) {
            if (mSlots[i]->mInstance->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_ACTIVE) {
                mSlots[i]->mInstance->deactivate();
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
        LOGI("Slot %d plugin set: %p (instanceId=%d, inPorts=%zu, outPorts=%zu, state=%d)",
             slotIndex, instance, instanceId,
             mSlots[slotIndex]->mAudioInPorts.size(), mSlots[slotIndex]->mAudioOutPorts.size(),
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
    std::lock_guard<std::mutex> lock(mSampleAudioMutex);
    mSampleAudioData.assign(data, data + sizeInFloats);
    mSampleAudioPos.store(0);
    mIsPlayingSampleAudio.store(false);
    LOGI("Sample audio loaded into native engine (%zu samples)", sizeInFloats);
}

void NativeAudioEngine::playSampleAudio()
{
    if (mNumSlots > 0) {
        std::lock_guard<std::mutex> lock(mSlots[0]->mMutex);

        if (mSlots[0]->mInstance != nullptr && !mSlots[0]->mIsBypassed.load()) {
            if (mSlots[0]->mInstance->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_ACTIVE) {
                return;
            }
        }
    }

    mSampleAudioPos.store(0);
    mIsPlayingSampleAudio.store(true);
}

void NativeAudioEngine::sendUmpToSlot(int32_t slotIndex, const uint8_t* data, size_t size)
{
    if (slotIndex < 0 || slotIndex >= mNumSlots || data == nullptr || size == 0) {
        return;
    }

    std::lock_guard<std::mutex> lock(mSlots[slotIndex]->mMutex);
    auto inst = mSlots[slotIndex]->mInstance;

    if (inst != nullptr) {
        inst->addEventUmpInput((void*) data, size);
    }
}

oboe::DataCallbackResult NativeAudioEngine::onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames)
{
    auto outStream = static_cast<float*>(audioData);
    auto numSamples = static_cast<size_t>(numFrames * mChannelCount);

    if (mIntermediateStereoBuffer.size() < numSamples) {
        mIntermediateStereoBuffer.resize(numSamples, 0.0f);
    }

    struct timespec start_total, end_total;
    clock_gettime(CLOCK_MONOTONIC, &start_total);

    // ----------------------------------------------------
    // STEP 1: SLOT 0 (SYNTH / SAMPLE AUDIO)
    // ----------------------------------------------------
    struct timespec slot_start, slot_end;
    clock_gettime(CLOCK_MONOTONIC, &slot_start);

    bool renderedSlot0 = false;

    if (mNumSlots > 0) {
        std::lock_guard<std::mutex> lock(mSlots[0]->mMutex);
        auto inst0 = mSlots[0]->mInstance;

        if (inst0 != nullptr && !mSlots[0]->mIsBypassed.load()) {
            if (inst0->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_INACTIVE) {
                inst0->activate();
            }

            if (inst0->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_ACTIVE) {
                inst0->process(numFrames, 0);
                auto aapBuffer = inst0->getAudioPluginBuffer();

                if (mSlots[0]->mAudioOutPorts.empty()) {
                    mSlots[0]->refreshPorts();
                }

                const auto& outPorts = mSlots[0]->mAudioOutPorts;

                if (outPorts.size() >= 2) {
                    auto outL = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, outPorts[0]));
                    auto outR = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, outPorts[1]));

                    if (outL != nullptr && outR != nullptr) {
                        for (int32_t i = 0; i < numFrames; i++) {
                            mIntermediateStereoBuffer[i * 2 + 0] = outL[i];
                            mIntermediateStereoBuffer[i * 2 + 1] = outR[i];
                        }

                        renderedSlot0 = true;
                    }
                } else if (outPorts.size() == 1) {
                    auto outL = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, outPorts[0]));

                    if (outL != nullptr) {
                        for (int32_t i = 0; i < numFrames; i++) {
                            mIntermediateStereoBuffer[i * 2 + 0] = outL[i];
                            mIntermediateStereoBuffer[i * 2 + 1] = outL[i];
                        }

                        renderedSlot0 = true;
                    }
                }
            }
        }
    }

    if (!renderedSlot0) {
        if (mIsPlayingSampleAudio.load()) {
            std::lock_guard<std::mutex> lock(mSampleAudioMutex);
            auto totalSamples = mSampleAudioData.size();
            auto currentPos = mSampleAudioPos.load();

            for (int32_t i = 0; i < numFrames; i++) {
                if (currentPos + 1 < totalSamples) {
                    mIntermediateStereoBuffer[i * 2 + 0] = mSampleAudioData[currentPos++];
                    mIntermediateStereoBuffer[i * 2 + 1] = mSampleAudioData[currentPos++];
                } else {
                    mIntermediateStereoBuffer[i * 2 + 0] = 0.0f;
                    mIntermediateStereoBuffer[i * 2 + 1] = 0.0f;
                    mIsPlayingSampleAudio.store(false);
                    currentPos = 0;
                }
            }

            mSampleAudioPos.store(currentPos);
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

        std::lock_guard<std::mutex> lock(mSlots[slotIdx]->mMutex);
        auto inst = mSlots[slotIdx]->mInstance;

        if (inst != nullptr && !mSlots[slotIdx]->mIsBypassed.load()) {
            if (inst->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_INACTIVE) {
                inst->activate();
            }

            if (inst->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_ACTIVE) {
                if (mSlots[slotIdx]->mAudioInPorts.empty() || mSlots[slotIdx]->mAudioOutPorts.empty()) {
                    mSlots[slotIdx]->refreshPorts();
                }

                auto aapBuffer = inst->getAudioPluginBuffer();
                const auto& inPorts = mSlots[slotIdx]->mAudioInPorts;
                const auto& outPorts = mSlots[slotIdx]->mAudioOutPorts;

                if (!inPorts.empty() && !outPorts.empty() && aapBuffer != nullptr) {
                    // Copy intermediate stereo buffer into effect inputs
                    if (inPorts.size() >= 2) {
                        auto inL = static_cast<float*>(aapBuffer->get_buffer(aapBuffer, inPorts[0]));
                        auto inR = static_cast<float*>(aapBuffer->get_buffer(aapBuffer, inPorts[1]));

                        if (inL != nullptr && inR != nullptr) {
                            for (int32_t i = 0; i < numFrames; i++) {
                                inL[i] = mIntermediateStereoBuffer[i * 2 + 0];
                                inR[i] = mIntermediateStereoBuffer[i * 2 + 1];
                            }
                        }
                    } else if (inPorts.size() == 1) {
                        auto inM = static_cast<float*>(aapBuffer->get_buffer(aapBuffer, inPorts[0]));

                        if (inM != nullptr) {
                            for (int32_t i = 0; i < numFrames; i++) {
                                inM[i] = 0.5f * (mIntermediateStereoBuffer[i * 2 + 0] + mIntermediateStereoBuffer[i * 2 + 1]);
                            }
                        }
                    }

                    // Process plugin
                    inst->process(numFrames, 0);

                    // Read effect outputs back into intermediate stereo buffer
                    if (outPorts.size() >= 2) {
                        auto outL = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, outPorts[0]));
                        auto outR = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, outPorts[1]));

                        if (outL != nullptr && outR != nullptr) {
                            for (int32_t i = 0; i < numFrames; i++) {
                                mIntermediateStereoBuffer[i * 2 + 0] = outL[i];
                                mIntermediateStereoBuffer[i * 2 + 1] = outR[i];
                            }
                        }
                    } else if (outPorts.size() == 1) {
                        auto outM = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, outPorts[0]));

                        if (outM != nullptr) {
                            for (int32_t i = 0; i < numFrames; i++) {
                                mIntermediateStereoBuffer[i * 2 + 0] = outM[i];
                                mIntermediateStereoBuffer[i * 2 + 1] = outM[i];
                            }
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
