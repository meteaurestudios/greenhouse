#include "RackEngine.h"
#include "utils/AudioSimd.h"
#include "utils/Logging.h"
#include <algorithm>
#include <cstring>

namespace aaphost
{

namespace
{

using Clock = std::chrono::steady_clock;

bool isActive(aap::PluginInstance* instance)
{
    return instance->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_ACTIVE;
}

bool isInactive(aap::PluginInstance* instance)
{
    return instance->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_INACTIVE;
}

AudioPorts findAudioPorts(aap::PluginInstance* instance)
{
    AudioPorts ports;
    auto numPorts = instance->getNumPorts();

    for (int32_t i = 0; i < numPorts; i++) {
        auto port = instance->getPort(i);

        if (port == nullptr || port->getContentType() != AAP_CONTENT_TYPE_AUDIO) {
            continue;
        }

        if (port->getPortDirection() == AAP_PORT_DIRECTION_INPUT && ports.mInCount < STEREO_CHANNEL_COUNT) {
            ports.mIn[ports.mInCount++] = i;
        } else if (port->getPortDirection() == AAP_PORT_DIRECTION_OUTPUT && ports.mOutCount < STEREO_CHANNEL_COUNT) {
            ports.mOut[ports.mOutCount++] = i;
        }
    }

    return ports;
}

double smoothLoad(double previous, Clock::duration elapsed, double blockDurationNs)
{
    auto elapsedNs = static_cast<double>(std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count());
    return previous * DSP_LOAD_EMA_PREVIOUS_WEIGHT + (elapsedNs / blockDurationNs) * DSP_LOAD_EMA_CURRENT_WEIGHT;
}

float nextPeak(float previous, float measured)
{
    auto next = measured >= previous ? measured : previous * METER_DECAY_FACTOR;
    return next < METER_MIN_THRESHOLD ? 0.0f : next;
}

} // namespace

RackEngine::~RackEngine()
{
    closeStream();
}

// ---------------------------------------------------------------------------------------------
// Control thread
// ---------------------------------------------------------------------------------------------

void RackEngine::configure(int32_t framesPerCallback, int32_t numSlots)
{
    closeStream();

    {
        std::lock_guard<std::mutex> lock(mControlMutex);
        clearSlotsLocked();

        if (numSlots > MAX_RACK_SLOTS) {
            LOGW("%d slots requested, the rack supports %d", numSlots, MAX_RACK_SLOTS);
        }

        mNumSlots.store(std::clamp(numSlots, 1, MAX_RACK_SLOTS));
        mFramesPerCallback.store(std::clamp(framesPerCallback, MIN_DSP_BLOCK_FRAMES, MAX_DSP_BLOCK_FRAMES));
    }

    openStream();
    LOGI("Rack engine configured: sampleRate=%d, framesPerCallback=%d, numSlots=%d",
         getSampleRate(), mFramesPerCallback.load(), mNumSlots.load());
}

void RackEngine::shutdown()
{
    closeStream();

    std::lock_guard<std::mutex> lock(mControlMutex);
    clearSlotsLocked();
}

void RackEngine::clearSlotsLocked()
{
    // Only called with the stream closed, so no quiescence wait is needed
    for (auto& slot : mSlots) {
        slot.mInstance.store(nullptr);
        slot.mPorts = AudioPorts{};
        slot.mPreparedSampleRate = 0;
        slot.mIsBypassed.store(false, std::memory_order_relaxed);
        slot.mCpuLoad.store(0.0f, std::memory_order_relaxed);
        slot.mPeakL.store(0.0f, std::memory_order_relaxed);
        slot.mPeakR.store(0.0f, std::memory_order_relaxed);
        slot.mInvalidBlocks.store(0, std::memory_order_relaxed);
    }
}

void RackEngine::setFramesPerCallback(int32_t framesPerCallback)
{
    std::lock_guard<std::mutex> lock(mControlMutex);

    // The audio thread picks the new size up at its next block boundary
    mFramesPerCallback.store(std::clamp(framesPerCallback, MIN_DSP_BLOCK_FRAMES, MAX_DSP_BLOCK_FRAMES), std::memory_order_relaxed);
    updateMinimumBufferSizeLocked();
}

void RackEngine::updateMinimumBufferSizeLocked()
{
    // A callback that has to render a whole block needs room for that block on top of what is queued
    auto blockFrames = mFramesPerCallback.load(std::memory_order_relaxed);
    setMinimumBufferSize(std::max(getFramesPerBurst(), blockFrames) * BUFFER_SIZE_SAFETY_FACTOR);
}

void RackEngine::setSlotPlugin(int32_t slotIndex, aap::PluginClient* client, int32_t instanceId, int32_t sampleRate)
{
    std::lock_guard<std::mutex> lock(mControlMutex);

    if (!isValidSlot(slotIndex)) {
        return;
    }

    auto& slot = mSlots[slotIndex];
    auto instance = (client != nullptr && instanceId >= 0) ? client->getInstanceById(instanceId) : nullptr;

    // Unpublish the old instance and wait for the audio thread to let go of it: the caller
    // destroys it as soon as we return
    slot.mInstance.store(nullptr);
    waitForAudioThreadQuiescence();
    slot.mIsBypassed.store(false, std::memory_order_relaxed);
    slot.mInvalidBlocks.store(0, std::memory_order_relaxed);

    if (instance == nullptr) {
        slot.mPorts = AudioPorts{};
        slot.mPreparedSampleRate = 0;
        LOGI("Slot %d cleared", slotIndex);
        return;
    }

    slot.mPorts = findAudioPorts(instance);
    slot.mPreparedSampleRate = sampleRate;

    if (isStreaming() && isInactive(instance)) {
        instance->activate();
    }

    slot.mInstance.store(instance);
    LOGI("Slot %d plugin set: instanceId=%d, sampleRate=%d, audioIn=%d, audioOut=%d, state=%d",
         slotIndex, instanceId, sampleRate, slot.mPorts.mInCount, slot.mPorts.mOutCount,
         static_cast<int>(instance->getInstanceState()));
}

void RackEngine::setSlotBypassed(int32_t slotIndex, bool bypassed)
{
    if (isValidSlot(slotIndex)) {
        mSlots[slotIndex].mIsBypassed.store(bypassed, std::memory_order_relaxed);
    }
}

void RackEngine::sendUmpToSlot(int32_t slotIndex, const uint8_t* data, int32_t size)
{
    if (!isValidSlot(slotIndex) || data == nullptr || size <= 0) {
        return;
    }

    // Held so the instance cannot be swapped out and destroyed while events are queued on it
    std::lock_guard<std::mutex> lock(mControlMutex);
    auto instance = mSlots[slotIndex].mInstance.load();

    // aap-core queues the events under its own lock and merges them at the next process(). Inactive
    // instances queue them too, so values set while paused (e.g. a session restore) apply once playing.
    if (instance != nullptr && (isActive(instance) || isInactive(instance))) {
        instance->addEventUmpInput(const_cast<uint8_t*>(data), size);
    }
}

float RackEngine::getTotalCpuLoad() const
{
    return mTotalCpuLoad.load(std::memory_order_relaxed);
}

float RackEngine::getSlotCpuLoad(int32_t slotIndex) const
{
    if (!isValidSlot(slotIndex)) {
        return 0.0f;
    }

    return mSlots[slotIndex].mCpuLoad.load(std::memory_order_relaxed);
}

int32_t RackEngine::getSlotInvalidBlocks(int32_t slotIndex) const
{
    if (!isValidSlot(slotIndex)) {
        return 0;
    }

    return mSlots[slotIndex].mInvalidBlocks.load(std::memory_order_relaxed);
}

void RackEngine::getAllSlotLevels(float* outLevels, int32_t maxSlots) const
{
    auto count = std::min(mNumSlots.load(std::memory_order_relaxed), maxSlots);

    for (int32_t i = 0; i < count; i++) {
        outLevels[i * STEREO_CHANNEL_COUNT] = mSlots[i].mPeakL.load(std::memory_order_relaxed);
        outLevels[i * STEREO_CHANNEL_COUNT + 1] = mSlots[i].mPeakR.load(std::memory_order_relaxed);
    }
}

bool RackEngine::isValidSlot(int32_t slotIndex) const
{
    return slotIndex >= 0 && slotIndex < mNumSlots.load(std::memory_order_relaxed);
}

// ---------------------------------------------------------------------------------------------
// OboeEngine hooks (control thread, mControlMutex held)
// ---------------------------------------------------------------------------------------------

void RackEngine::prepareToPlay(int32_t sampleRate, int32_t framesPerBurst)
{
    mRenderSampleRate = sampleRate;
    updateMinimumBufferSizeLocked();
}

void RackEngine::streamStarting()
{
    auto numSlots = mNumSlots.load();

    for (int32_t i = 0; i < numSlots; i++) {
        auto instance = mSlots[i].mInstance.load();

        if (instance != nullptr && isInactive(instance)) {
            instance->activate();
        }

        mSlots[i].mSmoothedLoad = 0.0;
    }

    mBlockFrames = 0;
    mBlockReadFrame = 0;
    mSmoothedTotalLoad = 0.0;
}

void RackEngine::flushState()
{
    for (auto& slot : mSlots) {
        slot.mPeakL.store(0.0f, std::memory_order_relaxed);
        slot.mPeakR.store(0.0f, std::memory_order_relaxed);
        slot.mCpuLoad.store(0.0f, std::memory_order_relaxed);

        auto instance = slot.mInstance.load();

        if (instance != nullptr && isActive(instance)) {
            instance->deactivate();
        }
    }

    mTotalCpuLoad.store(0.0f, std::memory_order_relaxed);
}

// ---------------------------------------------------------------------------------------------
// Audio thread
// ---------------------------------------------------------------------------------------------

void RackEngine::process(float* output, int32_t numFrames)
{
    // Copy out what is left of the current block and render a new one whenever it runs dry
    auto remaining = numFrames;

    while (remaining > 0) {
        if (mBlockReadFrame >= mBlockFrames) {
            renderBlock(mFramesPerCallback.load(std::memory_order_relaxed));
        }

        auto chunk = std::min(remaining, mBlockFrames - mBlockReadFrame);
        std::memcpy(output, mBlockBuffer.data() + mBlockReadFrame * STEREO_CHANNEL_COUNT,
                    static_cast<size_t>(chunk * STEREO_CHANNEL_COUNT) * sizeof(float));
        output += chunk * STEREO_CHANNEL_COUNT;
        mBlockReadFrame += chunk;
        remaining -= chunk;
    }
}

int32_t RackEngine::estimateWorkload() const
{
    auto workload = ADPF_BASE_WORKLOAD;
    auto numSlots = mNumSlots.load(std::memory_order_relaxed);

    for (int32_t i = 0; i < numSlots; i++) {
        auto instance = mSlots[i].mInstance.load();

        if (instance != nullptr && !mSlots[i].mIsBypassed.load(std::memory_order_relaxed) && isActive(instance)) {
            workload += ADPF_WORKLOAD_PER_ACTIVE_SLOT;
        }
    }

    return workload;
}

void RackEngine::renderBlock(int32_t frames)
{
    auto blockDurationNs = static_cast<double>(frames) * NANOS_PER_SECOND / static_cast<double>(mRenderSampleRate);
    auto blockStart = Clock::now();
    auto numSlots = mNumSlots.load(std::memory_order_relaxed);

    // The instrument slot starts from silence; each following slot processes the previous output in place
    std::fill_n(mBlockBuffer.data(), frames * STEREO_CHANNEL_COUNT, 0.0f);

    for (int32_t i = 0; i < numSlots; i++) {
        auto& slot = mSlots[i];
        auto slotStart = Clock::now();
        auto rendered = renderSlot(slot, i == INSTRUMENT_SLOT_INDEX, frames);

        slot.mSmoothedLoad = smoothLoad(slot.mSmoothedLoad, Clock::now() - slotStart, blockDurationNs);
        slot.mCpuLoad.store(static_cast<float>(slot.mSmoothedLoad), std::memory_order_relaxed);

        float peakL = 0.0f;
        float peakR = 0.0f;

        if (rendered) {
            simd::measureStereoPeak(mBlockBuffer.data(), frames, peakL, peakR);
        }

        slot.mPeakL.store(nextPeak(slot.mPeakL.load(std::memory_order_relaxed), peakL), std::memory_order_relaxed);
        slot.mPeakR.store(nextPeak(slot.mPeakR.load(std::memory_order_relaxed), peakR), std::memory_order_relaxed);
    }

    mSmoothedTotalLoad = smoothLoad(mSmoothedTotalLoad, Clock::now() - blockStart, blockDurationNs);
    mTotalCpuLoad.store(static_cast<float>(mSmoothedTotalLoad), std::memory_order_relaxed);

    mBlockFrames = frames;
    mBlockReadFrame = 0;
}

/**
 * Runs one slot over mBlockBuffer in place. Returns true if the plugin output replaced the buffer;
 * false leaves it untouched (empty, bypassed or failed slots, and blocks with NaN or infinite
 * samples, pass the signal through).
 */
bool RackEngine::renderSlot(RackSlot& slot, bool isInstrument, int32_t frames)
{
    auto instance = slot.mInstance.load();

    if (instance == nullptr || slot.mIsBypassed.load(std::memory_order_relaxed) || !isActive(instance)) {
        return false;
    }

    // Prepared for another device's rate: it would play at the wrong pitch until the host reloads it
    if (slot.mPreparedSampleRate != mRenderSampleRate) {
        return false;
    }

    const auto& ports = slot.mPorts;

    // An effect without audio in and out cannot sit in the chain; an instrument may be MIDI-only
    if (!isInstrument && (ports.mInCount == 0 || ports.mOutCount == 0)) {
        return false;
    }

    auto buffer = instance->getAudioPluginBuffer();

    if (buffer == nullptr || buffer->num_frames(buffer) < frames) {
        return false;
    }

    if (ports.mInCount == STEREO_CHANNEL_COUNT) {
        auto inL = static_cast<float*>(buffer->get_buffer(buffer, ports.mIn[0]));
        auto inR = static_cast<float*>(buffer->get_buffer(buffer, ports.mIn[1]));

        if (inL != nullptr && inR != nullptr) {
            simd::deinterleaveStereo(mBlockBuffer.data(), inL, inR, frames);
        }
    } else if (ports.mInCount == 1) {
        auto inMono = static_cast<float*>(buffer->get_buffer(buffer, ports.mIn[0]));

        if (inMono != nullptr) {
            simd::deinterleaveStereoToMono(mBlockBuffer.data(), inMono, frames);
        }
    }

    instance->process(frames, PLUGIN_PROCESS_TIMEOUT_NS);

    if (!isActive(instance)) {
        return false;
    }

    if (ports.mOutCount == 0) {
        return false;
    }

    auto outL = static_cast<const float*>(buffer->get_buffer(buffer, ports.mOut[0]));
    auto outR = ports.mOutCount == STEREO_CHANNEL_COUNT ? static_cast<const float*>(buffer->get_buffer(buffer, ports.mOut[1])) : outL;

    if (outL == nullptr || outR == nullptr) {
        return false;
    }

    // A plugin can output NaN or infinite samples (e.g. Odin2's first block after a session reload).
    // Passed on, they click, and a NaN can get stuck in the next slots' filters and delay lines for good.
    if (!simd::allFinite(outL, frames) || !simd::allFinite(outR, frames)) {
        slot.mInvalidBlocks.fetch_add(1, std::memory_order_relaxed);
        return false;
    }

    if (outR == outL) {
        simd::interleaveMonoToStereo(outL, mBlockBuffer.data(), frames);
    } else {
        simd::interleaveStereo(outL, outR, mBlockBuffer.data(), frames);
    }

    return true;
}

} // namespace aaphost
