package com.sae.stt

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WavStorage {
    fun recordingsDirectory(context: Context): File =
        File(ModelManager.appStorageRoot(context), "recordings").apply { mkdirs() }

    fun newRecordingFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(recordingsDirectory(context), "rec-$stamp.wav")
    }

    fun listRecordings(context: Context): List<File> =
        recordingsDirectory(context)
            .listFiles { f -> f.isFile && f.extension.equals("wav", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
}

class WavWriter(
    private val file: File,
    private val sampleRate: Int = 16_000,
) {
    private val out = BufferedOutputStream(FileOutputStream(file))
    private var dataBytes = 0L

    init {
        writeHeaderPlaceholder()
    }

    private fun writeHeaderPlaceholder() {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // PCM format
        header.putShort(1) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // byte rate
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(0)
        out.write(header.array())
    }

    fun writeShorts(samples: ShortArray, count: Int) {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
        }
        out.write(bytes)
        dataBytes += bytes.size
    }

    fun close() {
        out.flush()
        out.close()
        RandomAccessFile(file, "rw").use { raf ->
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            raf.seek(4)
            buf.putInt((36L + dataBytes).toInt())
            raf.write(buf.array())

            buf.clear()
            raf.seek(40)
            buf.putInt(dataBytes.toInt())
            raf.write(buf.array())
        }
    }
}

object WavReader {
    fun readMono16kFloat(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "Invalid WAV file: ${file.name}" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "Not a RIFF file" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a WAVE file" }

        var pos = 12
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            if (id == "data") {
                dataOffset = pos + 8
                dataSize = minOf(size, bytes.size - dataOffset)
                break
            }
            pos += 8 + size + (size and 1)
        }
        require(dataOffset >= 0 && dataSize > 0) { "WAV data chunk not found" }

        val sampleCount = dataSize / 2
        val out = FloatArray(sampleCount)
        var idx = dataOffset
        for (i in 0 until sampleCount) {
            val sample = buf.getShort(idx)
            out[i] = sample / 32768.0f
            idx += 2
        }
        return out
    }
}
