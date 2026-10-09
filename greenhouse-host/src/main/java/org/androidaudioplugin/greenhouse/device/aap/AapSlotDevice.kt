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
import org.androidaudioplugin.hosting.ParameterMetadataSnapshot

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
        // Before the first metadata publication: any published revision is newer
        private const val NO_METADATA_REVISION = -1L

        private fun ParameterMetadataSnapshot.toParameterList(): List<ParameterInformation> {
            return parameters.map { it.toParameterInformation() }
        }
    }

    /** Revision of the metadata publication [parameters] comes from. */
    private var metadataRevision: Long

    /** The instance's current parameter list, or the one discovered at instantiation until aap-core publishes it. */
    @Volatile
    override var parameters: List<ParameterInformation>
        private set

    init {
        // Read once: the revision has to match the list
        val metadata = instance.parameterMetadata
        metadataRevision = metadata?.revision ?: NO_METADATA_REVISION
        parameters = metadata?.toParameterList() ?: plugin.parameters.toList()
        Log.d(TAG, "${plugin.displayName}: ${parameters.size} parameters from " +
                if (metadata != null) "metadata revision $metadataRevision" else "discovery")
    }

    override val hasCustomUi: Boolean
        get() = !plugin.uiViewFactory.isNullOrBlank() ||
                !plugin.uiWeb.isNullOrBlank() ||
                !plugin.uiActivity.isNullOrBlank() ||
                plugin.extensions.any { it.uri?.startsWith(GUI_EXTENSION_URI_PREFIX) == true }

    @Volatile
    private var hasDied = false

    /** Called right away if the plugin already died, e.g. while the rack was still setting up the slot. */
    @Volatile
    override var onDied: (() -> Unit)? = null
        set(value) {
            field = value

            if (value != null && hasDied) {
                value()
            }
        }

    override var onParametersChanged: (() -> Unit)? = null

    private var isReleased = false

    // On the main thread. Replays the current publication, which is skipped when [parameters] already comes from it.
    private val metadataSubscription = instance.addParameterMetadataChangedListener { snapshot ->
        Log.d(TAG, "${plugin.displayName}: metadata revision ${snapshot.revision}, ${snapshot.parameters.size} parameters " +
                "(current revision $metadataRevision)")

        if (!isReleased && snapshot.revision > metadataRevision) {
            metadataRevision = snapshot.revision
            parameters = snapshot.toParameterList()
            onParametersChanged?.invoke()
        }
    }

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
        val index = parameters.indexOfFirst { it.id == parameter.id }

        if (index < 0 || instance.state == InstanceState.DESTROYED) {
            return
        }

        instance.setCachedParameterValue(index, value)
    }

    override fun readParameterValues(): Map<Int, Double> {
        return PluginSlotLoader.readParameterValues(parameters, instance)
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
        metadataSubscription.close()
        onRelease(this)
    }

    /**
     * Its process died. Right away, on the thread that noticed: the main thread may be about to query
     * the dead instance (e.g. an autosave), and a Binder call already sent to it is not interrupted
     * by aap-core's reply timeout.
     */
    internal fun onProcessDied() {
        PluginSlotLoader.abandon(instance)
        // Before reading onDied: a listener set meanwhile sees it (the rack ignores a second report)
        hasDied = true
        onDied?.invoke()
    }
}
