package com.example.reviewer

/** The documentation excerpt a convention finding rests on; [quote] was verified to occur verbatim in that excerpt. */
data class RuleRef(val source: String, val section: String, val quote: String)

data class Finding(
    val file: String,
    /** New-file line, verified to be part of the diff; null for findings about the file as a whole. */
    val line: Int?,
    val title: String,
    val explanation: String,
    val confidence: Int? = null,
    val rule: RuleRef? = null,
)

data class SkippedFile(val path: String, val reason: String)

data class ReviewReport(
    val base: String,
    val head: String,
    val summary: String,
    val bugs: List<Finding>,
    val architecture: List<Finding>,
    val recommendations: List<Finding>,
    val reviewedFiles: List<String>,
    val skipped: List<SkippedFile>,
    /** Findings the model produced that failed validation (bad line, unverifiable rule quote, low confidence, unknown file). */
    val dropped: List<String> = emptyList(),
    val docsConsulted: List<String> = emptyList(),
    val codeConsulted: List<String> = emptyList(),
    val model: String? = null,
) {
    val findingCount: Int get() = bugs.size + architecture.size + recommendations.size

    fun toMarkdown(lang: String = "en"): String = ReviewMarkdown.render(this, lang)

    companion object {
        /** Hidden marker the GitHub Action looks for, so one comment per pull request is updated instead of a new one per push. */
        const val MARKER = "<!-- ai-review -->"
    }
}

private class Labels(val title: String, val summary: String, val bugs: String, val architecture: String, val recommendations: String, val none: String,
    val rule: String, val confidence: String, val reviewed: String, val skipped: String, val consulted: String, val dropped: String, val footer: String, val nothing: String)

private val EN = Labels(
    "AI review", "Summary", "Potential bugs", "Architecture and conventions", "Recommendations", "Nothing found.",
    "Rule", "confidence", "Reviewed", "Not reviewed", "Context consulted", "findings were dropped because they could not be verified against the diff or the docs",
    "Automated review by an AI model: it can be wrong or miss things. It complements human review and the build, it does not replace them.",
    "No reviewable changes.",
)

private val RU = Labels(
    "AI-ревью", "Кратко", "Потенциальные баги", "Архитектура и конвенции", "Рекомендации", "Ничего не найдено.",
    "Правило", "уверенность", "Проверено", "Не проверено", "Использованный контекст", "замечаний отброшено: их не удалось подтвердить по diff или документации",
    "Автоматическое ревью модели: оно может ошибаться и что-то пропускать. Оно дополняет ревью человеком и сборку, а не заменяет их.",
    "Нет изменений для ревью.",
)

object ReviewMarkdown {
    fun render(r: ReviewReport, lang: String): String {
        val l = if (lang == "ru") RU else EN
        return buildString {
            appendLine(ReviewReport.MARKER)
            appendLine("## ${l.title}")
            appendLine()
            if (r.reviewedFiles.isEmpty()) {
                appendLine(l.nothing)
            } else {
                if (r.summary.isNotBlank()) { appendLine("**${l.summary}.** ${r.summary.trim()}"); appendLine() }
                section(l.bugs, "🐞", r.bugs, l)
                section(l.architecture, "🏗", r.architecture, l)
                section(l.recommendations, "💡", r.recommendations, l)
            }
            appendLine("<sub>${l.reviewed}: ${r.reviewedFiles.size} · ${r.base}...${r.head}" + (r.model?.let { " · $it" } ?: "") +
                (if (r.dropped.isNotEmpty()) " · ${r.dropped.size} ${l.dropped}" else "") + "</sub>")
            appendLine()
            if (r.skipped.isNotEmpty()) {
                appendLine("<details><summary>${l.skipped} (${r.skipped.size})</summary>\n")
                r.skipped.forEach { appendLine("- `${it.path}`: ${it.reason}") }
                appendLine("\n</details>\n")
            }
            val consulted = r.docsConsulted + r.codeConsulted
            if (consulted.isNotEmpty()) {
                appendLine("<details><summary>${l.consulted} (${consulted.size})</summary>\n")
                consulted.forEach { appendLine("- `$it`") }
                appendLine("\n</details>\n")
            }
            appendLine("<sub>${l.footer}</sub>")
        }.trimEnd() + "\n"
    }

    private fun StringBuilder.section(title: String, icon: String, findings: List<Finding>, l: Labels) {
        appendLine("### $icon $title")
        appendLine()
        if (findings.isEmpty()) { appendLine("_${l.none}_"); appendLine(); return }
        findings.forEach { f ->
            val where = if (f.line != null) "`${f.file}:${f.line}`" else "`${f.file}`"
            val conf = f.confidence?.let { " (${l.confidence} $it/10)" }.orEmpty()
            appendLine("- **${f.title.trim()}**: $where$conf")
            appendLine("  ${f.explanation.trim().replace("\n", "\n  ")}")
            f.rule?.let { appendLine("  > ${l.rule} (`${it.source}${if (it.section.isNotBlank()) " > ${it.section}" else ""}`): ${it.quote.trim().replace("\n", " ")}") }
        }
        appendLine()
    }
}
