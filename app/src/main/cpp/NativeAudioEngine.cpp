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
      mIntermediateStereoBuffer(static_cast<size_t>(MAX_DSP_BLOCK_FRAMES * channelCount), 0.0f),
      mFifoBuffer(static_cast<size_t>(FIFO_CAPACITY_FRAMES * channelCount), 0.0f),
      mSmoothedSlotLoad(std::max(1, numSlots), 0.0)
{
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

void NativeAudioEngine::resetFifo()
{
    mFifoReadPos = 0;
    mFifoWritePos = 0;
    mFifoAvailableFrames = 0;
    std::fill(mFifoBuffer.begin(), mFifoBuffer.end(), 0.0f);
}

void NativeAudioEngine::pushFifo(const float* data, size_t frames)
{
    if (data == nullptr || frames == 0) {
        return;
    }

    size_t samples = frames * mChannelCount;
    size_t totalBufferSamples = mFifoCapacityFrames * mChannelCount;
    size_t writeSamplePos = mFifoWritePos * mChannelCount;

    if (writeSamplePos + samples <= totalBufferSamples) {
        std::memcpy(mFifoBuffer.data() + writeSamplePos, data, samples * sizeof(float));
    } else {
        size_t firstChunkSamples = totalBufferSamples - writeSamplePos;
        size_t secondChunkSamples = samples - firstChunkSamples;

        std::memcpy(mFifoBuffer.data() + writeSamplePos, data, firstChunkSamples * sizeof(float));
        std::memcpy(mFifoBuffer.data(), data + firstChunkSamples, secondChunkSamples * sizeof(float));
    }

    mFifoWritePos = (mFifoWritePos + frames) % mFifoCapacityFrames;
    mFifoAvailableFrames = std::min(mFifoCapacityFrames, mFifoAvailableFrames + frames);
}

void NativeAudioEngine::pullFifo(float* outData, size_t frames)
{
    if (outData == nullptr || frames == 0) {
        return;
    }

    size_t framesToPull = std::min(frames, mFifoAvailableFrames);
    size_t samples = framesToPull * mChannelCount;
    size_t totalBufferSamples = mFifoCapacityFrames * mChannelCount;
    size_t readSamplePos = mFifoReadPos * mChannelCount;

    if (framesToPull > 0) {
        if (readSamplePos + samples <= totalBufferSamples) {
            std::memcpy(outData, mFifoBuffer.data() + readSamplePos, samples * sizeof(float));
        } else {
            size_t firstChunkSamples = totalBufferSamples - readSamplePos;
            size_t secondChunkSamples = samples - firstChunkSamples;

            std::memcpy(outData, mFifoBuffer.data() + readSamplePos, firstChunkSamples * sizeof(float));
            std::memcpy(outData + firstChunkSamples, mFifoBuffer.data(), secondChunkSamples * sizeof(float));
        }

        mFifoReadPos = (mFifoReadPos + framesToPull) % mFifoCapacityFrames;
        mFifoAvailableFrames -= framesToPull;
    }

    if (framesToPull < frames) {
        size_t missingFrames = frames - framesToPull;
        size_t missingSamples = missingFrames * mChannelCount;
        std::memset(outData + samples, 0, missingSamples * sizeof(float));
    }
}

void NativeAudioEngine::renderDspBlock(int32_t blockFrames)
{
    auto frames = std::clamp(blockFrames, MIN_DSP_BLOCK_FRAMES, MAX_DSP_BLOCK_FRAMES);
    auto numSamples = static_cast<size_t>(frames * mChannelCount);

    const double blockDurationNs = (mSampleRate > 0 && frames > 0)
        ? (static_cast<double>(frames) / static_cast<double>(mSampleRate)) * 1e9
        : 1e9;

    struct timespec start_total, end_total;
    clock_gettime(CLOCK_MONOTONIC, &start_total);

    // ----------------------------------------------------
    // STEP 1: SLOT 0 (SYNTH / GENERATOR)
    // ----------------------------------------------------
    struct timespec slot_start, slot_end;
    clock_gettime(CLOCK_MONOTONIC, &slot_start);

    bool renderedSlot0 = false;

    if (mNumSlots > 0) {
        auto inst0 = mSlots[0]->mInstance.load(std::memory_order_acquire);

        if (inst0 != nullptr && !mSlots[0]->mIsBypassed.load(std::memory_order_relaxed)) {
            if (inst0->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_ACTIVE) {
                auto aapBuffer = inst0->getAudioPluginBuffer();

                if (aapBuffer != nullptr) {
                    inst0->process(frames, 0);

                    if (inst0->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_ACTIVE) {
                        auto outCount = mSlots[0]->mOutPortCount.load(std::memory_order_relaxed);
                        auto out0 = mSlots[0]->mOutPort0.load(std::memory_order_relaxed);
                        auto out1 = mSlots[0]->mOutPort1.load(std::memory_order_relaxed);

                        if (outCount >= 2 && out0 >= 0 && out1 >= 0) {
                            auto outL = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, out0));
                            auto outR = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, out1));

                            if (outL != nullptr && outR != nullptr) {
                                simd::interleaveStereo(outL, outR, mIntermediateStereoBuffer.data(), frames);
                                renderedSlot0 = true;
                            }
                        } else if (outCount == 1 && out0 >= 0) {
                            auto outL = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, out0));

                            if (outL != nullptr) {
                                simd::interleaveMonoToStereo(outL, mIntermediateStereoBuffer.data(), frames);
                                renderedSlot0 = true;
                            }
                        }
                    }
                }
            }
        }
    }

    if (!renderedSlot0) {
        std::fill(mIntermediateStereoBuffer.begin(), mIntermediateStereoBuffer.begin() + numSamples, 0.0f);
    }

    if (mNumSlots > 0) {
        clock_gettime(CLOCK_MONOTONIC, &slot_end);
        auto slot0_ns = (slot_end.tv_sec - slot_start.tv_sec) * 1e9 + (slot_end.tv_nsec - slot_start.tv_nsec);
        auto slot0_load = (blockDurationNs > 0.0) ? (slot0_ns / blockDurationNs) : 0.0;
        mSmoothedSlotLoad[0] = (mSmoothedSlotLoad[0] * DSP_LOAD_EMA_PREVIOUS_WEIGHT) + (slot0_load * DSP_LOAD_EMA_CURRENT_WEIGHT);
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
                            simd::deinterleaveStereo(mIntermediateStereoBuffer.data(), inL, inR, frames);
                        }
                    } else if (inCount == 1 && in0 >= 0) {
                        auto inM = static_cast<float*>(aapBuffer->get_buffer(aapBuffer, in0));

                        if (inM != nullptr) {
                            simd::deinterleaveStereoToMono(mIntermediateStereoBuffer.data(), inM, frames);
                        }
                    }

                    // Process plugin
                    inst->process(frames, 0);

                    if (inst->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_ACTIVE) {
                        // Read effect outputs back into intermediate stereo buffer (SIMD interleaving)
                        if (outCount >= 2 && out0 >= 0 && out1 >= 0) {
                            auto outL = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, out0));
                            auto outR = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, out1));

                            if (outL != nullptr && outR != nullptr) {
                                simd::interleaveStereo(outL, outR, mIntermediateStereoBuffer.data(), frames);
                            }
                        } else if (outCount == 1 && out0 >= 0) {
                            auto outM = static_cast<const float*>(aapBuffer->get_buffer(aapBuffer, out0));

                            if (outM != nullptr) {
                                simd::interleaveMonoToStereo(outM, mIntermediateStereoBuffer.data(), frames);
                            }
                        }
                    }
                }
            }
        }

        clock_gettime(CLOCK_MONOTONIC, &slot_end);
        auto slot_ns = (slot_end.tv_sec - slot_start.tv_sec) * 1e9 + (slot_end.tv_nsec - slot_start.tv_nsec);
        auto slot_load = (blockDurationNs > 0.0) ? (slot_ns / blockDurationNs) : 0.0;
        mSmoothedSlotLoad[slotIdx] = (mSmoothedSlotLoad[slotIdx] * DSP_LOAD_EMA_PREVIOUS_WEIGHT) + (slot_load * DSP_LOAD_EMA_CURRENT_WEIGHT);
        mSlots[slotIdx]->mCpuLoad.store(static_cast<float>(mSmoothedSlotLoad[slotIdx]));
    }

    clock_gettime(CLOCK_MONOTONIC, &end_total);
    auto total_elapsed_ns = (end_total.tv_sec - start_total.tv_sec) * 1e9 + (end_total.tv_nsec - start_total.tv_nsec);
    auto instant_total_load = (blockDurationNs > 0.0) ? (total_elapsed_ns / blockDurationNs) : 0.0;
    mSmoothedTotalLoad = (mSmoothedTotalLoad * DSP_LOAD_EMA_PREVIOUS_WEIGHT) + (instant_total_load * DSP_LOAD_EMA_CURRENT_WEIGHT);
    mTotalCpuLoad.store(static_cast<float>(mSmoothedTotalLoad));

    mRenderEpoch.fetch_add(1, std::memory_order_release);

    // Push the newly rendered DSP audio block to the FIFO ring buffer
    pushFifo(mIntermediateStereoBuffer.data(), static_cast<size_t>(frames));
}

void NativeAudioEngine::primeFifo()
{
    resetFifo();

    auto targetFrames = mFramesPerCallback.load(std::memory_order_relaxed);

    if (targetFrames <= 0) {
        targetFrames = DEFAULT_FRAMES_PER_CALLBACK;
    }

    // Pre-render full blocks into the FIFO so playback starts immediately with no initial gap
    for (int32_t i = 0; i < FIFO_PRIME_BLOCKS; i++) {
        renderDspBlock(targetFrames);
    }
}

bool NativeAudioEngine::start()
{
    if (mIsProcessing.load()) {
        return true;
    }

    if (mStream) {
        mStream->close();
        mStream.reset();
    }

    auto targetFrames = mFramesPerCallback.load(std::memory_order_relaxed);

    // Configure audio stream builder (default: LowLatency Exclusive MMAP)
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(mChannelCount)
        ->setSampleRate(mSampleRate)
        ->setFramesPerCallback(oboe::kUnspecified)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    auto result = builder.openStream(mStream);

    if (result != oboe::Result::OK) {
        LOGW("Exclusive stream open failed: %s. Falling back to Shared mode.", oboe::convertToText(result));
        mStream.reset();

        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(mStream);
    }

    if (result != oboe::Result::OK) {
        LOGE("Failed to open Oboe audio stream: %s", oboe::convertToText(result));
        return false;
    }

    if (mStream) {
        auto burst = mStream->getFramesPerBurst();

        if (burst > 0) {
            auto minRingBuffer = burst * BUFFER_CAPACITY_SAFETY_FACTOR;
            auto targetBufferSize = std::max(minRingBuffer, targetFrames * BUFFER_CAPACITY_SAFETY_FACTOR);
            mStream->setBufferSizeInFrames(targetBufferSize);
            LOGI("Oboe stream opened: bufferSize=%d, burst=%d, sharingMode=%s, renderBlock=%d",
                 mStream->getBufferSizeInFrames(), burst,
                 mStream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared",
                 targetFrames);
        }
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

    // Prime the FIFO ring buffer before requesting stream playback
    primeFifo();

    result = mStream->requestStart();

    if (result != oboe::Result::OK) {
        LOGE("Failed to start Oboe audio stream: %s", oboe::convertToText(result));
        mStream->close();
        mStream.reset();
        return false;
    }

    mIsProcessing.store(true);
    LOGI("Native Audio Engine started successfully (FIFO decoupled render running)");
    return true;
}

void NativeAudioEngine::setFramesPerCallback(int32_t framesPerCallback)
{
    auto targetFrames = std::min(framesPerCallback, MAX_DSP_BLOCK_FRAMES);

    if (targetFrames <= 0 || mFramesPerCallback.load(std::memory_order_relaxed) == targetFrames) {
        return;
    }

    mFramesPerCallback.store(targetFrames, std::memory_order_relaxed);

    if (mStream) {
        auto burst = mStream->getFramesPerBurst();

        if (burst > 0) {
            auto minRingBuffer = burst * BUFFER_CAPACITY_SAFETY_FACTOR;
            auto targetBufferSize = std::max(minRingBuffer, targetFrames * BUFFER_CAPACITY_SAFETY_FACTOR);
            mStream->setBufferSizeInFrames(targetBufferSize);
        }
    }

    if (mIsProcessing.load()) {
        LOGI("Re-priming FIFO with new render block size (%d frames)", targetFrames);
        primeFifo();
    }
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

    resetFifo();
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

    auto oldInstance = mSlots[slotIndex]->mInstance.load(std::memory_order_relaxed);

    aap::PluginInstance* instance = nullptr;

    if (client != nullptr && instanceId >= 0) {
        instance = client->getInstanceById(instanceId);
    }

    mSlots[slotIndex]->setInstance(instance);
    mSlots[slotIndex]->mIsBypassed.store(false, std::memory_order_release);

    // Quiescent-state synchronization: wait for 2 full audio render epochs on control thread
    if (oldInstance != nullptr && mIsProcessing.load(std::memory_order_acquire)) {
        auto targetEpoch = mRenderEpoch.load(std::memory_order_acquire) + QUIESCENT_EPOCHS_TO_WAIT;

        for (int32_t attempt = 0; attempt < MAX_QUIESCENT_WAIT_ATTEMPTS && mRenderEpoch.load(std::memory_order_acquire) < targetEpoch; attempt++) {
            usleep(QUIESCENT_POLL_INTERVAL_US);
        }
    }

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
        mSlots[slotIndex]->mIsBypassed.store(bypassed, std::memory_order_release);
    }
}

void NativeAudioEngine::sendUmpToSlot(int32_t slotIndex, const uint8_t* data, size_t size)
{
    if (slotIndex < 0 || slotIndex >= mNumSlots || data == nullptr || size == 0) {
        return;
    }

    auto inst = mSlots[slotIndex]->mInstance.load(std::memory_order_acquire);

    if (inst != nullptr && inst->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_ACTIVE) {
        inst->addEventUmpInput((void*) data, size);
    }
}

oboe::DataCallbackResult NativeAudioEngine::onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames)
{
    ScopedNoDenormals noDenormals;

    if (audioData == nullptr || numFrames <= 0) {
        return oboe::DataCallbackResult::Continue;
    }

    auto outStream = static_cast<float*>(audioData);
    auto targetFrames = mFramesPerCallback.load(std::memory_order_relaxed);

    if (targetFrames <= 0) {
        targetFrames = DEFAULT_FRAMES_PER_CALLBACK;
    }

    // Accumulate enough frames in FIFO to satisfy the HAL callback request
    size_t safetyAttempts = 0;
    size_t maxAttempts = static_cast<size_t>((numFrames + targetFrames - 1) / targetFrames) + BUFFER_CAPACITY_SAFETY_FACTOR;

    while (mFifoAvailableFrames < static_cast<size_t>(numFrames) && safetyAttempts < maxAttempts) {
        renderDspBlock(targetFrames);
        safetyAttempts++;
    }

    // Pull exactly numFrames from the FIFO directly into audio output buffer
    pullFifo(outStream, static_cast<size_t>(numFrames));

    return oboe::DataCallbackResult::Continue;
}

} // namespace aaphost
