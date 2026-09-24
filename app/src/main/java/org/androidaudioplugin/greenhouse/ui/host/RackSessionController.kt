package org.androidaudioplugin.greenhouse.ui.host

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidaudioplugin.greenhouse.data.RackPreset
import org.androidaudioplugin.greenhouse.data.RackPresetHeader
import org.androidaudioplugin.greenhouse.data.RackSessionManager
import java.io.File

/** Saved sessions on disk, the currently open session, autosave, and restoring a session into the rack. */
class RackSessionController(
    context: Context,
    private val rack: RackController,
    private val audio: AudioEngineController,
    private val browser: PluginBrowserController,
    private val scope: CoroutineScope,
    private val postStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "RackSessionController"
        const val AUTOSAVE_SESSION_NAME = "Autosave Session"
        const val DEFAULT_CAPTURE_NAME = "Current Session"
        const val IMPORTED_SESSION_NAME = "Imported Session"
    }

    private val sessionManager = RackSessionManager(context)

    var savedSessions by mutableStateOf<List<RackPresetHeader>>(emptyList())
        private set

    var currentSessionName by mutableStateOf<String?>(null)
        private set

    var currentSessionFile by mutableStateOf<File?>(null)
        private set

    var isOperationInProgress by mutableStateOf(false)
        private set

    fun refreshSavedSessions() {
        scope.launch(Dispatchers.IO) {
            val list = sessionManager.listPresets()

            withContext(Dispatchers.Main) {
                savedSessions = list
            }
        }
    }

    /**
     * True when saving under [name] would land on another saved session.
     * Names are compared case-insensitively, and target files are compared too, because names
     * are sanitized into file names and two different names can map to the same file.
     * [ignoreFile] excludes the session being saved in place.
     */
    fun isSessionNameTaken(name: String, ignoreFile: File? = null): Boolean {
        val trimmed = name.trim()

        if (trimmed.isBlank()) {
            return false
        }

        val targetPath = sessionManager.getPresetFile(trimmed).absolutePath
        val ignoredPath = ignoreFile?.absolutePath

        return savedSessions.any { saved ->
            val savedPath = saved.file.absolutePath
            savedPath != ignoredPath &&
                (saved.name.equals(trimmed, ignoreCase = true) || savedPath == targetPath)
        }
    }

    /** True when the one-tap Save can write the current session without clobbering a different one. */
    fun canSaveActiveSessionInPlace(): Boolean {
        val activeName = currentSessionName

        if (activeName.isNullOrBlank()) {
            return false
        }

        return !isSessionNameTaken(activeName, ignoreFile = currentSessionFile)
    }

    fun saveSession(
        name: String,
        onComplete: ((Boolean) -> Unit)? = null,
        overwrite: Boolean = false
    ) {
        val trimmed = name.trim()

        if (trimmed.isBlank()) {
            return
        }

        if (!overwrite && isSessionNameTaken(trimmed)) {
            postStatus("'$trimmed' already exists.")
            onComplete?.invoke(false)
            return
        }

        scope.launch(Dispatchers.IO) {
            val file = sessionManager.savePreset(trimmed, capture(trimmed))

            withContext(Dispatchers.Main) {
                refreshSavedSessions()

                if (file != null) {
                    currentSessionName = trimmed
                    currentSessionFile = file
                    postStatus("Saved '$trimmed'.")
                    onComplete?.invoke(true)
                } else {
                    postStatus("Couldn't save '$trimmed'.")
                    onComplete?.invoke(false)
                }
            }
        }
    }

    fun saveActiveSession(onComplete: ((Boolean) -> Unit)? = null) {
        val activeName = currentSessionName

        if (activeName.isNullOrBlank() || !canSaveActiveSessionInPlace()) {
            onComplete?.invoke(false)
            return
        }

        saveSession(activeName, onComplete, overwrite = true)
    }

    fun loadSessionFromFile(file: File, onComplete: ((Boolean) -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            val preset = sessionManager.loadPreset(file)

            withContext(Dispatchers.Main) {
                if (preset == null) {
                    postStatus("Couldn't open ${file.name}.")
                    onComplete?.invoke(false)
                    return@withContext
                }

                currentSessionName = preset.name.ifBlank { file.nameWithoutExtension }
                currentSessionFile = file
                restore(preset, onComplete)
            }
        }
    }

    fun importSessionFromUri(uri: Uri, onComplete: ((Boolean) -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            val preset = sessionManager.loadPresetFromUri(uri)

            withContext(Dispatchers.Main) {
                if (preset == null) {
                    postStatus("Couldn't import that file.")
                    onComplete?.invoke(false)
                    return@withContext
                }

                currentSessionName = preset.name.ifBlank { IMPORTED_SESSION_NAME }
                currentSessionFile = null
                restore(preset, onComplete)
            }
        }
    }

    fun deleteSession(header: RackPresetHeader) {
        scope.launch(Dispatchers.IO) {
            val deleted = sessionManager.deletePreset(header.file)

            withContext(Dispatchers.Main) {
                if (currentSessionFile == header.file || currentSessionName.equals(header.name, ignoreCase = true)) {
                    currentSessionName = null
                    currentSessionFile = null
                }

                refreshSavedSessions()

                if (deleted) {
                    postStatus("Deleted '${header.name}'.")
                }
            }
        }
    }

    /** Empties the rack and detaches from any saved session. */
    fun startNewSession() {
        rack.unloadAll()
        currentSessionName = null
        currentSessionFile = null
        sessionManager.clearAutosession()
        postStatus("New session started.")
    }

    fun autosave() {
        try {
            sessionManager.saveAutosession(capture(currentSessionName ?: AUTOSAVE_SESSION_NAME))
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to autosave session", e)
        }
    }

    /** Brings back the last autosave on launch, but never over a rack the user already filled. */
    fun restoreAutosaveIfRackEmpty() {
        if (!sessionManager.hasAutosavedSession() || !rack.isEmpty) {
            return
        }

        scope.launch(Dispatchers.IO) {
            val preset = sessionManager.loadAutosession()

            if (preset == null || preset.loadedSlotCount == 0) {
                return@launch
            }

            withContext(Dispatchers.Main) {
                if (preset.name.isNotBlank() && preset.name != AUTOSAVE_SESSION_NAME && preset.name != DEFAULT_CAPTURE_NAME) {
                    currentSessionName = preset.name
                    currentSessionFile = sessionManager.findPresetFile(preset.name)
                }

                restore(preset)
            }
        }
    }

    private fun capture(name: String): RackPreset {
        return RackPreset(name = name, slots = rack.captureSlotStates())
    }

    private fun restore(preset: RackPreset, onComplete: ((Boolean) -> Unit)? = null) {
        if (isOperationInProgress || rack.isInstantiating) {
            return
        }

        isOperationInProgress = true
        postStatus("Loading '${preset.name}'…")

        scope.launch(Dispatchers.Main) {
            val wasAudioActive = audio.isProcessing

            if (wasAudioActive) {
                audio.pause()
            }

            rack.unloadAll()

            var anyError = false

            for (slotState in preset.slots) {
                if (!rack.isValidSlot(slotState.slotIndex) || !slotState.isLoaded || slotState.pluginId.isNullOrBlank()) {
                    continue
                }

                val plugin = browser.findInstalledPlugin(slotState.pluginId, slotState.packageName, slotState.displayName)

                if (plugin == null) {
                    val missingName = slotState.displayName ?: slotState.pluginId
                    Log.w(TAG, "Plugin $missingName not found on device")
                    postStatus("Plugin $missingName not installed on this device")
                    anyError = true
                    continue
                }

                if (!rack.restoreSlot(slotState, plugin)) {
                    anyError = true
                }
            }

            // A session with plugins in it should be playable right away, including the autosave restored at launch.
            if (wasAudioActive || !rack.isEmpty) {
                audio.requestRunning()
            }

            autosave()
            isOperationInProgress = false

            val status = if (anyError) {
                "Loaded '${preset.name}' with warnings (some plugins may be missing)."
            } else {
                "Session '${preset.name}' loaded successfully."
            }

            postStatus(status)
            onComplete?.invoke(!anyError)
        }
    }
}
