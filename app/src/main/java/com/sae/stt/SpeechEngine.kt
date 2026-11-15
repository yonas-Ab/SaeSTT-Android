package com.sae.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Debug
import android.os.Process
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

data class AudioDiagnostics(
    val level: Float = 0f,
    val peak: Float = 0f,
    val noiseFloor: Float = 0.005f,
    val speechActive: Boolean = false,
    val clipping: Boolean = false,
    val tooQuiet: Boolean = false,
)

data class OfflineDecodeMetrics(
    val rawText: String,
    val wallMs: Long,
    val cpuMs: Long,
    val rtf: Float,
    val peakNativeRamMb: Float,
)

class SpeechEngine(
    private val modelDir: File,
    private val modelType: String = "zipformer",
    private val hotwordsFile: File? = null,
    private val onPartial: (text: String, firstPartialMs: Long?) -> Unit,
    private val onFinal: (text: String, finalizationMs: Long?) -> Unit,
    private val onDiagnostics: (AudioDiagnostics) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 1_600 // 100 ms
        private const val PRE_ROLL_CHUNKS = 3 // 300 ms pre-roll
        private const val TAIL_PADDING_SAMPLES = 6_400 // 400 ms flush
    }

    @Volatile
    private var running = false
    private var recorder: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var recognizer: OnlineRecognizer? = null
    private var worker: Thread? = null

    val isInitialized: Boolean
        get() = recognizer != null

    val isRunning: Boolean
        get() = running

    fun initialize() {
        if (recognizer != null) return

        val bpeVocabFile = File(modelDir, "bpe.vocab")
        val useHotwords = bpeVocabFile.exists() && hotwordsFile != null && hotwordsFile.exists()
        val decoderFile =
            File(modelDir, "decoder-epoch-99-avg-1.int8.onnx")
                .takeIf { it.isFile }
                ?: File(modelDir, "decoder-epoch-99-avg-1.onnx")

        val model = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                decoder = decoderFile.absolutePath,
                joiner = File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
            ),
            tokens = File(modelDir, "tokens.txt").absolutePath,
            numThreads = 2,
            provider = "cpu",
            modelType = modelType,
            modelingUnit = if (useHotwords) "bpe" else "",
            bpeVocab = if (useHotwords) bpeVocabFile.absolutePath else "",
        )

        val endpointConfig = EndpointConfig(
            rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0.0f),
            rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 0.8f, minUtteranceLength = 0.0f),
            rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.0f, minUtteranceLength = 20.0f),
        )

        recognizer = OnlineRecognizer(
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = model,
                endpointConfig = endpointConfig,
                enableEndpoint = true,
                decodingMethod = if (useHotwords) "modified_beam_search" else "greedy_search",
                maxActivePaths = 4,
                hotwordsFile = if (useHotwords) hotwordsFile!!.absolutePath else "",
                hotwordsScore = 1.5f,
            )
        )
    }

    @SuppressLint("MissingPermission")
    fun start(
        enableNoiseSuppression: Boolean = false,
        recordWavTo: File? = null,
    ) {
        check(recognizer != null) { "Recognizer is not initialized" }
        if (running) return

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "Invalid microphone buffer size: $minBuffer" }

        val bufferBytes = maxOf(minBuffer * 2, SAMPLE_RATE * 2)
        val audio = createAudioRecord(bufferBytes)
        check(audio.state == AudioRecord.STATE_INITIALIZED) {
            audio.release()
            "Could not initialize microphone recorder"
        }

        if (enableNoiseSuppression && NoiseSuppressor.isAvailable()) {
            noiseSuppressor = try {
                NoiseSuppressor.create(audio.audioSessionId)?.apply { enabled = true }
            } catch (_: Throwable) {
                null
            }
        }

        try {
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone failed to enter recording state"
            }
        } catch (t: Throwable) {
            noiseSuppressor?.release()
            noiseSuppressor = null
            audio.release()
            throw t
        }

        recorder = audio
        running = true
        worker = thread(name = "SaeSTT-audio") {
            processAudio(audio, recordWavTo)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferBytes: Int): AudioRecord {
        val preferred = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (preferred.state == AudioRecord.STATE_INITIALIZED) return preferred
        preferred.release()
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
    }

    private fun processAudio(audio: AudioRecord, wavFile: File?) {
        val asr = recognizer ?: return
        val stream = asr.createStream()
        val wavWriter = wavFile?.let { WavWriter(it, SAMPLE_RATE) }

        val shortBuffer = ShortArray(CHUNK_SAMPLES)
        val preRoll = ArrayDeque<FloatArray>(PRE_ROLL_CHUNKS + 1)

        var noiseFloor = 0.004f
        var speechActive = false
        var silenceChunks = 0
        var quietChunks = 0
        var peakHold = 0f

        var speechOnsetAtMs = 0L
        var firstPartialReported = false
        var lastPartial = ""

        try {
            while (running) {
                val count = audio.read(shortBuffer, 0, shortBuffer.size)
                if (count < 0) {
                    if (running) error("Microphone read error ($count)")
                    break
                }
                if (count == 0) continue

                wavWriter?.writeShorts(shortBuffer, count)

                val samples = FloatArray(count)
                var sumSquares = 0.0
                var maxAbs = 0f
                for (i in 0 until count) {
                    val s = shortBuffer[i] / 32768.0f
                    samples[i] = s
                    sumSquares += s * s
                    val a = abs(s)
                    if (a > maxAbs) maxAbs = a
                }

                val rms = sqrt(sumSquares / count).toFloat()
                peakHold = maxOf(rms, peakHold * 0.92f)
                val clipping = maxAbs >= 0.98f

                if (!speechActive) {
                    noiseFloor = noiseFloor * 0.9f + rms * 0.1f
                }
                val vadThreshold = maxOf(0.012f, noiseFloor * 2.5f)
                val isVoiceFrame = rms >= vadThreshold

                if (isVoiceFrame) {
                    if (!speechActive) {
                        speechActive = true
                        speechOnsetAtMs = System.currentTimeMillis()
                        firstPartialReported = false
                        // Feed 300 ms pre-roll so the first syllable isn't cut off
                        while (preRoll.isNotEmpty()) {
                            stream.acceptWaveform(preRoll.removeFirst(), SAMPLE_RATE)
                        }
                    }
                    silenceChunks = 0
                    quietChunks = 0
                } else {
                    if (speechActive) {
                        silenceChunks++
                        if (silenceChunks >= 8) { // ~800 ms silence
                            speechActive = false
                        }
                    } else {
                        if (rms < 0.002f) quietChunks++ else quietChunks = 0
                    }
                }

                onDiagnostics(
                    AudioDiagnostics(
                        level = (rms * 4.5f).coerceIn(0f, 1f),
                        peak = (peakHold * 4.5f).coerceIn(0f, 1f),
                        noiseFloor = noiseFloor,
                        speechActive = speechActive,
                        clipping = clipping,
                        tooQuiet = quietChunks >= 25,
                    )
                )

                if (!speechActive && silenceChunks >= 8) {
                    // Keep rolling pre-roll buffer while idle
                    if (preRoll.size >= PRE_ROLL_CHUNKS) preRoll.removeFirst()
                    preRoll.addLast(samples)
                    continue
                }

                if (!speechActive && preRoll.isNotEmpty()) {
                    if (preRoll.size >= PRE_ROLL_CHUNKS) preRoll.removeFirst()
                    preRoll.addLast(samples)
                }

                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (asr.isReady(stream)) {
                    asr.decode(stream)
                }

                val current = asr.getResult(stream).text.trim()
                if (current != lastPartial) {
                    lastPartial = current
                    val firstMs = if (current.isNotEmpty() && !firstPartialReported && speechOnsetAtMs > 0L) {
                        firstPartialReported = true
                        System.currentTimeMillis() - speechOnsetAtMs
                    } else {
                        null
                    }
                    onPartial(current, firstMs)
                }

                if (asr.isEndpoint(stream)) {
                    if (current.isNotEmpty()) {
                        val finalMs = if (speechOnsetAtMs > 0L) {
                            System.currentTimeMillis() - speechOnsetAtMs
                        } else {
                            null
                        }
                        onFinal(current, finalMs)
                    }
                    lastPartial = ""
                    speechActive = false
                    silenceChunks = 0
                    asr.reset(stream)
                }
            }

            val flushStart = System.currentTimeMillis()
            stream.acceptWaveform(FloatArray(TAIL_PADDING_SAMPLES), SAMPLE_RATE)
            stream.inputFinished()
            while (asr.isReady(stream)) {
                asr.decode(stream)
            }
            val remaining = asr.getResult(stream).text.trim()
            if (remaining.isNotEmpty()) {
                onFinal(remaining, System.currentTimeMillis() - flushStart)
            }
        } catch (t: Throwable) {
            if (running) {
                running = false
                onError(t.message ?: t.javaClass.simpleName)
            }
        } finally {
            try {
                wavWriter?.close()
            } catch (_: Throwable) {
            }
            onDiagnostics(AudioDiagnostics())
            stream.release()
        }
    }

    fun decodeWavSamples(samples: FloatArray): OfflineDecodeMetrics {
        val asr = recognizer ?: error("Recognizer is not initialized")
        val stream = asr.createStream()
        val segments = mutableListOf<String>()

        val cpuStart = Process.getElapsedCpuTime()
        val wallStart = System.currentTimeMillis()
        var peakNativeBytes = Debug.getNativeHeapAllocatedSize()

        try {
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + CHUNK_SAMPLES, samples.size)
                val chunk = samples.copyOfRange(offset, end)
                stream.acceptWaveform(chunk, SAMPLE_RATE)
                while (asr.isReady(stream)) {
                    asr.decode(stream)
                }
                if (asr.isEndpoint(stream)) {
                    val seg = asr.getResult(stream).text.trim()
                    if (seg.isNotEmpty()) segments.add(seg)
                    asr.reset(stream)
                }
                val nativeNow = Debug.getNativeHeapAllocatedSize()
                if (nativeNow > peakNativeBytes) peakNativeBytes = nativeNow
                offset = end
            }

            stream.acceptWaveform(FloatArray(TAIL_PADDING_SAMPLES), SAMPLE_RATE)
            stream.inputFinished()
            while (asr.isReady(stream)) {
                asr.decode(stream)
            }
            val tail = asr.getResult(stream).text.trim()
            if (tail.isNotEmpty()) segments.add(tail)
        } finally {
            stream.release()
        }

        val wallMs = maxOf(1L, System.currentTimeMillis() - wallStart)
        val cpuMs = maxOf(0L, Process.getElapsedCpuTime() - cpuStart)
        val audioDurationMs = maxOf(1f, (samples.size.toFloat() / SAMPLE_RATE) * 1000f)

        return OfflineDecodeMetrics(
            rawText = segments.joinToString(" "),
            wallMs = wallMs,
            cpuMs = cpuMs,
            rtf = wallMs / audioDurationMs,
            peakNativeRamMb = peakNativeBytes / (1024f * 1024f),
        )
    }

    fun stop() {
        running = false
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            worker?.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        noiseSuppressor?.release()
        noiseSuppressor = null
        recorder?.release()
        recorder = null
        worker = null
    }

    fun close() {
        stop()
        recognizer?.release()
        recognizer = null
    }
}
