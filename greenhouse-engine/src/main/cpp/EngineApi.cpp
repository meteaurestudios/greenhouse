#include "EngineApi.h"
#include "RackEngine.h"

// One engine per process. It is intentionally never destroyed: tearing it down during static
// destruction could touch plugin instances aap-core has already freed. The stream and plugin
// references are released explicitly through nativeShutdown().
greenhouse::RackEngine& greenhouse::getEngine()
{
    static auto engine = new greenhouse::RackEngine();
    return *engine;
}

void greenhouse::setSlotProcessor(int32_t slotIndex, std::unique_ptr<SlotProcessor> processor)
{
    getEngine().setSlotProcessor(slotIndex, std::move(processor));
}
