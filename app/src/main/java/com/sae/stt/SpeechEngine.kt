package com.sae.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

class SpeechEngine(
    private val modelDir: File,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onAudioLevel: (Float) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 1_600 // 100 ms at 16 kHz
        private const val TAIL_PADDING_SAMPLES = 6_400 // 400 ms flush padding
    }

    @Volatile
    private var running = false
    private var recorder: AudioRecord? = null
    private var recognizer: OnlineRecognizer? = null
    private var worker: Thread? = null

    val isInitialized: Boolean
        get() = recognizer != null

    val isRunning: Boolean
        get() = running

    fun initialize() {
        if (recognizer != null) return
        val model = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                decoder = File(modelDir, "decoder-epoch-99-avg-1.int8.onnx").absolutePath,
                joiner = File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
            ),
            tokens = File(modelDir, "tokens.txt").absolutePath,
            numThreads = 2,
            provider = "cpu",
            modelType = "zipformer",
        )
        recognizer = OnlineRecognizer(
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = model,
                endpointConfig = EndpointConfig(),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
        )
    }

    @SuppressLint("MissingPermission")
    fun start() {
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

        try {
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone failed to enter recording state"
            }
        } catch (t: Throwable) {
            audio.release()
            throw t
        }

        recorder = audio
        running = true
        worker = thread(name = "SaeSTT-audio") { processAudio(audio) }
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

    private fun processAudio(audio: AudioRecord) {
        val asr = recognizer ?: return
        val stream = asr.createStream()
        val shortBuffer = ShortArray(CHUNK_SAMPLES)
        val fullFloatBuffer = FloatArray(CHUNK_SAMPLES)
        var lastPartial = ""

        try {
            while (running) {
                val count = audio.read(shortBuffer, 0, shortBuffer.size)
                if (count < 0) {
                    if (running) {
                        error("Microphone read error ($count)")
                    }
                    break
                }
                if (count == 0) continue

                val samples = if (count == fullFloatBuffer.size) fullFloatBuffer else FloatArray(count)
                var sumSquares = 0.0
                for (i in 0 until count) {
                    val sample = shortBuffer[i] / 32768.0f
                    samples[i] = sample
                    sumSquares += sample * sample
                }
                val rms = sqrt(sumSquares / count).toFloat()
                onAudioLevel((rms * 4.0f).coerceIn(0f, 1f))

                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (asr.isReady(stream)) {
                    asr.decode(stream)
                }

                val current = asr.getResult(stream).text.trim()
                if (current != lastPartial) {
                    lastPartial = current
                    onPartial(current)
                }

                if (asr.isEndpoint(stream)) {
                    if (current.isNotEmpty()) {
                        onFinal(current)
                    }
                    lastPartial = ""
                    asr.reset(stream)
                }
            }

            stream.acceptWaveform(FloatArray(TAIL_PADDING_SAMPLES), SAMPLE_RATE)
            stream.inputFinished()
            while (asr.isReady(stream)) {
                asr.decode(stream)
            }
            val remaining = asr.getResult(stream).text.trim()
            if (remaining.isNotEmpty()) {
                onFinal(remaining)
            }
        } catch (t: Throwable) {
            if (running) {
                running = false
                onError(t.message ?: t.javaClass.simpleName)
            }
        } finally {
            onAudioLevel(0f)
            stream.release()
        }
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
