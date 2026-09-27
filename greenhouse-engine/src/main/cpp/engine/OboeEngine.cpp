#include "engine/OboeEngine.h"
#include "engine/ScopedNoDenormals.h"
#include "utils/Logging.h"
#include <oboe/OboeExtensions.h>
#include <thread>

namespace aaphost
{

namespace
{

bool isRunning(oboe::StreamState state)
{
    return state == oboe::StreamState::Started || state == oboe::StreamState::Starting;
}

void logStreamProperties(oboe::AudioStream& stream)
{
    LOGI("Output stream opened: device=%d, sampleRate=%d, channels=%d, burst=%d, bufferSize=%d, capacity=%d, sharingMode=%s, mmap=%s",
         stream.getDeviceId(),
         stream.getSampleRate(),
         stream.getChannelCount(),
         stream.getFramesPerBurst(),
         stream.getBufferSizeInFrames(),
         stream.getBufferCapacityInFrames(),
         stream.getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared",
         oboe::OboeExtensions::isMMapUsed(&stream) ? "yes" : "no");
}

} // namespace

// ---------------------------------------------------------------------------------------------
// Control thread
// ---------------------------------------------------------------------------------------------

bool OboeEngine::openStream()
{
    std::lock_guard<std::mutex> lock(mControlMutex);
    return mStream != nullptr || openStreamLocked();
}

bool OboeEngine::startStreaming()
{
    std::lock_guard<std::mutex> lock(mControlMutex);
    return startLocked();
}

void OboeEngine::stopStreaming()
{
    std::lock_guard<std::mutex> lock(mControlMutex);
    stopLocked();
}

void OboeEngine::closeStream()
{
    std::lock_guard<std::mutex> lock(mControlMutex);
    stopLocked();
    closeStreamLocked();
}

void OboeEngine::setMinimumBufferSize(int32_t frames)
{
    mMinimumBufferSize.store(frames, std::memory_order_relaxed);
}

void OboeEngine::setBufferSizeLocked(int32_t frames)
{
    if (mStream == nullptr) {
        return;
    }

    auto result = mStream->setBufferSizeInFrames(frames);

    if (!result) {
        LOGW("setBufferSizeInFrames(%d) failed: %s", frames, oboe::convertToText(result.error()));
    } else if (result.value() < frames) {
        LOGW("Buffer size capped at %d frames (requested %d)", result.value(), frames);
    }
}

bool OboeEngine::startLocked()
{
    mShouldStream = true;
    auto result = startStreamLocked();

    // A stream whose device went away while it was stopped only reports it when started: retry on a fresh stream
    if (result == oboe::Result::ErrorDisconnected) {
        LOGW("Output stream was disconnected while stopped, reopening");
        closeStreamLocked();
        result = startStreamLocked();
    }

    if (result != oboe::Result::OK) {
        LOGE("Failed to start output stream: %s", oboe::convertToText(result));
        stopLocked(); // deactivates what streamStarting() prepared
        closeStreamLocked();
        return false;
    }

    return true;
}

oboe::Result OboeEngine::startStreamLocked()
{
    // The stream can be missing on first start, after closeStream(), or if it could not be reopened after an error
    if (mStream == nullptr && !openStreamLocked()) {
        return oboe::Result::ErrorUnavailable;
    }

    if (isRunning(mStream->getState())) {
        return oboe::Result::OK;
    }

    streamStarting();
    return mStream->requestStart();
}

bool OboeEngine::openStreamLocked()
{
    oboe::AudioStreamBuilder builder;

    // AAudio is always available (minSdk 29) and falls back to Shared mode on its own when Exclusive
    // (MMAP) is not available. No sample rate is requested: the device's native rate avoids a resampler.
    builder.setAudioApi(oboe::AudioApi::AAudio)
        ->setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setChannelCount(STEREO_CHANNEL_COUNT)
        ->setChannelConversionAllowed(true)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    auto result = builder.openStream(mStream);

    // MMAP is briefly unavailable while the audio server tears down a previous stream (process restart,
    // device change), and AAudio then silently falls back to the slower legacy path for good: retry once.
    if (result == oboe::Result::OK && oboe::OboeExtensions::isMMapSupported() && !oboe::OboeExtensions::isMMapUsed(mStream.get())) {
        LOGW("Got the legacy path although MMAP is supported, retrying");
        mStream->close();
        std::this_thread::sleep_for(MMAP_RETRY_DELAY);
        result = builder.openStream(mStream);
    }

    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream: %s", oboe::convertToText(result));
        mStream.reset();
        return false;
    }

    // Let Oboe manage the Android Dynamic Performance Framework session from the audio callback thread
    mStream->setPerformanceHintEnabled(true);

    // Grows the buffer when underruns are detected
    mLatencyTuner = std::make_unique<oboe::LatencyTuner>(*mStream);

    mSampleRate.store(mStream->getSampleRate(), std::memory_order_relaxed);
    mFramesPerBurst.store(mStream->getFramesPerBurst(), std::memory_order_relaxed);
    prepareToPlay(mStream->getSampleRate(), mStream->getFramesPerBurst());

    // No callback runs yet, so the minimum is applied right away rather than at the first callback
    mAppliedMinimumBufferSize = mMinimumBufferSize.load(std::memory_order_relaxed);

    if (mAppliedMinimumBufferSize > 0) {
        mLatencyTuner->setMinimumBufferSize(mAppliedMinimumBufferSize);
        setBufferSizeLocked(mAppliedMinimumBufferSize);
    }

    logStreamProperties(*mStream);
    return true;
}

void OboeEngine::stopLocked()
{
    mShouldStream = false;

    // The stream may be gone after an error, but its plugins still need deactivating
    if (mStream != nullptr && isRunning(mStream->getState())) {
        auto result = mStream->stop();

        if (result != oboe::Result::OK) {
            LOGW("Failed to stop output stream: %s", oboe::convertToText(result));
        }
    }

    waitForAudioThreadQuiescence();
    flushState();
}

void OboeEngine::closeStreamLocked()
{
    if (mStream == nullptr) {
        return;
    }

    // close() also waits for an in-flight callback to return
    auto result = mStream->close();

    if (result != oboe::Result::OK) {
        LOGW("Failed to close output stream: %s", oboe::convertToText(result));
    }

    mStream.reset();
    mLatencyTuner.reset();
}

void OboeEngine::onErrorAfterClose(oboe::AudioStream* audioStream, oboe::Result error)
{
    std::lock_guard<std::mutex> lock(mControlMutex);

    // Ignore errors from a stream that was already replaced or closed
    if (audioStream != mStream.get()) {
        return;
    }

    // Oboe already closed the stream. Reopen it right away, even when not streaming, so a new
    // device's sample rate and burst size are known before playback resumes.
    LOGW("Output stream error (%s), reopening", oboe::convertToText(error));
    mStream.reset();
    mLatencyTuner.reset();

    // Both log their failures; startLocked() also clears mShouldStream if the stream cannot start
    if (mShouldStream) {
        startLocked();
    } else {
        openStreamLocked();
    }
}

void OboeEngine::waitForAudioThreadQuiescence() const
{
    auto observed = mCallbackSequence.load();

    // Even: no callback is running, and any later one will read the already-updated pointers
    if ((observed & 1) == 0) {
        return;
    }

    while (mCallbackSequence.load(std::memory_order_acquire) == observed) {
        std::this_thread::sleep_for(QUIESCENCE_POLL_INTERVAL);
    }
}

// ---------------------------------------------------------------------------------------------
// Audio thread
// ---------------------------------------------------------------------------------------------

oboe::DataCallbackResult OboeEngine::onAudioReady(oboe::AudioStream* audioStream, void* audioData, int32_t numFrames)
{
    // Must precede every load of a published pointer in process() (see waitForAudioThreadQuiescence)
    mCallbackSequence.fetch_add(1);
    ScopedNoDenormals noDenormals;

    applyMinimumBufferSize();

    auto workload = estimateWorkload();

    if (workload > 0) {
        audioStream->reportWorkload(workload);
    }

    process(static_cast<float*>(audioData), numFrames);

    mLatencyTuner->tune();

    mCallbackSequence.fetch_add(1, std::memory_order_release);
    return oboe::DataCallbackResult::Continue;
}

void OboeEngine::applyMinimumBufferSize()
{
    auto minimum = mMinimumBufferSize.load(std::memory_order_relaxed);

    if (minimum == mAppliedMinimumBufferSize) {
        return;
    }

    mAppliedMinimumBufferSize = minimum;

    // Takes effect in tune() at the end of this callback: the buffer drops to the new minimum, then grows again on underruns
    mLatencyTuner->setMinimumBufferSize(minimum);
    mLatencyTuner->requestReset();
}

} // namespace aaphost
