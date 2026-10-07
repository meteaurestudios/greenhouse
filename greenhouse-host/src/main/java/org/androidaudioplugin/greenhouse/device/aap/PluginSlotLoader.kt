package org.androidaudioplugin.greenhouse.device.aap

import android.util.Log
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.greenhouse.core.AapHostEngine
import org.androidaudioplugin.greenhouse.device.DevicePreset
import org.androidaudioplugin.hosting.InstanceState
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal data class LoadedPlugin(
    val instance: NativeRemotePluginInstance,
    val presetCount: Int
)

/**
 * Blocking plugin-instance queries (binder IPC). Everything here must run off the main thread, except [queryUnlessDied].
 */
internal object PluginSlotLoader {
    private const val TAG = "PluginSlotLoader"

    /** Completed when an instance's plugin process dies (see [abandon]). */
    private val deathSignals = ConcurrentHashMap<NativeRemotePluginInstance, CompletableFuture<Unit>>()

    /** Runs [queryUnlessDied] requests. A thread whose plugin died mid-request stays blocked for good. */
    private val queryExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "PluginQuery").apply { isDaemon = true }
    }

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

    /**
     * Like [queryIfAlive], but returns [fallback] as soon as the plugin's process dies instead of
     * waiting forever: aap-core bounds its wait for AAPXS replies, but not a Binder call already sent
     * to a plugin that stopped responding. Blocks the caller while
     * the query runs, so it can be used where the query must not overlap other work (e.g. the main thread).
     */
    fun <T> queryUnlessDied(instance: NativeRemotePluginInstance, fallback: T, query: () -> T): T {
        val died = deathSignal(instance)
        val result = CompletableFuture.supplyAsync({ queryIfAlive(instance, fallback, query) }, queryExecutor)
        CompletableFuture.anyOf(result, died).join()

        if (!result.isDone) {
            Log.w(TAG, "Plugin died while a query was in flight: its reply will never arrive")
            return fallback
        }

        return result.join()
    }

    /** Destroys [instance], waiting for any in-flight [queryIfAlive] call on it to finish. */
    fun destroy(instance: NativeRemotePluginInstance) {
        deathSignals.remove(instance)

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
     * Marks [instance], whose plugin process died, as destroyed without calling into it, so no new
     * [queryIfAlive] call reaches it; disposing its client frees it. Does not take the lock: a call
     * already in flight may never return (aap-core does not interrupt a Binder call already sent).
     */
    fun abandon(instance: NativeRemotePluginInstance) {
        instance.state = InstanceState.DESTROYED
        deathSignals.remove(instance)?.complete(Unit)
    }

    private fun deathSignal(instance: NativeRemotePluginInstance): CompletableFuture<Unit> {
        return deathSignals.computeIfAbsent(instance) { CompletableFuture() }
    }

    /**
     * Instantiates [plugin] for [slotIndex], fills in parameters / ports the plugin only exposes
     * at runtime, and lets [prepare] apply saved state. Returns null if the plugin service fails to
     * create the instance.
     */
    suspend fun instantiate(
        hostEngine: AapHostEngine,
        slotIndex: Int,
        plugin: PluginInformation,
        sampleRate: Int,
        prepare: (NativeRemotePluginInstance) -> Unit = {}
    ): LoadedPlugin? {
        val (_, instance) = hostEngine.instantiatePluginForSlot(slotIndex, plugin, sampleRate)
            ?: return null

        discoverDynamicPortsAndParameters(plugin, instance)
        prepare(instance)

        val presetCount = try {
            instance.getPresetCount()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to query preset count", e)
            0
        }

        return LoadedPlugin(instance, presetCount)
    }

    /**
     * Current value of each of [parameters], the instance's parameters in its order. Parameters the
     * instance doesn't expose at runtime (declared only in `aap_metadata.xml`) report their default,
     * because `getParameterValue` swallows errors and returns 0.0 for indices it doesn't know.
     */
    fun readParameterValues(parameters: List<ParameterInformation>, instance: NativeRemotePluginInstance): Map<Int, Double> {
        val runtimeCount = queryIfAlive(instance, 0) { instance.getParameterCount() }
        val values = mutableMapOf<Int, Double>()

        for ((i, param) in parameters.withIndex()) {
            values[param.id] = if (i < runtimeCount) {
                queryIfAlive(instance, param.defaultValue) { instance.getParameterValue(i) }
            } else {
                param.defaultValue
            }
        }

        return values
    }

    /** Preset names sorted for browsing: named presets first, ignoring leading punctuation. */
    fun readPresets(instance: NativeRemotePluginInstance, presetCount: Int): List<DevicePreset> {
        val presets = (0 until presetCount).map { i ->
            val name = try {
                queryIfAlive(instance, "") { instance.getPresetName(i) }
            } catch (e: Throwable) {
                ""
            }

            DevicePreset(nativeIndex = i, name = name.ifBlank { "Preset #${i + 1}" })
        }

        return presets.sortedWith(
            compareBy<DevicePreset> { it.name.none { ch -> ch.isLetterOrDigit() } }
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
