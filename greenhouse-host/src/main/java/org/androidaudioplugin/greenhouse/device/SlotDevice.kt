package org.androidaudioplugin.greenhouse.device

import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.greenhouse.core.RackEngine
import org.androidaudioplugin.greenhouse.data.PluginCategory

/** A device that can be loaded into a rack slot, as listed by its [SlotDeviceSource]. */
data class DeviceInfo(
    /** [SlotDeviceSource.id] of the source that provides it. */
    val sourceId: String,
    /** Identifies the device within its source, and in saved sessions. */
    val id: String,
    val displayName: String,
    val developer: String? = null,
    /** Decides which slots it can be loaded into. */
    val category: PluginCategory = PluginCategory.OTHER,
    /** The category as the device describes itself, shown in the browser. */
    val categoryLabel: String? = null,
    /** Android package that provides it, if any: a fallback to find it again when its ID changed. */
    val packageName: String? = null
) {
    /** Unique across sources, e.g. for list keys. */
    val key: String
        get() = "$sourceId:$id"
}

/** One of a device's presets. */
data class DevicePreset(
    /** How the device identifies it. */
    val nativeIndex: Int,
    val name: String
)

/** Saved state to apply to a device as it is created. */
class SavedDeviceState(
    /** Opaque state from [SlotDevice.captureState]. */
    val state: ByteArray?,
    /** Native index of the preset that was selected, or a negative value if none. */
    val presetIndex: Int
)

/**
 * What fills a rack slot: an AAP plugin, or a device provided by the app. It puts its processor in
 * the native rack itself ([attach]); the rack keeps everything else about the slot (bypass, level
 * and mix, parameter values shown, sessions).
 *
 * Threading: properties may be read from any thread. The methods noted as such are for the main
 * thread; the others may block (e.g. on IPC) and are called on a background thread.
 */
interface SlotDevice {
    val info: DeviceInfo

    /** Parameters to show and save. Known once the device is created; values are keyed by [ParameterInformation.id]. */
    val parameters: List<ParameterInformation>

    /** Number of presets; the Presets view is shown when there is more than one. */
    val presetCount: Int

    /** Whether it has its own UI for the Plugin UI view. Only AAP plugin UIs can be shown for now: other devices return false. */
    val hasCustomUi: Boolean

    /** Called when the device stops working on its own (e.g. its process died), on any thread. */
    var onDied: (() -> Unit)?

    /** Main thread. Puts its processor in the slot of the native rack. */
    fun attach(engine: RackEngine, slotIndex: Int)

    /** Main thread. Whether it has to be created again to play at the stream's new [sampleRate] (it stays silent until then). */
    fun needsReloadAt(sampleRate: Int): Boolean

    /**
     * Main thread. Sends parameter values to its processor. By default as AAP parameter changes in
     * the slot's UMP input, ordered with the notes on the audio thread.
     */
    fun setParameterValues(engine: RackEngine, slotIndex: Int, values: List<Pair<ParameterInformation, Double>>) {
        engine.setParameterValues(slotIndex, values)
    }

    /** Main thread. A value was just sent to it: lets [readParameterValue] report it before the processor applies it. */
    fun onHostParameterChange(parameter: ParameterInformation, value: Double) {
    }

    /** Current value of every parameter, keyed by ID. */
    fun readParameterValues(): Map<Int, Double>

    /** Current value of the parameter at [index] in [parameters], to follow changes made on the device side; null if unknown. */
    fun readParameterValue(index: Int): Double?

    fun readPresets(): List<DevicePreset>

    /** Applies the preset and returns the parameter values it set, keyed by ID. */
    fun selectPreset(nativeIndex: Int): Map<Int, Double>

    /** Main thread, so it must not block for long. Opaque state for [SavedDeviceState.state]; null if it has none. */
    fun captureState(): ByteArray?

    /** Main thread, after the slot was cleared in the native rack. Frees it; it may already have died. Called once. */
    fun release()
}

/** Lists devices of one kind and creates them. */
interface SlotDeviceSource : AutoCloseable {
    /** Saved in sessions to find the source again: keep it stable. */
    val id: String

    /** The devices that can be loaded now. */
    fun listDevices(): List<DeviceInfo>

    /**
     * Creates [info] for [slotIndex], ready to play at [sampleRate], with [saved] applied. Null if it
     * could not be created.
     */
    suspend fun instantiate(info: DeviceInfo, slotIndex: Int, sampleRate: Int, saved: SavedDeviceState?): SlotDevice?

    override fun close() {
    }
}
