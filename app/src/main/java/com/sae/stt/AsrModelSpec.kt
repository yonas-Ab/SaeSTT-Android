package com.sae.stt

enum class AsrModelSpec(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val repoName: String,
    val modelType: String,
    val files: List<ModelFileSpec>,
) {
    ZIPFORMER_20M(
        id = "zipformer-en-20m",
        displayName = "Zipformer 20M (Baseline)",
        subtitle = "LibriSpeech • ~46 MB • Fastest",
        repoName = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
        modelType = "zipformer",
        files = listOf(
            ModelFileSpec("encoder-epoch-99-avg-1.int8.onnx", 40_000_000L, 44_900_000L),
            ModelFileSpec("decoder-epoch-99-avg-1.int8.onnx", 450_000L, 539_000L),
            ModelFileSpec("joiner-epoch-99-avg-1.int8.onnx", 200_000L, 260_000L),
            ModelFileSpec("tokens.txt", 4_000L, 5_048L),
        ),
    ),
    ZIPFORMER_2023_06_21(
        id = "zipformer-en-2023-06-21",
        displayName = "Zipformer 2023-06-21 (v0.2 Candidate)",
        subtitle = "LibriSpeech + GigaSpeech • ~181 MB • Better conversational",
        repoName = "sherpa-onnx-streaming-zipformer-en-2023-06-21",
        modelType = "zipformer",
        files = listOf(
            ModelFileSpec("encoder-epoch-99-avg-1.int8.onnx", 150_000_000L, 179_000_000L),
            ModelFileSpec("decoder-epoch-99-avg-1.onnx", 900_000L, 1_200_000L),
            ModelFileSpec("joiner-epoch-99-avg-1.int8.onnx", 200_000L, 253_000L),
            ModelFileSpec("tokens.txt", 4_000L, 5_048L),
        ),
    );

    val baseUrl: String
        get() = "https://huggingface.co/csukuangfj/$repoName/resolve/main/"

    val totalExpectedBytes: Long
        get() = files.sumOf { it.expectedBytes }

    companion object {
        fun fromId(id: String?): AsrModelSpec =
            entries.firstOrNull { it.id == id } ?: ZIPFORMER_20M
    }
}

data class ModelFileSpec(
    val name: String,
    val minimumBytes: Long,
    val expectedBytes: Long,
)
