package org.androidaudioplugin.greenhouse.ui.host

import android.util.Log
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.greenhouse.core.AapHostEngine
import org.androidaudioplugin.greenhouse.ui.PluginPreset
import org.androidaudioplugin.hosting.AudioPluginClientBase
import org.androidaudioplugin.hosting.InstanceState
import org.androidaudioplugin.hosting.NativeRemotePluginInstance

internal data class LoadedPlugin(
    val client: AudioPluginClientBase,
    val instance: NativeRemotePluginInstance,
    val presetCount: Int,
    /** Parameter values read back from the instance after it was prepared, keyed by parameter ID. */
    val parameterValues: Map<Int, Double>
)

/**
 * Blocking plugin-instance queries (binder IPC). Everything here must run off the main thread.
 */
internal object PluginSlotLoader {
    private const val TAG = "PluginSlotLoader"

    /**
     * Runs [query] against [instance] unless it has been destroyed, returning [fallback] otherwise.
     * Serialized with [destroy]: a destroyed instance is gone from the native client, and any
     * further call on it dereferences null and kills the process (not catchable from Kotlin).
     */
    fun <T> queryIfAlive(instance: NativeRemotePluginInstance, fallback: T, query: () -> T): T {
        synchronized(instance) {
            if (instance.state == InstanceState.DESTROYED) {
                return fallback
            }

            return query()
        }
    }

    /** Destroys [instance], waiting for any in-flight [queryIfAlive] call on it to finish. */
    fun destroy(instance: NativeRemotePluginInstance) {
        synchronized(instance) {
            try {
                instance.destroy()
            } finally {
                // destroy() swallows remote errors without marking the instance destroyed.
                instance.state = InstanceState.DESTROYED
            }
        }
    }

    /**
     * Instantiates [plugin] for [slotIndex], fills in parameters / ports the plugin only exposes
     * at runtime, lets [prepare] apply saved state, then reads back the resulting parameter values.
     */
    suspend fun instantiate(
        hostEngine: AapHostEngine,
        slotIndex: Int,
        plugin: PluginInformation,
        sampleRate: Int,
        framesPerCallback: Int,
        prepare: (NativeRemotePluginInstance) -> Unit = {}
    ): LoadedPlugin {
        val (client, instance) = hostEngine.instantiatePluginForSlot(slotIndex, plugin, sampleRate, framesPerCallback)

        discoverDynamicPortsAndParameters(plugin, instance)
        prepare(instance)

        val presetCount = try {
            instance.getPresetCount()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to query preset count", e)
            0
        }

        return LoadedPlugin(client, instance, presetCount, readParameterValues(plugin, instance))
    }

    /**
     * Current value of every known parameter. Parameters the instance doesn't expose at runtime
     * (declared only in `aap_metadata.xml`) report their default, because `getParameterValue`
     * swallows errors and returns 0.0 for indices it doesn't know.
     */
    fun readParameterValues(plugin: PluginInformation, instance: NativeRemotePluginInstance): Map<Int, Double> {
        val runtimeCount = queryIfAlive(instance, 0) { instance.getParameterCount() }
        val values = mutableMapOf<Int, Double>()

        for ((i, param) in plugin.parameters.withIndex()) {
            values[param.id] = if (i < runtimeCount) {
                queryIfAlive(instance, param.defaultValue) { instance.getParameterValue(i) }
            } else {
                param.defaultValue
            }
        }

        return values
    }

    /** Preset names sorted for browsing: named presets first, ignoring leading punctuation. */
    fun readPresets(instance: NativeRemotePluginInstance, presetCount: Int): List<PluginPreset> {
        val presets = (0 until presetCount).map { i ->
            val name = try {
                queryIfAlive(instance, "") { instance.getPresetName(i) }
            } catch (e: Throwable) {
                ""
            }

            PluginPreset(nativeIndex = i, name = name.ifBlank { "Preset #${i + 1}" })
        }

        return presets.sortedWith(
            compareBy<PluginPreset> { it.name.none { ch -> ch.isLetterOrDigit() } }
                .thenBy(String.CASE_INSENSITIVE_ORDER) {
                    it.name.trimStart { ch -> !ch.isLetterOrDigit() }
                }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }

    /**
     * `aap_metadata.xml` may not declare parameters / ports statically; most plugins expose them
     * over the AAPXS extensions instead, so populate them from the live instance when missing.
     */
    private fun discoverDynamicPortsAndParameters(plugin: PluginInformation, instance: NativeRemotePluginInstance) {
        // A plugin that fails discovery still loads; it just shows fewer controls.
        try {
            if (plugin.parameters.isEmpty()) {
                for (i in 0 until instance.getParameterCount()) {
                    plugin.parameters.add(instance.getParameter(i))
                }
            }

            if (plugin.ports.isEmpty()) {
                for (i in 0 until instance.getPortCount()) {
                    plugin.ports.add(instance.getPort(i))
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to discover dynamic parameters / ports for ${plugin.displayName}", e)
        }
    }
}
