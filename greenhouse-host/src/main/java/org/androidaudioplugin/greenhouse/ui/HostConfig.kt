package org.androidaudioplugin.greenhouse.ui

import android.app.Application
import org.androidaudioplugin.greenhouse.device.SlotDeviceSource
import org.androidaudioplugin.greenhouse.device.aap.AapDeviceSource

/** How an app configures the host: give it to [HostViewModel] through [HostViewModel.factory]. */
class HostConfig(
    /**
     * Where the devices loaded into the rack's slots come from, in browser order. Each source is
     * created once, for a rack of `numSlots` slots, and closed with the view model.
     */
    val createSources: (application: Application, numSlots: Int) -> List<SlotDeviceSource> = { application, numSlots ->
        listOf(AapDeviceSource(application, numSlots))
    }
)
