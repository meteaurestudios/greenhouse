package org.androidaudioplugin.greenhouse.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.hosting.AudioPluginClientBase
import org.androidaudioplugin.hosting.NativeRemotePluginInstance

private const val DEFAULT_CONTROL_BUFFER_SIZE = 0x10000
const val MAX_HOST_BUFFER_FRAMES = 4096

class SlotPluginHost(
    val context: Context,
    /** Called on a binder or the main thread when the plugin's process dies while its instance is loaded. */
    private val onPluginDied: (NativeRemotePluginInstance) -> Unit
) : AutoCloseable {
    private val tag = "SlotPluginHost"

    @Volatile
    private var currentClient: AudioPluginClientBase? = null

    val client: AudioPluginClientBase?
        get() = currentClient

    suspend fun instantiatePlugin(
        pluginInfo: PluginInformation,
        sampleRate: Int,
        framesPerCallback: Int
    ): Pair<AudioPluginClientBase, NativeRemotePluginInstance> = withContext(Dispatchers.IO) {
        close()

        val newClient = AudioPluginClientBase(context)
        currentClient = newClient

        Log.d(tag, "Connecting to plugin service: ${pluginInfo.packageName}")
        newClient.connectToPluginService(pluginInfo.packageName)

        Log.d(tag, "Instantiating native plugin: ${pluginInfo.pluginId}")
        val instance = newClient.instantiateNativePlugin(pluginInfo)

        // Also fired when the client is disposed on purpose: only a disconnection while it is still current is a death
        newClient.onDisconnectingListeners.add {
            if (currentClient === newClient) {
                Log.w(tag, "Plugin service died: ${pluginInfo.packageName}")
                onPluginDied(instance)
            }
        }

        Log.d(tag, "Preparing plugin instance (SR: $sampleRate, Frames: $MAX_HOST_BUFFER_FRAMES)")
        instance.prepare(MAX_HOST_BUFFER_FRAMES, sampleRate, DEFAULT_CONTROL_BUFFER_SIZE)

        Pair(newClient, instance)
    }

    override fun close() {
        val clientToDispose = currentClient
        currentClient = null

        if (clientToDispose != null) {
            try {
                clientToDispose.dispose()
            } catch (e: Throwable) {
                Log.e(tag, "Error disposing slot AudioPluginClientBase", e)
            }
        }
    }
}

class AapHostEngine(
    val context: Context,
    val numSlots: Int = RackEngine.DEFAULT_NUM_RACK_SLOTS
) : AutoCloseable {
    private val tag = "AapHostEngine"

    /** Called on a binder or the main thread when the process of the plugin loaded in a slot dies. */
    var onSlotPluginDied: ((slotIndex: Int, instance: NativeRemotePluginInstance) -> Unit)? = null

    private val slotHosts = Array(numSlots) { slotIndex ->
        SlotPluginHost(context) { instance ->
            onSlotPluginDied?.invoke(slotIndex, instance)
        }
    }

    suspend fun instantiatePluginForSlot(
        slotIndex: Int,
        pluginInfo: PluginInformation,
        sampleRate: Int,
        framesPerCallback: Int
    ): Pair<AudioPluginClientBase, NativeRemotePluginInstance> {
        require(slotIndex in 0 until numSlots) { "Invalid slotIndex: $slotIndex" }
        return slotHosts[slotIndex].instantiatePlugin(pluginInfo, sampleRate, framesPerCallback)
    }

    fun unloadSlot(slotIndex: Int) {
        if (slotIndex in 0 until numSlots) {
            slotHosts[slotIndex].close()
        }
    }

    fun getSlotClient(slotIndex: Int): AudioPluginClientBase? {
        if (slotIndex in 0 until numSlots) {
            return slotHosts[slotIndex].client
        }

        return null
    }

    override fun close() {
        for (i in 0 until numSlots) {
            try {
                slotHosts[i].close()
            } catch (e: Throwable) {
                Log.e(tag, "Error disposing slot host $i", e)
            }
        }
    }
}
