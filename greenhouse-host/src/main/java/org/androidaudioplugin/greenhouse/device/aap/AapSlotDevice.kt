package org.androidaudioplugin.greenhouse.device.aap

import android.util.Log
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.greenhouse.core.RackEngine
import org.androidaudioplugin.greenhouse.device.DeviceInfo
import org.androidaudioplugin.greenhouse.device.DevicePreset
import org.androidaudioplugin.greenhouse.device.SlotDevice
import org.androidaudioplugin.hosting.InstanceState
import org.androidaudioplugin.hosting.NativeRemotePluginInstance

/** An AAP plugin instance in a rack slot, running in the plugin's own process. */
class AapSlotDevice internal constructor(
    override val info: DeviceInfo,
    val plugin: PluginInformation,
    val instance: NativeRemotePluginInstance,
    /** AAP plugins cannot be prepared again: they play at this rate only. */
    private val preparedSampleRate: Int,
    override val presetCount: Int,
    private val onRelease: (AapSlotDevice) -> Unit
) : SlotDevice {
    companion object {
        private const val TAG = "AapSlotDevice"
        private const val GUI_EXTENSION_URI_PREFIX = "urn://androidaudioplugin.org/extensions/gui"
    }

    override val parameters: List<ParameterInformation>
        get() = plugin.parameters

    override val hasCustomUi: Boolean
        get() = !plugin.uiViewFactory.isNullOrBlank() ||
                !plugin.uiWeb.isNullOrBlank() ||
                !plugin.uiActivity.isNullOrBlank() ||
                plugin.extensions.any { it.uri?.startsWith(GUI_EXTENSION_URI_PREFIX) == true }

    @Volatile
    override var onDied: (() -> Unit)? = null

    private var isReleased = false

    override fun attach(engine: RackEngine, slotIndex: Int) {
        engine.setSlotPlugin(slotIndex, instance, preparedSampleRate)
    }

    override fun needsReloadAt(sampleRate: Int): Boolean {
        return preparedSampleRate != sampleRate
    }

    /**
     * Records the value in aap-core's value cache, so [readParameterValue] reads it back before the
     * plugin processes it (while paused, not until playback resumes).
     */
    override fun onHostParameterChange(parameter: ParameterInformation, value: Double) {
        val index = plugin.parameters.indexOfFirst { it.id == parameter.id }

        if (index < 0 || instance.state == InstanceState.DESTROYED) {
            return
        }

        instance.setCachedParameterValue(index, value)
    }

    override fun readParameterValues(): Map<Int, Double> {
        return PluginSlotLoader.readParameterValues(plugin, instance)
    }

    override fun readParameterValue(index: Int): Double? {
        return try {
            PluginSlotLoader.queryIfAlive<Double?>(instance, null) { instance.getParameterValue(index) }
        } catch (e: Throwable) {
            null
        }
    }

    override fun readPresets(): List<DevicePreset> {
        return PluginSlotLoader.readPresets(instance, presetCount)
    }

    override fun selectPreset(nativeIndex: Int): Map<Int, Double> {
        PluginSlotLoader.queryIfAlive(instance, Unit) {
            instance.setCurrentPresetIndex(nativeIndex)
        }

        return readParameterValues()
    }

    override fun captureState(): ByteArray? {
        return try {
            PluginSlotLoader.queryUnlessDied<ByteArray?>(instance, null) {
                val stateSize = instance.getStateSize()

                if (stateSize > 0) {
                    ByteArray(stateSize).also { instance.getState(it) }
                } else {
                    null
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to capture the state of ${plugin.displayName}", e)
            null
        }
    }

    override fun release() {
        if (isReleased) {
            return
        }

        isReleased = true
        onRelease(this)
    }

    /**
     * Its process died. Right away, on the thread that noticed: the main thread may be about to query
     * the dead instance (e.g. an autosave), and a Binder call already sent to it is not interrupted
     * by aap-core's reply timeout.
     */
    internal fun onProcessDied() {
        PluginSlotLoader.abandon(instance)
        onDied?.invoke()
    }
}
