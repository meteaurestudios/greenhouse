package org.androidaudioplugin.host.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.hosting.AudioPluginClientBase
import org.androidaudioplugin.hosting.NativeRemotePluginInstance

private const val DEFAULT_CONTROL_BUFFER_SIZE = 0x10000

class AapHostEngine(val context: Context) : AutoCloseable {
    private val tag = "AapHostEngine"

    val client: AudioPluginClientBase = AudioPluginClientBase(context)

    suspend fun instantiatePlugin(
        pluginInfo: PluginInformation,
        sampleRate: Int,
        framesPerCallback: Int
    ): NativeRemotePluginInstance = withContext(Dispatchers.IO) {
        Log.d(tag, "Connecting to plugin service: ${pluginInfo.packageName}")
        client.connectToPluginService(pluginInfo.packageName)

        Log.d(tag, "Instantiating native plugin: ${pluginInfo.pluginId}")
        val instance = client.instantiateNativePlugin(pluginInfo)

        Log.d(tag, "Preparing plugin instance (SR: $sampleRate, Frames: $framesPerCallback)")
        instance.prepare(framesPerCallback, sampleRate, DEFAULT_CONTROL_BUFFER_SIZE)

        instance
    }

    override fun close() {
        try {
            client.dispose()
        } catch (e: Throwable) {
            Log.e(tag, "Error disposing AudioPluginClient", e)
        }
    }
}
