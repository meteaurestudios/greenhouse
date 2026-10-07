#include "slot/AapSlotProcessor.h"
#include "utils/AudioSimd.h"
#include "utils/Logging.h"
#include <aap/ext/midi.h>
#include <algorithm>
#include <cstring>

namespace aaphost
{

namespace
{

bool isActiveInstance(aap::PluginInstance* instance)
{
    return instance->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_ACTIVE;
}

bool isInactiveInstance(aap::PluginInstance* instance)
{
    return instance->getInstanceState() == aap::PluginInstantiationState::PLUGIN_INSTANTIATION_STATE_INACTIVE;
}

AudioPorts findAudioPorts(aap::PluginInstance* instance)
{
    AudioPorts ports;
    auto numPorts = instance->getNumPorts();

    for (int32_t i = 0; i < numPorts; i++) {
        auto port = instance->getPort(i);

        if (port == nullptr) {
            continue;
        }

        if (port->getContentType() == AAP_CONTENT_TYPE_MIDI2) {
            if (port->getPortDirection() == AAP_PORT_DIRECTION_INPUT && ports.mMidiIn < 0) {
                ports.mMidiIn = i;
            }

            continue;
        }

        if (port->getContentType() != AAP_CONTENT_TYPE_AUDIO) {
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

} // namespace

std::unique_ptr<AapSlotProcessor> AapSlotProcessor::create(aap::PluginClient* client, int32_t instanceId, int32_t sampleRate)
{
    auto instance = (client != nullptr && instanceId >= 0) ? client->getInstanceById(instanceId) : nullptr;

    if (instance == nullptr) {
        return nullptr;
    }

    auto processor = std::make_unique<AapSlotProcessor>(instance, sampleRate);
    LOGI("AAP plugin instance %d: sampleRate=%d, audioIn=%d, audioOut=%d, state=%d", instanceId, sampleRate,
         processor->mPorts.mInCount, processor->mPorts.mOutCount, static_cast<int>(instance->getInstanceState()));
    return processor;
}

AapSlotProcessor::AapSlotProcessor(aap::PluginInstance* instance, int32_t sampleRate)
    : mInstance(instance), mPorts(findAudioPorts(instance)), mPreparedSampleRate(sampleRate)
{
}

// ---------------------------------------------------------------------------------------------
// Control thread
// ---------------------------------------------------------------------------------------------

void AapSlotProcessor::prepareToPlay(int32_t sampleRate, int32_t maxBlockFrames)
{
    // The plugin was prepared when it was instantiated. At another rate, canProcess() keeps it
    // silent (it would play at the wrong pitch) until the host reloads it.
}

void AapSlotProcessor::activate()
{
    if (isInactiveInstance(mInstance)) {
        mInstance->activate();
    }
}

void AapSlotProcessor::deactivate()
{
    if (isActiveInstance(mInstance)) {
        mInstance->deactivate();
    }
}

int32_t AapSlotProcessor::getAudioInputCount() const
{
    return mPorts.mInCount;
}

int32_t AapSlotProcessor::getAudioOutputCount() const
{
    return mPorts.mOutCount;
}

int32_t AapSlotProcessor::getEventCapacityWords() const
{
    auto buffer = mInstance->getAudioPluginBuffer();

    if (buffer == nullptr || mPorts.mMidiIn < 0) {
        return 0;
    }

    // aap-core merges the queued live input with the port's events within its own event buffer,
    // which can be smaller than the port: more events than that would be cut by the merge
    auto portBytes = buffer->get_buffer_size(buffer, mPorts.mMidiIn) - static_cast<int32_t>(sizeof(AAPMidiBufferHeader));
    auto bytes = std::min(portBytes, aap::DEFAULT_EVENT_MIDI2_INPUT_BUFFER_SIZE);
    return std::max(0, bytes / static_cast<int32_t>(sizeof(uint32_t)));
}

bool AapSlotProcessor::queueEvents(const uint8_t* data, int32_t size)
{
    // aap-core queues the events (lock-free) and merges them at the next process(). Inactive instances
    // queue them too, so values set while paused (e.g. a session restore) apply once playing.
    if (!(isActiveInstance(mInstance) || isInactiveInstance(mInstance))) {
        return false;
    }

    // The queue holds a fixed number of inputs until the next process(): what does not fit is dropped
    if (!mInstance->tryAddEventUmpInput(data, size)) {
        LOGW("AAP plugin input queue full, %d bytes of UMP dropped", size);
        return false;
    }

    return true;
}

// ---------------------------------------------------------------------------------------------
// Audio thread
// ---------------------------------------------------------------------------------------------

bool AapSlotProcessor::isActive() const
{
    return isActiveInstance(mInstance);
}

bool AapSlotProcessor::canProcess(int32_t frames, int32_t sampleRate) const
{
    // Prepared for another device's rate: it would play at the wrong pitch until the host reloads it
    if (!isActiveInstance(mInstance) || mPreparedSampleRate != sampleRate) {
        return false;
    }

    auto buffer = mInstance->getAudioPluginBuffer();
    return buffer != nullptr && buffer->num_frames(buffer) >= frames;
}

bool AapSlotProcessor::process(const float* input, int32_t frames, const uint32_t* events, int32_t eventWordCount, SlotOutput& out)
{
    auto buffer = mInstance->getAudioPluginBuffer();

    if (mPorts.mInCount == STEREO_CHANNEL_COUNT) {
        auto inL = static_cast<float*>(buffer->get_buffer(buffer, mPorts.mIn[0]));
        auto inR = static_cast<float*>(buffer->get_buffer(buffer, mPorts.mIn[1]));

        if (inL != nullptr && inR != nullptr) {
            simd::deinterleaveStereo(input, inL, inR, frames);
        }
    } else if (mPorts.mInCount == 1) {
        auto inMono = static_cast<float*>(buffer->get_buffer(buffer, mPorts.mIn[0]));

        if (inMono != nullptr) {
            simd::deinterleaveStereoToMono(input, inMono, frames);
        }
    }

    writeSequencerEvents(buffer, events, eventWordCount);
    mInstance->process(frames, PLUGIN_PROCESS_TIMEOUT_NS);

    // The plugin crashed or was deactivated during process()
    if (!isActiveInstance(mInstance) || mPorts.mOutCount == 0) {
        return false;
    }

    out.mLeft = static_cast<const float*>(buffer->get_buffer(buffer, mPorts.mOut[0]));
    out.mRight = mPorts.mOutCount == STEREO_CHANNEL_COUNT ? static_cast<const float*>(buffer->get_buffer(buffer, mPorts.mOut[1])) : out.mLeft;
    return out.mLeft != nullptr && out.mRight != nullptr;
}

/**
 * Writes the sequencer's events for this block into the MIDI2 input. The plugin clears the port
 * after each process(); within process(), aap-core merges the queued live input into it, ordered
 * by the JR timestamps.
 */
void AapSlotProcessor::writeSequencerEvents(aap_buffer_t* buffer, const uint32_t* events, int32_t eventWordCount)
{
    if (eventWordCount == 0 || mPorts.mMidiIn < 0) {
        return;
    }

    auto header = static_cast<AAPMidiBufferHeader*>(buffer->get_buffer(buffer, mPorts.mMidiIn));
    auto byteCount = eventWordCount * static_cast<int32_t>(sizeof(uint32_t));
    auto capacity = buffer->get_buffer_size(buffer, mPorts.mMidiIn) - static_cast<int32_t>(sizeof(AAPMidiBufferHeader));

    if (header == nullptr || byteCount > capacity) {
        return;
    }

    header->time_options = 0;
    header->length = static_cast<uint32_t>(byteCount);
    std::memcpy(header + 1, events, static_cast<size_t>(byteCount));
}

} // namespace aaphost
