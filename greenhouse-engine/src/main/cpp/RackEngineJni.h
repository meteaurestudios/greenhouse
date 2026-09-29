#pragma once

#include "RackEngine.h"

namespace aaphost
{

/** The process-wide engine behind the JNI bindings. */
RackEngine& getEngine();

} // namespace aaphost
