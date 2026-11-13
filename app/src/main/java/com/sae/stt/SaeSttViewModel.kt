package com.sae.stt

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class SaeSttUiState(
    val modelReady: Boolean = false,
    val modelSizeText: String = "",
    val downloading: Boolean = false,
    val downloadProgress: Int = 0,
    val status: String = "",
    val initializingEngine: Boolean = false,
    val recording: Boolean = false,
    val audioLevel: Float = 0f,
    val committedTranscript: String = "",
    val partialTranscript: String = "",
    val error: String = "",
    val permissionPermanentlyDenied: Boolean = false,
) {
    val visibleTranscript: String
        get() = listOf(committedTranscript, partialTranscript)
            .filter { it.isNotBlank() }
            .joinToString(" ")
}

class SaeSttViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val engineExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SaeSTT-engine")
    }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()

    private var engine: SpeechEngine? = null
    private var downloadJob: Job? = null

    var uiState by mutableStateOf(createInitialState())
        private set

    init {
        if (!uiState.modelReady) {
            downloadModel()
        }
    }

    private fun createInitialState(): SaeSttUiState {
        val ready = ModelManager.isInstalled(appContext)
        val sizeText = if (ready) {
            ModelManager.formatBytes(ModelManager.installedSizeBytes(appContext))
        } else {
            ""
        }
        return SaeSttUiState(
            modelReady = ready,
            modelSizeText = sizeText,
            status = if (ready) {
                "Model ready — offline recognition available"
            } else {
                "Downloading model on first launch"
            },
        )
    }

    fun downloadModel() {
        if (uiState.downloading || uiState.modelReady) return
        downloadJob = viewModelScope.launch {
            uiState = uiState.copy(downloading = true, downloadProgress = 0, error = "")
            try {
                ModelManager.download(appContext) { message, percent ->
                    viewModelScope.launch {
                        uiState = uiState.copy(status = message, downloadProgress = percent)
                    }
                }
                val sizeText = ModelManager.formatBytes(ModelManager.installedSizeBytes(appContext))
                uiState = uiState.copy(
                    modelReady = true,
                    modelSizeText = sizeText,
                    downloadProgress = 100,
                    status = "Model ready — recognition now works offline",
                )
            } catch (t: Throwable) {
                uiState = uiState.copy(
                    error = t.message ?: "Model download failed",
                    status = "Download incomplete. Tap retry.",
                )
            } finally {
                uiState = uiState.copy(downloading = false)
            }
        }
    }

    fun redownloadModel() {
        if (uiState.downloading || uiState.initializingEngine || uiState.recording) return
        viewModelScope.launch {
            uiState = uiState.copy(recording = false, error = "", status = "Removing cached model…")
            withContext(engineDispatcher) {
                engine?.close()
                engine = null
            }
            ModelManager.deleteModel(appContext)
            uiState = uiState.copy(modelReady = false, modelSizeText = "", downloadProgress = 0)
            downloadModel()
        }
    }

    fun startRecording() {
        if (!uiState.modelReady || uiState.downloading || uiState.initializingEngine || uiState.recording) {
            return
        }
        uiState = uiState.copy(
            initializingEngine = true,
            error = "",
            permissionPermanentlyDenied = false,
            status = if (engine?.isInitialized == true) "Starting microphone…" else "Loading recognizer…",
        )

        viewModelScope.launch {
            try {
                val activeEngine = withContext(engineDispatcher) {
                    val instance = engine ?: SpeechEngine(
                        modelDir = ModelManager.modelDirectory(appContext),
                        onPartial = { segment ->
                            viewModelScope.launch {
                                if (uiState.recording) {
                                    uiState = uiState.copy(partialTranscript = segment)
                                }
                            }
                        },
                        onFinal = { segment ->
                            viewModelScope.launch {
                                val updated = listOf(uiState.committedTranscript, segment)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" ")
                                uiState = uiState.copy(
                                    committedTranscript = updated,
                                    partialTranscript = "",
                                )
                            }
                        },
                        onAudioLevel = { level ->
                            viewModelScope.launch {
                                uiState = uiState.copy(audioLevel = if (uiState.recording) level else 0f)
                            }
                        },
                        onError = { message ->
                            viewModelScope.launch {
                                uiState = uiState.copy(
                                    recording = false,
                                    audioLevel = 0f,
                                    partialTranscript = "",
                                    error = message,
                                    status = "Recognition error",
                                )
                            }
                        },
                    ).also { engine = it }

                    instance.initialize()
                    instance.start()
                    instance
                }

                if (activeEngine.isRunning) {
                    uiState = uiState.copy(
                        initializingEngine = false,
                        recording = true,
                        status = "Listening…",
                    )
                }
            } catch (t: Throwable) {
                withContext(engineDispatcher) {
                    engine?.close()
                    engine = null
                }
                uiState = uiState.copy(
                    initializingEngine = false,
                    recording = false,
                    audioLevel = 0f,
                    error = t.message ?: "Could not start recognizer",
                    status = "Ready",
                )
            }
        }
    }

    fun stopRecording() {
        if (!uiState.recording) return
        uiState = uiState.copy(recording = false, audioLevel = 0f, status = "Finalizing…")
        viewModelScope.launch {
            withContext(engineDispatcher) {
                engine?.stop()
            }
            uiState = uiState.copy(
                partialTranscript = "",
                status = "Recording stopped",
            )
        }
    }

    fun onPermissionDenied(permanentlyDenied: Boolean) {
        uiState = uiState.copy(
            error = if (permanentlyDenied) {
                "Microphone permission is blocked. Enable it in App Settings."
            } else {
                "Microphone permission was not granted."
            },
            permissionPermanentlyDenied = permanentlyDenied,
        )
    }

    fun clearTranscript() {
        uiState = uiState.copy(committedTranscript = "", partialTranscript = "")
    }

    fun modelDirectoryPath(): String = ModelManager.modelDirectory(appContext).absolutePath

    override fun onCleared() {
        downloadJob?.cancel()
        engineExecutor.execute {
            engine?.close()
            engine = null
        }
        engineExecutor.shutdown()
        super.onCleared()
    }
}
