package org.androidaudioplugin.greenhouse.ui.host

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidaudioplugin.greenhouse.data.PluginCategory
import org.androidaudioplugin.greenhouse.device.DeviceInfo
import org.androidaudioplugin.greenhouse.device.SlotDeviceSource

/** The catalog of devices from every source, and the browser's slot target, developer filter, and search. */
class PluginBrowserController(
    private val sources: List<SlotDeviceSource>,
    private val scope: CoroutineScope,
    private val postStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "PluginBrowserController"
        const val ALL_DEVELOPERS = "ALL"
        const val UNKNOWN_DEVELOPER = "Unknown"

        private val DeviceInfo.developerName: String
            get() = developer?.ifBlank { null } ?: UNKNOWN_DEVELOPER
    }

    /** Every device the sources offer, in source order. */
    var devices by mutableStateOf<List<DeviceInfo>>(emptyList())
        private set

    /** The rack slot the browser is picking a plugin for; decides instrument vs effect filtering. */
    var targetSlotIndex by mutableIntStateOf(RackController.INSTRUMENT_SLOT_INDEX)

    var selectedDeveloper by mutableStateOf(ALL_DEVELOPERS)
        private set

    var searchQuery by mutableStateOf("")
        private set

    val availableDevelopers: List<String>
        get() {
            val developers = devices
                .filter { isAllowedInSlot(it, targetSlotIndex) }
                .map { it.developerName }
                .distinct()
                .sorted()

            return listOf(ALL_DEVELOPERS) + developers
        }

    val filteredDevices: List<DeviceInfo>
        get() {
            return devices.filter { device ->
                val developer = device.developerName
                val matchesDeveloper = selectedDeveloper == ALL_DEVELOPERS || developer == selectedDeveloper
                val matchesSearch = searchQuery.isBlank() ||
                        device.displayName.contains(searchQuery, ignoreCase = true) ||
                        developer.contains(searchQuery, ignoreCase = true) ||
                        device.id.contains(searchQuery, ignoreCase = true)

                isAllowedInSlot(device, targetSlotIndex) && matchesDeveloper && matchesSearch
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
                val found = sources.flatMap { source ->
                    try {
                        source.listDevices()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to list the devices of ${source.id}", e)
                        emptyList()
                    }
                }

                withContext(Dispatchers.Main) {
                    devices = found
                    postStatus("Found ${found.size} plugin(s).")
                    onComplete?.invoke()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to list devices", e)

                withContext(Dispatchers.Main) {
                    postStatus("Error querying plugins: ${e.message}")
                }
            }
        }
    }

    /** Resolves a saved device reference against what the sources offer now. */
    fun findDevice(sourceId: String, id: String?, packageName: String?, displayName: String?): DeviceInfo? {
        val fromSource = devices.filter { it.sourceId == sourceId }
        return fromSource.find { it.id == id }
            ?: fromSource.find { it.packageName != null && it.packageName == packageName && it.displayName == displayName }
    }

    private fun isAllowedInSlot(device: DeviceInfo, slotIndex: Int): Boolean {
        val category = device.category

        if (category == PluginCategory.OTHER) {
            return true
        }

        if (slotIndex == RackController.INSTRUMENT_SLOT_INDEX) {
            return category == PluginCategory.SYNTH
        }

        return category == PluginCategory.EFFECT
    }
}
