package com.sae.stt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CorrectionRule(
    val id: String,
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean = true,
    val note: String = "",
)

object VocabularyManager {
    private const val CORRECTIONS_FILE = "corrections.json"
    private const val HOTWORDS_FILE = "hotwords.txt"

    fun vocabularyDirectory(context: Context): File =
        File(ModelManager.appStorageRoot(context), "vocabulary").apply { mkdirs() }

    fun correctionsFile(context: Context): File =
        File(vocabularyDirectory(context), CORRECTIONS_FILE)

    fun hotwordsFile(context: Context): File =
        File(vocabularyDirectory(context), HOTWORDS_FILE)

    fun defaultRules(): List<CorrectionRule> = listOf(
        CorrectionRule(
            id = "hey-sae",
            pattern = """\b(hey|hi|okay|ok|ask|tell)\s+say\b""",
            replacement = "$1 Sae",
            isRegex = true,
            note = "Context-aware: only replaces 'say' after wake/address verbs",
        ),
        CorrectionRule(
            id = "sae-command",
            pattern = """\bsay\s+(can you|please|what|how|open|start|help|check|listen)\b""",
            replacement = "Sae $1",
            isRegex = true,
            note = "Context-aware: replaces 'say' when followed by a command",
        ),
        CorrectionRule(
            id = "youtube",
            pattern = """\byou\s*tube\b""",
            replacement = "YouTube",
            isRegex = true,
            note = "Brand casing",
        ),
        CorrectionRule(
            id = "sherpa-onnx",
            pattern = """\bsherpa\s+(?:on\s*nx|onyx|on\s+x)\b""",
            replacement = "sherpa-onnx",
            isRegex = true,
            note = "Runtime name",
        ),
        CorrectionRule(
            id = "waifu-companion",
            pattern = """\bwaifu\s+companion\b""",
            replacement = "WaifuCompanion",
            isRegex = true,
            note = "Project name",
        ),
        CorrectionRule(
            id = "termux",
            pattern = """\bterm\s*(?:ux|ox|ex)\b""",
            replacement = "Termux",
            isRegex = true,
            note = "Terminal app",
        ),
    )

    fun ensureInitialized(context: Context) {
        val corrFile = correctionsFile(context)
        if (!corrFile.exists() || corrFile.length() == 0L) {
            saveRules(context, defaultRules())
        }
        val hwFile = hotwordsFile(context)
        if (!hwFile.exists()) {
            hwFile.writeText(
                """
                Sae :2.5
                YouTube :1.5
                Termux :2.0
                WaifuCompanion :2.5
                """.trimIndent() + "\n"
            )
        }
    }

    fun loadRules(context: Context): List<CorrectionRule> {
        ensureInitialized(context)
        return try {
            val array = JSONArray(correctionsFile(context).readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        CorrectionRule(
                            id = obj.optString("id", "rule-$i"),
                            pattern = obj.getString("pattern"),
                            replacement = obj.getString("replacement"),
                            isRegex = obj.optBoolean("isRegex", true),
                            note = obj.optString("note", ""),
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            defaultRules()
        }
    }

    fun saveRules(context: Context, rules: List<CorrectionRule>) {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject().apply {
                    put("id", rule.id)
                    put("pattern", rule.pattern)
                    put("replacement", rule.replacement)
                    put("isRegex", rule.isRegex)
                    put("note", rule.note)
                }
            )
        }
        correctionsFile(context).writeText(array.toString(2))
    }

    fun resetToDefaults(context: Context): List<CorrectionRule> {
        val defaults = defaultRules()
        saveRules(context, defaults)
        return defaults
    }

    fun applyCorrections(rawText: String, rules: List<CorrectionRule>): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""

        var result = normalizeAsrCase(trimmed)
        rules.forEach { rule ->
            result = try {
                val regex = if (rule.isRegex) {
                    Regex(rule.pattern, RegexOption.IGNORE_CASE)
                } else {
                    Regex("""\b${Regex.escape(rule.pattern)}\b""", RegexOption.IGNORE_CASE)
                }
                regex.replace(result, rule.replacement)
            } catch (_: Throwable) {
                result
            }
        }
        return capitalizeFirstLetter(result)
    }

    private fun normalizeAsrCase(text: String): String {
        // Zipformer outputs uppercase text; normalize to natural sentence case first.
        val lower = text.lowercase()
        val withPronounI = Regex("""\bi(?:'(m|ll|ve|d))?\b""").replace(lower) { m ->
            val suffix = m.groupValues.getOrNull(1).orEmpty()
            if (suffix.isEmpty()) "I" else "I'$suffix"
        }
        return capitalizeFirstLetter(withPronounI)
    }

    private fun capitalizeFirstLetter(text: String): String {
        if (text.isEmpty()) return text
        val first = text[0]
        return if (first.isLowerCase()) first.uppercaseChar() + text.substring(1) else text
    }
}
