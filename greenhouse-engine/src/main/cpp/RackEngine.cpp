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
        // The engine lives as long as the process: a new configuration starts from an empty sequence
        mSequencer.reset();
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
        slot.mProcessor.store(nullptr);
        slot.mOwnedProcessor.reset();
        slot.mIsBypassed.store(false, std::memory_order_relaxed);
        slot.mGain.store(UNITY_GAIN, std::memory_order_relaxed);
        slot.mMix.store(WET_ONLY_MIX, std::memory_order_relaxed);
        slot.mCurrentGain = UNITY_GAIN;
        slot.mCurrentMix = WET_ONLY_MIX;
        slot.mWasBypassed = false;
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

void RackEngine::setSlotProcessor(int32_t slotIndex, std::unique_ptr<SlotProcessor> processor)
{
    std::lock_guard<std::mutex> lock(mControlMutex);

    if (!isValidSlot(slotIndex)) {
        return;
    }

    auto& slot = mSlots[slotIndex];

    // Unpublish the old processor and wait for the audio thread to let go of it before destroying it.
    // An AAP plugin instance is destroyed by the host as soon as we return.
    slot.mProcessor.store(nullptr);
    waitForAudioThreadQuiescence();
    slot.mOwnedProcessor = std::move(processor);
    slot.mIsBypassed.store(false, std::memory_order_relaxed);
    slot.mInvalidBlocks.store(0, std::memory_order_relaxed);

    auto newProcessor = slot.mOwnedProcessor.get();

    if (newProcessor == nullptr) {
        mSequencer.setSlotEventCapacity(slotIndex, MAX_SLOT_EVENT_WORDS);
        LOGI("Slot %d cleared", slotIndex);
        return;
    }

    mSequencer.setSlotEventCapacity(slotIndex, newProcessor->getEventCapacityWords());

    // Before the first open, prepareToPlay() prepares it with the rest of the rack
    if (getSampleRate() > 0) {
        newProcessor->prepareToPlay(getSampleRate(), MAX_DSP_BLOCK_FRAMES);
    }

    if (isStreaming()) {
        newProcessor->activate();
    }

    slot.mProcessor.store(newProcessor);
    LOGI("Slot %d processor set: audioIn=%d, audioOut=%d", slotIndex,
         newProcessor->getAudioInputCount(), newProcessor->getAudioOutputCount());
}

void RackEngine::setSlotBypassed(int32_t slotIndex, bool bypassed)
{
    if (isValidSlot(slotIndex)) {
        mSlots[slotIndex].mIsBypassed.store(bypassed, std::memory_order_relaxed);
    }
}

void RackEngine::setSlotGain(int32_t slotIndex, float gain)
{
    if (isValidSlot(slotIndex)) {
        mSlots[slotIndex].mGain.store(std::clamp(gain, SILENT_GAIN, MAX_SLOT_GAIN), std::memory_order_relaxed);
    }
}

void RackEngine::setSlotMix(int32_t slotIndex, float mix)
{
    if (isValidSlot(slotIndex)) {
        mSlots[slotIndex].mMix.store(std::clamp(mix, DRY_ONLY_MIX, WET_ONLY_MIX), std::memory_order_relaxed);
    }
}

void RackEngine::sendUmpToSlot(int32_t slotIndex, const uint8_t* data, int32_t size)
{
    if (!isValidSlot(slotIndex) || data == nullptr || size <= 0) {
        return;
    }

    // Held so the processor cannot be swapped out and destroyed while events are queued on it
    std::lock_guard<std::mutex> lock(mControlMutex);
    auto processor = mSlots[slotIndex].mProcessor.load();

    if (processor == nullptr || !processor->queueEvents(data, size)) {
        return;
    }

    // Recorded where the processor receives it: at the start of the next block
    mSequencer.recordInput(slotIndex, data, size);
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

    for (auto& slot : mSlots) {
        auto processor = slot.mProcessor.load();

        if (processor != nullptr) {
            processor->prepareToPlay(sampleRate, MAX_DSP_BLOCK_FRAMES);
        }
    }
}

void RackEngine::streamStarting()
{
    auto numSlots = mNumSlots.load();

    for (int32_t i = 0; i < numSlots; i++) {
        auto processor = mSlots[i].mProcessor.load();

        if (processor != nullptr) {
            processor->activate();
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

        auto processor = slot.mProcessor.load();

        if (processor != nullptr) {
            processor->deactivate();
        }
    }

    mTotalCpuLoad.store(0.0f, std::memory_order_relaxed);
    mSequencer.onStreamStopped();
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
        auto processor = mSlots[i].mProcessor.load();

        if (processor != nullptr && !mSlots[i].mIsBypassed.load(std::memory_order_relaxed) && processor->isActive()) {
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

    mSequencer.beginBlock(frames, mRenderSampleRate);

    // The instrument slot starts from silence; each following slot processes the previous output in place
    std::fill_n(mBlockBuffer.data(), frames * STEREO_CHANNEL_COUNT, 0.0f);

    for (int32_t i = 0; i < numSlots; i++) {
        auto& slot = mSlots[i];
        auto slotStart = Clock::now();
        auto rendered = renderSlot(i, frames);

        // Nothing was applied: jump to the host settings so they don't ramp from stale values later
        if (!rendered) {
            slot.mCurrentGain = slot.mGain.load(std::memory_order_relaxed);
            slot.mCurrentMix = slot.mMix.load(std::memory_order_relaxed);
        }

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

    // After the slots, so effects do not process the clicks
    mSequencer.renderMetronome(mBlockBuffer.data(), frames);

    mSmoothedTotalLoad = smoothLoad(mSmoothedTotalLoad, Clock::now() - blockStart, blockDurationNs);
    mTotalCpuLoad.store(static_cast<float>(mSmoothedTotalLoad), std::memory_order_relaxed);

    mBlockFrames = frames;
    mBlockReadFrame = 0;
}

/**
 * Runs one slot over mBlockBuffer in place. Returns true if the processor output replaced the buffer;
 * false leaves it untouched (empty, bypassed or failed slots, and blocks with NaN or infinite
 * samples, pass the signal through).
 */
bool RackEngine::renderSlot(int32_t slotIndex, int32_t frames)
{
    auto& slot = mSlots[slotIndex];
    auto isInstrument = slotIndex == INSTRUMENT_SLOT_INDEX;
    auto processor = slot.mProcessor.load();
    auto isBypassed = slot.mIsBypassed.load(std::memory_order_relaxed);

    if (!isBypassed) {
        slot.mWasBypassed = false;
    }

    // The first bypassed block the processor can run still runs it, to end the notes the sequencer plays on it
    auto isBypassStarting = isBypassed && !slot.mWasBypassed;

    if (processor == nullptr || (isBypassed && !isBypassStarting) || !processor->canProcess(frames, mRenderSampleRate)) {
        return false;
    }

    // An effect without audio in and out cannot sit in the chain; an instrument may be MIDI-only
    if (!isInstrument && (processor->getAudioInputCount() == 0 || processor->getAudioOutputCount() == 0)) {
        return false;
    }

    if (isBypassStarting) {
        mSequencer.releaseSlotNotes(slotIndex);
        slot.mWasBypassed = true;
    }

    int32_t eventWordCount = 0;
    auto events = mSequencer.getSlotEvents(slotIndex, eventWordCount);
    SlotOutput out;
    auto hasOutput = processor->process(mBlockBuffer.data(), frames, events, eventWordCount, out);

    // Once bypassed, its output is not used
    if (isBypassStarting || !hasOutput) {
        return false;
    }

    // A processor can output NaN or infinite samples (e.g. Odin2's first block after a session reload).
    // Passed on, they click, and a NaN can get stuck in the next slots' filters and delay lines for good.
    if (!simd::allFinite(out.mLeft, frames) || !simd::allFinite(out.mRight, frames)) {
        slot.mInvalidBlocks.fetch_add(1, std::memory_order_relaxed);
        return false;
    }

    // mBlockBuffer still holds the slot's input: the dry signal of the host mix
    auto targetGain = slot.mGain.load(std::memory_order_relaxed);
    auto targetMix = slot.mMix.load(std::memory_order_relaxed);
    auto isUnityWet = slot.mCurrentGain == UNITY_GAIN && targetGain == UNITY_GAIN &&
                      slot.mCurrentMix == WET_ONLY_MIX && targetMix == WET_ONLY_MIX;

    if (isUnityWet && out.mRight == out.mLeft) {
        simd::interleaveMonoToStereo(out.mLeft, mBlockBuffer.data(), frames);
    } else if (isUnityWet) {
        simd::interleaveStereo(out.mLeft, out.mRight, mBlockBuffer.data(), frames);
    } else {
        simd::mixStereo(out.mLeft, out.mRight, mBlockBuffer.data(), frames,
                        (WET_ONLY_MIX - slot.mCurrentMix) * slot.mCurrentGain, (WET_ONLY_MIX - targetMix) * targetGain,
                        slot.mCurrentMix * slot.mCurrentGain, targetMix * targetGain);
    }

    slot.mCurrentGain = targetGain;
    slot.mCurrentMix = targetMix;
    return true;
}

} // namespace aaphost
