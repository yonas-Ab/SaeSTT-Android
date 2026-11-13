package com.sae.stt

import android.app.Application
import android.media.audiofx.NoiseSuppressor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

data class SaeSttUiState(
    val selectedModel: AsrModelSpec = AsrModelSpec.ZIPFORMER_20M,
    val installedModels: Set<AsrModelSpec> = emptySet(),
    val retainOnlySelected: Boolean = true,
    val modelReady: Boolean = false,
    val modelSizeText: String = "",
    val downloading: Boolean = false,
    val downloadProgress: Int = 0,
    val status: String = "",
    val initializingEngine: Boolean = false,
    val recording: Boolean = false,
    val saveRecordingWav: Boolean = false,
    val noiseSuppressionEnabled: Boolean = false,
    val noiseSuppressionAvailable: Boolean = false,
    val diagnostics: AudioDiagnostics = AudioDiagnostics(),
    val firstPartialMs: Long? = null,
    val finalizationMs: Long? = null,
    val committedTranscript: String = "",
    val partialTranscript: String = "",
    val vocabularyRules: List<CorrectionRule> = emptyList(),
    val recordings: List<File> = emptyList(),
    val selectedWavPath: String? = null,
    val referenceText: String = BenchmarkSuite.prompts.first().text,
    val benchmarking: Boolean = false,
    val benchmarkResults: List<ModelBenchmarkResult> = emptyList(),
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
    private val engineExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "SaeSTT-engine") }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()

    private var engine: SpeechEngine? = null
    private var loadedModelSpec: AsrModelSpec? = null
    private var downloadJob: Job? = null

    var uiState by mutableStateOf(createInitialState())
        private set

    init {
        if (!uiState.modelReady) {
            downloadSelectedModel()
        }
    }

    private fun createInitialState(): SaeSttUiState {
        val selected = ModelManager.getSelectedModel(appContext)
        val installed = AsrModelSpec.entries.filter { ModelManager.isInstalled(appContext, it) }.toSet()
        val ready = selected in installed
        val rules = VocabularyManager.loadRules(appContext)
        val recs = WavStorage.listRecordings(appContext)
        return SaeSttUiState(
            selectedModel = selected,
            installedModels = installed,
            retainOnlySelected = ModelManager.getRetainOnlySelected(appContext),
            modelReady = ready,
            modelSizeText = if (ready) ModelManager.formatBytes(ModelManager.installedSizeBytes(appContext, selected)) else "",
            status = if (ready) "Model ready — offline recognition available" else "Model not downloaded",
            noiseSuppressionAvailable = NoiseSuppressor.isAvailable(),
            vocabularyRules = rules,
            recordings = recs,
            selectedWavPath = recs.firstOrNull()?.absolutePath,
        )
    }

    fun selectModel(spec: AsrModelSpec) {
        if (uiState.downloading || uiState.initializingEngine || uiState.recording || uiState.benchmarking) return
        ModelManager.setSelectedModel(appContext, spec)
        val installed = AsrModelSpec.entries.filter { ModelManager.isInstalled(appContext, it) }.toSet()
        val ready = spec in installed
        uiState = uiState.copy(
            selectedModel = spec,
            installedModels = installed,
            modelReady = ready,
            modelSizeText = if (ready) ModelManager.formatBytes(ModelManager.installedSizeBytes(appContext, spec)) else "",
            status = if (ready) "Switched to ${spec.displayName}" else "Tap download to install ${spec.displayName}",
            error = "",
        )
    }

    fun setRetainOnlySelected(retain: Boolean) {
        ModelManager.setRetainOnlySelected(appContext, retain)
        uiState = uiState.copy(retainOnlySelected = retain)
    }

    fun setNoiseSuppression(enabled: Boolean) {
        uiState = uiState.copy(noiseSuppressionEnabled = enabled)
    }

    fun setSaveRecordingWav(enabled: Boolean) {
        uiState = uiState.copy(saveRecordingWav = enabled)
    }

    fun downloadSelectedModel() {
        val target = uiState.selectedModel
        if (uiState.downloading || ModelManager.isInstalled(appContext, target)) return
        downloadJob = viewModelScope.launch {
            uiState = uiState.copy(downloading = true, downloadProgress = 0, error = "")
            try {
                ModelManager.download(appContext, target) { message, percent ->
                    viewModelScope.launch {
                        uiState = uiState.copy(status = message, downloadProgress = percent)
                    }
                }
                val installed = AsrModelSpec.entries.filter { ModelManager.isInstalled(appContext, it) }.toSet()
                uiState = uiState.copy(
                    installedModels = installed,
                    modelReady = true,
                    modelSizeText = ModelManager.formatBytes(ModelManager.installedSizeBytes(appContext, target)),
                    downloadProgress = 100,
                    status = "${target.displayName} ready — works offline",
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

    fun redownloadSelectedModel() {
        if (uiState.downloading || uiState.initializingEngine || uiState.recording || uiState.benchmarking) return
        val target = uiState.selectedModel
        viewModelScope.launch {
            uiState = uiState.copy(recording = false, error = "", status = "Removing cached model…")
            withContext(engineDispatcher) {
                engine?.close()
                engine = null
                loadedModelSpec = null
            }
            ModelManager.deleteModel(appContext, target)
            val installed = AsrModelSpec.entries.filter { ModelManager.isInstalled(appContext, it) }.toSet()
            uiState = uiState.copy(
                installedModels = installed,
                modelReady = false,
                modelSizeText = "",
                downloadProgress = 0,
            )
            downloadSelectedModel()
        }
    }

    fun startRecording(forceSaveWav: Boolean = false) {
        val target = uiState.selectedModel
        if (!uiState.modelReady || uiState.downloading || uiState.initializingEngine || uiState.recording || uiState.benchmarking) {
            return
        }
        val wavOut = if (forceSaveWav || uiState.saveRecordingWav) {
            WavStorage.newRecordingFile(appContext)
        } else {
            null
        }

        uiState = uiState.copy(
            initializingEngine = true,
            error = "",
            permissionPermanentlyDenied = false,
            firstPartialMs = null,
            finalizationMs = null,
            status = if (engine != null && loadedModelSpec == target) "Starting microphone…" else "Loading ${target.displayName}…",
        )

        viewModelScope.launch {
            try {
                val activeEngine = withContext(engineDispatcher) {
                    if (engine != null && loadedModelSpec != target) {
                        engine?.close()
                        engine = null
                        loadedModelSpec = null
                    }
                    val instance = engine ?: createEngineFor(target).also {
                        engine = it
                        loadedModelSpec = target
                    }
                    instance.initialize()
                    instance.start(
                        enableNoiseSuppression = uiState.noiseSuppressionEnabled,
                        recordWavTo = wavOut,
                    )
                    instance
                }

                if (activeEngine.isRunning) {
                    uiState = uiState.copy(
                        initializingEngine = false,
                        recording = true,
                        status = if (wavOut != null) "Listening (recording WAV)…" else "Listening…",
                    )
                }
            } catch (t: Throwable) {
                withContext(engineDispatcher) {
                    engine?.close()
                    engine = null
                    loadedModelSpec = null
                }
                uiState = uiState.copy(
                    initializingEngine = false,
                    recording = false,
                    diagnostics = AudioDiagnostics(),
                    error = t.message ?: "Could not start recognizer",
                    status = "Ready",
                )
            }
        }
    }

    private fun createEngineFor(spec: AsrModelSpec): SpeechEngine {
        return SpeechEngine(
            modelDir = ModelManager.modelDirectory(appContext, spec),
            modelType = spec.modelType,
            hotwordsFile = VocabularyManager.hotwordsFile(appContext),
            onPartial = { rawSegment, firstMs ->
                viewModelScope.launch {
                    if (uiState.recording) {
                        val corrected = VocabularyManager.applyCorrections(rawSegment, uiState.vocabularyRules)
                        uiState = uiState.copy(
                            partialTranscript = corrected,
                            firstPartialMs = firstMs ?: uiState.firstPartialMs,
                        )
                    }
                }
            },
            onFinal = { rawSegment, finalMs ->
                viewModelScope.launch {
                    val corrected = VocabularyManager.applyCorrections(rawSegment, uiState.vocabularyRules)
                    val updated = listOf(uiState.committedTranscript, corrected)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    uiState = uiState.copy(
                        committedTranscript = updated,
                        partialTranscript = "",
                        finalizationMs = finalMs ?: uiState.finalizationMs,
                    )
                }
            },
            onDiagnostics = { diag ->
                viewModelScope.launch {
                    uiState = uiState.copy(
                        diagnostics = if (uiState.recording) diag else AudioDiagnostics(),
                    )
                }
            },
            onError = { message ->
                viewModelScope.launch {
                    uiState = uiState.copy(
                        recording = false,
                        diagnostics = AudioDiagnostics(),
                        partialTranscript = "",
                        error = message,
                        status = "Recognition error",
                    )
                }
            },
        )
    }

    fun stopRecording() {
        if (!uiState.recording) return
        uiState = uiState.copy(recording = false, diagnostics = AudioDiagnostics(), status = "Finalizing…")
        viewModelScope.launch {
            withContext(engineDispatcher) {
                engine?.stop()
            }
            val recs = WavStorage.listRecordings(appContext)
            uiState = uiState.copy(
                partialTranscript = "",
                recordings = recs,
                selectedWavPath = uiState.selectedWavPath ?: recs.firstOrNull()?.absolutePath,
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
        uiState = uiState.copy(
            committedTranscript = "",
            partialTranscript = "",
            firstPartialMs = null,
            finalizationMs = null,
        )
    }

    fun addVocabularyRule(pattern: String, replacement: String, isRegex: Boolean, note: String) {
        if (pattern.isBlank() || replacement.isBlank()) return
        val updated = uiState.vocabularyRules + CorrectionRule(
            id = UUID.randomUUID().toString().take(8),
            pattern = pattern.trim(),
            replacement = replacement.trim(),
            isRegex = isRegex,
            note = note.trim(),
        )
        VocabularyManager.saveRules(appContext, updated)
        uiState = uiState.copy(vocabularyRules = updated)
    }

    fun deleteVocabularyRule(id: String) {
        val updated = uiState.vocabularyRules.filterNot { it.id == id }
        VocabularyManager.saveRules(appContext, updated)
        uiState = uiState.copy(vocabularyRules = updated)
    }

    fun resetVocabularyRules() {
        val defaults = VocabularyManager.resetToDefaults(appContext)
        uiState = uiState.copy(vocabularyRules = defaults)
    }

    fun refreshRecordings() {
        val recs = WavStorage.listRecordings(appContext)
        uiState = uiState.copy(
            recordings = recs,
            selectedWavPath = recs.firstOrNull { it.absolutePath == uiState.selectedWavPath }?.absolutePath
                ?: recs.firstOrNull()?.absolutePath,
        )
    }

    fun selectRecording(path: String) {
        uiState = uiState.copy(selectedWavPath = path)
    }

    fun deleteRecording(file: File) {
        file.delete()
        refreshRecordings()
    }

    fun setReferenceText(text: String) {
        uiState = uiState.copy(referenceText = text)
    }

    fun runBenchmark() {
        val wavPath = uiState.selectedWavPath ?: return
        val wavFile = File(wavPath)
        if (!wavFile.exists() || uiState.benchmarking || uiState.recording) return

        val installed = AsrModelSpec.entries.filter { ModelManager.isInstalled(appContext, it) }
        if (installed.isEmpty()) {
            uiState = uiState.copy(error = "Download at least one model to benchmark.")
            return
        }

        uiState = uiState.copy(benchmarking = true, error = "", benchmarkResults = emptyList(), status = "Running benchmark…")
        val reference = uiState.referenceText
        val rules = uiState.vocabularyRules

        viewModelScope.launch {
            try {
                val results = withContext(engineDispatcher) {
                    engine?.close()
                    engine = null
                    loadedModelSpec = null

                    val samples = WavReader.readMono16kFloat(wavFile)
                    installed.map { spec ->
                        val benchEngine = createEngineFor(spec)
                        try {
                            benchEngine.initialize()
                            val metrics = benchEngine.decodeWavSamples(samples)
                            val corrected = VocabularyManager.applyCorrections(metrics.rawText, rules)
                            val wer = if (reference.isNotBlank()) {
                                BenchmarkSuite.computeWer(reference, corrected)
                            } else {
                                null
                            }
                            ModelBenchmarkResult(
                                model = spec,
                                rawTranscript = metrics.rawText,
                                correctedTranscript = corrected,
                                wer = wer,
                                targetNamesMatched = BenchmarkSuite.checkTargetNames(reference, corrected),
                                wallMs = metrics.wallMs,
                                cpuMs = metrics.cpuMs,
                                rtf = metrics.rtf,
                                peakNativeRamMb = metrics.peakNativeRamMb,
                            )
                        } finally {
                            benchEngine.close()
                        }
                    }
                }
                uiState = uiState.copy(
                    benchmarking = false,
                    benchmarkResults = results,
                    status = "Benchmark complete (${results.size} model(s))",
                )
            } catch (t: Throwable) {
                uiState = uiState.copy(
                    benchmarking = false,
                    error = t.message ?: "Benchmark failed",
                    status = "Benchmark error",
                )
            }
        }
    }

    fun modelDirectoryPath(): String = ModelManager.modelDirectory(appContext, uiState.selectedModel).absolutePath
    fun vocabularyFilePath(): String = VocabularyManager.correctionsFile(appContext).absolutePath
    fun recordingsDirectoryPath(): String = WavStorage.recordingsDirectory(appContext).absolutePath

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
