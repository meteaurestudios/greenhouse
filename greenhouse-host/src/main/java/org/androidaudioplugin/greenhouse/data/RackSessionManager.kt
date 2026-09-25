package org.androidaudioplugin.greenhouse.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Manages file-system storage for autosaved sessions and user-created rack presets (.ghrack).
 */
class RackSessionManager(private val context: Context) {
    companion object {
        private const val TAG = "RackSessionManager"
        private const val SESSION_DIR_NAME = "session"
        private const val PRESETS_DIR_NAME = "presets"
        private const val AUTOSAVE_FILE_NAME = "autosave_session.ghrack"
    }

    private val sessionDir: File
        get() = File(context.filesDir, SESSION_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }

    private val presetsDir: File
        get() = File(context.filesDir, PRESETS_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }

    val autosaveFile: File
        get() = File(sessionDir, AUTOSAVE_FILE_NAME)

    fun hasAutosavedSession(): Boolean {
        return autosaveFile.exists() && autosaveFile.length() > 0
    }

    fun saveAutosession(preset: RackPreset): Boolean {
        return try {
            val json = RackStateSerializer.serializeToJson(preset)
            autosaveFile.writeText(json)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Error saving autosession", e)
            false
        }
    }

    fun loadAutosession(): RackPreset? {
        return try {
            if (!hasAutosavedSession()) {
                return null
            }

            val json = autosaveFile.readText()
            RackStateSerializer.deserializeFromJson(json)
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading autosession", e)
            null
        }
    }

    fun clearAutosession(): Boolean {
        return try {
            if (autosaveFile.exists()) {
                autosaveFile.delete()
            } else {
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error clearing autosession", e)
            false
        }
    }

    fun getPresetFile(name: String): File {
        val sanitizedName = sanitizeFilename(name)
        return File(presetsDir, "$sanitizedName.${RackPreset.FILE_EXTENSION}")
    }

    fun findPresetFile(name: String): File? {
        val file = getPresetFile(name)

        if (file.exists()) {
            return file
        }

        return null
    }

    fun savePreset(name: String, preset: RackPreset): File? {
        val sanitizedName = sanitizeFilename(name)

        if (sanitizedName.isBlank()) {
            return null
        }

        val targetFile = File(presetsDir, "$sanitizedName.${RackPreset.FILE_EXTENSION}")

        return try {
            val updatedPreset = preset.copy(
                name = name,
                modifiedAt = System.currentTimeMillis()
            )
            val json = RackStateSerializer.serializeToJson(updatedPreset)
            targetFile.writeText(json)
            targetFile
        } catch (e: Throwable) {
            Log.e(TAG, "Error saving preset $name", e)
            null
        }
    }

    fun loadPreset(file: File): RackPreset? {
        return try {
            if (!file.exists() || !file.canRead()) {
                return null
            }

            val json = file.readText()
            RackStateSerializer.deserializeFromJson(json)
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading preset from file ${file.name}", e)
            null
        }
    }

    fun loadPresetFromUri(uri: Uri): RackPreset? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                loadPresetFromStream(inputStream)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading preset from URI $uri", e)
            null
        }
    }

    fun loadPresetFromStream(inputStream: InputStream): RackPreset? {
        return try {
            val json = inputStream.bufferedReader().use { it.readText() }
            RackStateSerializer.deserializeFromJson(json)
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading preset from stream", e)
            null
        }
    }

    fun exportPresetToStream(preset: RackPreset, outputStream: OutputStream): Boolean {
        return try {
            val json = RackStateSerializer.serializeToJson(preset)
            outputStream.bufferedWriter().use { it.write(json) }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Error exporting preset to stream", e)
            false
        }
    }

    fun listPresets(): List<RackPresetHeader> {
        return try {
            val files = presetsDir.listFiles { file ->
                file.isFile && file.extension.equals(RackPreset.FILE_EXTENSION, ignoreCase = true)
            } ?: emptyArray()

            files.mapNotNull { file ->
                try {
                    val json = file.readText()
                    val preset = RackStateSerializer.deserializeFromJson(json)

                    if (preset != null) {
                        RackPresetHeader(
                            name = preset.name.ifBlank { file.nameWithoutExtension },
                            file = file,
                            modifiedAt = file.lastModified(),
                            loadedSlotCount = preset.loadedSlotCount,
                            pluginNames = preset.pluginDisplayNames
                        )
                    } else {
                        null
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Error parsing preset header for ${file.name}", e)
                    null
                }
            }.sortedByDescending { it.modifiedAt }
        } catch (e: Throwable) {
            Log.e(TAG, "Error listing presets", e)
            emptyList()
        }
    }

    fun deletePreset(file: File): Boolean {
        return try {
            if (file.exists()) {
                file.delete()
            } else {
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error deleting preset file ${file.name}", e)
            false
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
}
