package org.androidaudioplugin.greenhouse.device.aap

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.greenhouse.core.AapHostEngine
import org.androidaudioplugin.greenhouse.data.PluginRepository
import org.androidaudioplugin.greenhouse.data.SlotState
import org.androidaudioplugin.greenhouse.device.DeviceInfo
import org.androidaudioplugin.greenhouse.device.SavedDeviceState
import org.androidaudioplugin.greenhouse.device.SlotDevice
import org.androidaudioplugin.greenhouse.device.SlotDeviceSource
import org.androidaudioplugin.hosting.InstalledPluginsMonitor
import org.androidaudioplugin.hosting.InstanceState
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import java.util.concurrent.atomic.AtomicReferenceArray

/** The AAP plugins installed on the device, each slot's instance hosted in its plugin's process. */
class AapDeviceSource(context: Context, numSlots: Int) : SlotDeviceSource {
    companion object {
        /** Sessions saved before there were several sources hold AAP plugins. */
        const val ID = SlotState.DEFAULT_SOURCE
        private const val TAG = "AapDeviceSource"
        private const val AAP_LIBRARY_NAME = "androidaudioplugin"
    }

    override val id = ID

    private val context = context.applicationContext
    private val repository = PluginRepository()
    private val hostEngine = AapHostEngine(this.context, numSlots)

    /** What [listDevices] found last: [instantiate] creates plugins from it. */
    @Volatile
    private var plugins: List<PluginInformation> = emptyList()

    /** The device created in each slot, until it is released. */
    private val slotDevices = AtomicReferenceArray<AapSlotDevice?>(numSlots)

    @Volatile
    private var onDevicesChanged: (() -> Unit)? = null

    /** Any package may be reported, not only plugins: a hint to list again. */
    private val packagesChangedListener: (String) -> Unit = { onDevicesChanged?.invoke() }

    init {
        // The monitor's receiver calls into aap-core's library, which is otherwise loaded only with the first plugin client
        System.loadLibrary(AAP_LIBRARY_NAME)
        InstalledPluginsMonitor.register(this.context)
        InstalledPluginsMonitor.onInstalledPluginsChangedListeners.add(packagesChangedListener)

        hostEngine.onSlotPluginDied = { slotIndex, instance ->
            val device = slotDevices.get(slotIndex)

            if (device != null && device.instance === instance) {
                device.onProcessDied()
            } else {
                PluginSlotLoader.abandon(instance)
            }
        }
    }

    override fun listDevices(): List<DeviceInfo> {
        val found = repository.queryPlugins(context)
        plugins = found
        return found.map { toDeviceInfo(it) }
    }

    override suspend fun instantiate(info: DeviceInfo, slotIndex: Int, sampleRate: Int, saved: SavedDeviceState?): SlotDevice? {
        val plugin = plugins.find { deviceId(it) == info.id } ?: return null

        val loaded = withContext(Dispatchers.IO) {
            PluginSlotLoader.instantiate(hostEngine, slotIndex, plugin, sampleRate) { instance ->
                if (saved != null) {
                    applySavedState(instance, saved)
                }
            }
        } ?: return null

        val device = AapSlotDevice(info, plugin, loaded.instance, sampleRate, loaded.presetCount) { released ->
            release(slotIndex, released)
        }

        slotDevices.set(slotIndex, device)

        // Its process died before the device was there to hear of it: only the instance was abandoned
        if (loaded.instance.state == InstanceState.DESTROYED) {
            device.onProcessDied()
        }

        return device
    }

    override fun setOnDevicesChanged(listener: (() -> Unit)?) {
        onDevicesChanged = listener
    }

    override fun close() {
        InstalledPluginsMonitor.onInstalledPluginsChangedListeners.remove(packagesChangedListener)
        hostEngine.close()
    }

    private fun release(slotIndex: Int, device: AapSlotDevice) {
        slotDevices.compareAndSet(slotIndex, device, null)

        // A plugin whose process died was abandoned: calling into it would crash
        if (device.instance.state != InstanceState.DESTROYED) {
            try {
                PluginSlotLoader.destroy(device.instance)
            } catch (e: Throwable) {
                Log.e(TAG, "Error destroying the plugin instance in slot $slotIndex", e)
            }
        }

        hostEngine.unloadSlot(slotIndex)
    }

    private fun applySavedState(instance: NativeRemotePluginInstance, saved: SavedDeviceState) {
        val state = saved.state

        if (state != null && state.isNotEmpty()) {
            try {
                instance.setState(state)
                Log.d(TAG, "Restored setState (${state.size} bytes)")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to apply setState", e)
            }
        }

        if (saved.presetIndex >= 0) {
            try {
                instance.setCurrentPresetIndex(saved.presetIndex)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to restore the preset index", e)
            }
        }
    }

    private fun deviceId(plugin: PluginInformation): String {
        return plugin.pluginId ?: "${plugin.packageName}/${plugin.displayName}"
    }

    private fun toDeviceInfo(plugin: PluginInformation): DeviceInfo {
        return DeviceInfo(
            sourceId = ID,
            id = deviceId(plugin),
            displayName = plugin.displayName,
            developer = plugin.developer?.ifBlank { null },
            category = repository.getPluginCategory(plugin),
            categoryLabel = plugin.category,
            packageName = plugin.packageName
        )
    }
}
