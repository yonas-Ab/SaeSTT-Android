package com.sae.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

object ModelManager {
    private const val PREFS_NAME = "sae_stt_prefs"
    private const val KEY_SELECTED_MODEL = "selected_model_id"
    private const val KEY_RETAIN_ONLY_SELECTED = "retain_only_selected"

    fun appStorageRoot(context: Context): File =
        context.getExternalFilesDir(null) ?: File(context.filesDir, "external-fallback")

    fun modelDirectory(context: Context, spec: AsrModelSpec = getSelectedModel(context)): File =
        File(appStorageRoot(context), "models/${spec.repoName}")

    fun getSelectedModel(context: Context): AsrModelSpec {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AsrModelSpec.fromId(prefs.getString(KEY_SELECTED_MODEL, AsrModelSpec.ZIPFORMER_20M.id))
    }

    fun setSelectedModel(context: Context, spec: AsrModelSpec) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_MODEL, spec.id)
            .apply()
    }

    fun getRetainOnlySelected(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RETAIN_ONLY_SELECTED, true)

    fun setRetainOnlySelected(context: Context, retainOnly: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RETAIN_ONLY_SELECTED, retainOnly)
            .apply()
    }

    fun isInstalled(context: Context, spec: AsrModelSpec = getSelectedModel(context)): Boolean {
        val dir = modelDirectory(context, spec)
        return spec.files.all { item ->
            val file = File(dir, item.name)
            file.exists() && file.length() >= item.minimumBytes
        }
    }

    fun installedSizeBytes(context: Context, spec: AsrModelSpec = getSelectedModel(context)): Long {
        val dir = modelDirectory(context, spec)
        if (!dir.isDirectory) return 0L
        return spec.files.sumOf { File(dir, it.name).takeIf { f -> f.exists() }?.length() ?: 0L }
    }

    suspend fun deleteModel(context: Context, spec: AsrModelSpec) = withContext(Dispatchers.IO) {
        val dir = modelDirectory(context, spec)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    suspend fun deleteUnselectedModels(context: Context, keep: AsrModelSpec) = withContext(Dispatchers.IO) {
        AsrModelSpec.entries
            .filter { it != keep }
            .forEach { other ->
                val dir = modelDirectory(context, other)
                if (dir.exists()) dir.deleteRecursively()
            }
    }

    suspend fun download(
        context: Context,
        spec: AsrModelSpec = getSelectedModel(context),
        onProgress: (String, Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dir = modelDirectory(context, spec)
        check(dir.mkdirs() || dir.isDirectory) { "Cannot create model directory: $dir" }

        val totalExpectedBytes = spec.totalExpectedBytes
        var completedExpectedBytes = 0L

        spec.files.forEach { item ->
            currentCoroutineContext().ensureActive()
            val destination = File(dir, item.name)
            if (destination.exists() && destination.length() >= item.minimumBytes) {
                completedExpectedBytes += item.expectedBytes
                val pct = ((completedExpectedBytes * 100) / totalExpectedBytes).toInt().coerceIn(0, 99)
                onProgress("Verified ${item.name}", pct)
                return@forEach
            }

            val temp = File(dir, item.name + ".part")
            temp.delete()
            val connection = openFollowingRedirects(spec.baseUrl + item.name)
            try {
                val total = connection.contentLengthLong
                val itemEstimate = if (total > 0) total else item.expectedBytes
                var received = 0L
                var lastReportTime = 0L

                connection.inputStream.use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count

                            val now = System.currentTimeMillis()
                            if (now - lastReportTime >= 100 || (total > 0 && received == total)) {
                                lastReportTime = now
                                val fraction = (received.toDouble() / itemEstimate.toDouble()).coerceIn(0.0, 1.0)
                                val weightedBytes = completedExpectedBytes + (item.expectedBytes * fraction).toLong()
                                val overall = ((weightedBytes * 100) / totalExpectedBytes).toInt().coerceIn(0, 99)
                                val sizeLabel = if (total > 0) {
                                    "${formatBytes(received)} / ${formatBytes(total)}"
                                } else {
                                    formatBytes(received)
                                }
                                onProgress("Downloading ${item.name} ($sizeLabel)", overall)
                            }
                        }
                    }
                }
                if (total > 0) {
                    check(received == total) {
                        "Incomplete download for ${item.name}: $received of $total bytes"
                    }
                }
                check(temp.length() >= item.minimumBytes) {
                    "Downloaded file is unexpectedly small: ${item.name}"
                }
                if (destination.exists()) destination.delete()
                check(temp.renameTo(destination)) { "Could not finalize ${item.name}" }
            } finally {
                connection.disconnect()
                if (temp.exists()) temp.delete()
            }

            completedExpectedBytes += item.expectedBytes
            val pct = ((completedExpectedBytes * 100) / totalExpectedBytes).toInt().coerceIn(0, 99)
            onProgress("Downloaded ${item.name}", pct)
        }

        if (getRetainOnlySelected(context)) {
            deleteUnselectedModels(context, spec)
        }
        onProgress("Model ready", 100)
    }

    private fun openFollowingRedirects(url: String, maxRedirects: Int = 5): HttpURLConnection {
        var current = url
        repeat(maxRedirects + 1) {
            val connection = (URI(current).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SaeSTT-Android/0.2")
            }
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                check(!location.isNullOrBlank()) { "HTTP $code redirect missing Location for $current" }
                current = URI(current).resolve(location).toString()
            } else {
                check(code in 200..299) { "Download failed for $current: HTTP $code" }
                return connection
            }
        }
        error("Too many redirects downloading $url")
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
