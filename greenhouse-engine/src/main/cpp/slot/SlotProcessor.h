#pragma once

#include <cstdint>

namespace aaphost
{

/** A processor's output for the current block, non-interleaved and valid until its next process(). Mono: mRight == mLeft. */
struct SlotOutput
{
    const float* mLeft{nullptr};
    const float* mRight{nullptr};
};

/**
 * What runs in a rack slot: an AAP plugin (AapSlotProcessor), or DSP running inside the engine.
 * The RackEngine owns it and keeps the rest of the slot: the chain, bypass, level and mix, the
 * NaN guard, meters, CPU load and the sequencer. It may be destroyed while active, once the audio
 * thread no longer uses it.
 *
 * Threading:
 * - Control methods are called by the RackEngine with its control mutex held, never concurrently
 *   with each other. prepareToPlay(), activate() and deactivate() are only called while the audio
 *   thread does not use the processor.
 * - queueEvents() runs on the control thread while the audio thread may be in process(): what it
 *   shares with process() must be lock-free.
 * - The audio thread methods follow the realtime rules: no locks, allocations or system calls.
 */
class SlotProcessor
{
public:
    virtual ~SlotProcessor() = default;

    // -- Control thread --

    /**
     * The stream runs at sampleRate and process() gets at most maxBlockFrames: called before the
     * processor is added to a running rack, and whenever the stream is opened again, at a rate
     * that may differ.
     */
    virtual void prepareToPlay(int32_t sampleRate, int32_t maxBlockFrames) = 0;
    /** The stream is starting, or the processor was added to a running rack. Called again after deactivate(). */
    virtual void activate() = 0;
    /** The stream stopped. */
    virtual void deactivate() = 0;

    /** Audio inputs and outputs it uses, 0 to STEREO_CHANNEL_COUNT. An effect slot needs both. */
    virtual int32_t getAudioInputCount() const = 0;
    virtual int32_t getAudioOutputCount() const = 0;
    /** Most UMP words of sequencer events it takes in one process() call. */
    virtual int32_t getEventCapacityWords() const = 0;

    /** Queues live UMP (notes, parameter changes) for the next process(). Returns false if it was dropped. */
    virtual bool queueEvents(const uint8_t* data, int32_t size) = 0;

    // -- Audio thread --

    /** Whether it is running, for the ADPF workload estimate. */
    virtual bool isActive() const = 0;
    /** Whether process() can render a block of frames at sampleRate now. */
    virtual bool canProcess(int32_t frames, int32_t sampleRate) const = 0;
    /**
     * Renders one block. input: interleaved stereo, the previous slot's output (silence for the
     * instrument). events: the sequencer's UMP for this block (JR timestamps and events), to merge
     * with the queued live events. Returns false if there is no output; out is then not used.
     */
    virtual bool process(const float* input, int32_t frames, const uint32_t* events, int32_t eventWordCount, SlotOutput& out) = 0;
};

} // namespace aaphost
