#pragma once

#include "slot/SlotProcessor.h"
#include <cstdint>
#include <memory>

// What other native libraries can call, through the prefab package of :greenhouse-engine
// (find_package(greenhouse-engine REQUIRED CONFIG), target greenhouse-engine::greenhouse-engine).
// The engine library hides every other symbol.
#define GREENHOUSE_API __attribute__((visibility("default")))

namespace greenhouse
{

/**
 * Puts a processor in a slot of the app's rack; null empties the slot. Blocks until the audio thread
 * no longer uses the previous processor, then destroys it. The processor is prepared and activated
 * as the rack's stream requires (see SlotProcessor). Slot 0 is the instrument, then come the effects.
 *
 * Call it only from your SlotDevice's attach() (Kotlin, :greenhouse-host), on the main thread, so the
 * host knows what the slot holds. A processor put in any other way is unknown to the host, which
 * replaces or empties the slot whenever it loads something into it.
 */
GREENHOUSE_API void setSlotProcessor(int32_t slotIndex, std::unique_ptr<SlotProcessor> processor);

} // namespace greenhouse
