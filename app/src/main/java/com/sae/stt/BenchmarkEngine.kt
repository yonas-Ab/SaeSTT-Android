package com.sae.stt

data class BenchmarkPrompt(
    val id: String,
    val category: String,
    val text: String,
)

data class WerResult(
    val werPercent: Float,
    val substitutions: Int,
    val deletions: Int,
    val insertions: Int,
    val referenceWords: Int,
)

data class ModelBenchmarkResult(
    val model: AsrModelSpec,
    val rawTranscript: String,
    val correctedTranscript: String,
    val wer: WerResult?,
    val targetNamesMatched: List<String>,
    val wallMs: Long,
    val cpuMs: Long,
    val rtf: Float,
    val peakNativeRamMb: Float,
)

object BenchmarkSuite {
    val prompts: List<BenchmarkPrompt> = listOf(
        BenchmarkPrompt("1", "Sae / Wake", "Hey Sae, can you help me test this microphone?"),
        BenchmarkPrompt("2", "Sae / Brand", "Open YouTube and search for sherpa-onnx tutorials."),
        BenchmarkPrompt("3", "Sae / Project", "WaifuCompanion is running inside Termux on my phone."),
        BenchmarkPrompt("4", "Conversational", "I'm going to grab some coffee and come back in ten minutes."),
        BenchmarkPrompt("5", "Conversational", "Could you remind me why the build failed earlier today?"),
        BenchmarkPrompt("6", "Numbers", "The model size is forty five megabytes and uses two CPU threads."),
        BenchmarkPrompt("7", "Numbers", "Set a timer for twenty five minutes and thirty seconds."),
        BenchmarkPrompt("8", "Technical", "Offline speech recognition needs sixteen kilohertz mono audio."),
        BenchmarkPrompt("9", "Technical", "Android studio compiled the arm64 debug package without errors."),
        BenchmarkPrompt("10", "Longer", "When I pause for less than a second, the transcript should stay stable and wait before finalizing."),
    )

    fun computeWer(reference: String, hypothesis: String): WerResult {
        val ref = tokenize(reference)
        val hyp = tokenize(hypothesis)
        if (ref.isEmpty()) {
            return WerResult(if (hyp.isEmpty()) 0f else 100f, 0, 0, hyp.size, 0)
        }

        val dp = Array(ref.size + 1) { IntArray(hyp.size + 1) }
        for (i in 0..ref.size) dp[i][0] = i
        for (j in 0..hyp.size) dp[0][j] = j

        for (i in 1..ref.size) {
            for (j in 1..hyp.size) {
                val cost = if (ref[i - 1] == hyp[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,       // deletion
                    dp[i][j - 1] + 1,       // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        // Backtrack to count S / D / I
        var i = ref.size
        var j = hyp.size
        var sub = 0
        var del = 0
        var ins = 0
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + (if (ref[i - 1] == hyp[j - 1]) 0 else 1)) {
                if (ref[i - 1] != hyp[j - 1]) sub++
                i--; j--
            } else if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
                del++; i--
            } else {
                ins++; j--
            }
        }

        val wer = ((sub + del + ins).toFloat() / ref.size.toFloat()) * 100f
        return WerResult(wer, sub, del, ins, ref.size)
    }

    fun checkTargetNames(reference: String, transcript: String): List<String> {
        val targets = listOf("Sae", "YouTube", "sherpa-onnx", "WaifuCompanion", "Termux")
        return targets.filter { name ->
            reference.contains(name, ignoreCase = true) && transcript.contains(name)
        }
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9'\\-\\s]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
}
