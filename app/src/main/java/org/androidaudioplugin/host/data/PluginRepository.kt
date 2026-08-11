package org.androidaudioplugin.host.data

import android.content.Context
import android.util.Log
import org.androidaudioplugin.AudioPluginServiceHelper
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.PluginServiceInformation
import org.androidaudioplugin.hosting.AudioPluginHostHelper

data class PluginCategory(
    val id: String,
    val title: String
) {
    companion object {
        val ALL = PluginCategory("all", "All Plugins")
        val SYNTH = PluginCategory("synth", "Instruments & Synths")
        val EFFECT = PluginCategory("effect", "Audio Effects")
        val OTHER = PluginCategory("other", "Utilities & Other")
    }
}

class PluginRepository {
    private val tag = "AAPPluginRepository"

    fun queryPlugins(context: Context): List<PluginInformation> {
        val discoveredPlugins = mutableListOf<PluginInformation>()

        try {
            // Query all registered AAP plugin services on the system
            val services = AudioPluginHostHelper.queryAudioPluginServices(context)
            for (service in services) {
                discoveredPlugins.addAll(service.plugins)
            }
        } catch (e: Throwable) {
            Log.e(tag, "Error querying system AAP services", e)
        }

        try {
            // Also include local plugin service if declared in host app
            val localService = AudioPluginServiceHelper.getLocalAudioPluginService(context)
            if (localService != null) {
                for (plugin in localService.plugins) {
                    if (!discoveredPlugins.any { it.pluginId == plugin.pluginId }) {
                        discoveredPlugins.add(plugin)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.d(tag, "No local plugins found or error querying local service", e)
        }

        return discoveredPlugins
    }

    fun getPluginCategory(plugin: PluginInformation): PluginCategory {
        val category = plugin.category?.lowercase() ?: ""
        return when {
            category.contains("synth") || category.contains("instrument") -> PluginCategory.SYNTH
            category.contains("effect") || category.contains("filter") || category.contains("delay") || category.contains("reverb") -> PluginCategory.EFFECT
            else -> PluginCategory.OTHER
        }
    }
}
