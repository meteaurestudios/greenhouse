package org.androidaudioplugin.greenhouse.ui.host

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.greenhouse.data.PluginCategory
import org.androidaudioplugin.greenhouse.data.PluginRepository

/** The installed-plugin catalog and the browser's slot target, developer filter, and search. */
class PluginBrowserController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val postStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "PluginBrowserController"
        const val ALL_DEVELOPERS = "ALL"
        const val UNKNOWN_DEVELOPER = "Unknown"

        private val PluginInformation.developerName: String
            get() = developer?.ifBlank { null } ?: UNKNOWN_DEVELOPER
    }

    private val repository = PluginRepository()

    var plugins by mutableStateOf<List<PluginInformation>>(emptyList())
        private set

    /** The rack slot the browser is picking a plugin for; decides instrument vs effect filtering. */
    var targetSlotIndex by mutableIntStateOf(RackController.INSTRUMENT_SLOT_INDEX)

    var selectedDeveloper by mutableStateOf(ALL_DEVELOPERS)
        private set

    var searchQuery by mutableStateOf("")
        private set

    val availableDevelopers: List<String>
        get() {
            val developers = plugins
                .filter { isAllowedInSlot(it, targetSlotIndex) }
                .map { it.developerName }
                .distinct()
                .sorted()

            return listOf(ALL_DEVELOPERS) + developers
        }

    val filteredPlugins: List<PluginInformation>
        get() {
            return plugins.filter { plugin ->
                val developer = plugin.developerName
                val matchesDeveloper = selectedDeveloper == ALL_DEVELOPERS || developer == selectedDeveloper
                val matchesSearch = searchQuery.isBlank() ||
                        plugin.displayName.contains(searchQuery, ignoreCase = true) ||
                        developer.contains(searchQuery, ignoreCase = true) ||
                        (plugin.pluginId?.contains(searchQuery, ignoreCase = true) == true)

                isAllowedInSlot(plugin, targetSlotIndex) && matchesDeveloper && matchesSearch
            }
        }

    /** Points the browser at [slotIndex] with filters cleared. */
    fun openForSlot(slotIndex: Int) {
        targetSlotIndex = slotIndex
        selectedDeveloper = ALL_DEVELOPERS
        searchQuery = ""
    }

    fun selectDeveloper(developer: String) {
        selectedDeveloper = developer
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
    }

    fun refresh(onComplete: (() -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            try {
                val found = repository.queryPlugins(context)

                withContext(Dispatchers.Main) {
                    plugins = found
                    postStatus("Found ${found.size} AAP plugin(s) on system.")
                    onComplete?.invoke()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to query plugins", e)

                withContext(Dispatchers.Main) {
                    postStatus("Error querying plugins: ${e.message}")
                }
            }
        }
    }

    /** Resolves a saved plugin reference against what's installed now. */
    fun findInstalledPlugin(pluginId: String?, packageName: String?, displayName: String?): PluginInformation? {
        return plugins.find { it.pluginId == pluginId }
            ?: plugins.find { it.packageName == packageName && it.displayName == displayName }
    }

    private fun isAllowedInSlot(plugin: PluginInformation, slotIndex: Int): Boolean {
        val category = repository.getPluginCategory(plugin)

        if (category == PluginCategory.OTHER) {
            return true
        }

        if (slotIndex == RackController.INSTRUMENT_SLOT_INDEX) {
            return category == PluginCategory.SYNTH
        }

        return category == PluginCategory.EFFECT
    }
}
