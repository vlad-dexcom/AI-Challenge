package com.example.rag

/**
 * Wording variants of the RAG prompts. [DEFAULT] is the original text (used for the cloud model and everywhere a profile is not given);
 * [LOCAL] is tuned for small local models: query rewriting with examples that keeps the question's own terms, an answer contract that
 * answers the supported part of a partly covered question instead of refusing, an explicit answer language, and a shorter contract.
 * Each lever can be switched on its own (`rewrite`, `partial`, `lang`, `compact`) so their effect can be measured separately.
 */
data class PromptProfile(
    val rewriteExamples: Boolean = false,
    val partialAnswers: Boolean = false,
    val languageHint: Boolean = false,
    val compactContract: Boolean = false,
) {
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = PromptProfile()
        val LOCAL = PromptProfile(rewriteExamples = true, partialAnswers = true, languageHint = true, compactContract = true)

        /** `default`, `local`, or a comma list of levers: `rewrite,partial,lang,compact`. */
        fun parse(spec: String?): PromptProfile {
            val s = spec?.trim()?.lowercase().orEmpty()
            if (s.isEmpty() || s == "default") return DEFAULT
            if (s == "local") return LOCAL
            var p = DEFAULT
            for (lever in s.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                p = when (lever) {
                    "rewrite" -> p.copy(rewriteExamples = true)
                    "partial" -> p.copy(partialAnswers = true)
                    "lang" -> p.copy(languageHint = true)
                    "compact" -> p.copy(compactContract = true)
                    else -> throw IllegalArgumentException("Unknown prompt lever '$lever' (use default, local, or rewrite,partial,lang,compact)")
                }
            }
            return p
        }
    }
}
