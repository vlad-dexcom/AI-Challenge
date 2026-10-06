package com.example.rag.chat

private fun pct(n: Int, d: Int) = if (d == 0) "n/a" else "$n/$d"
private fun mark(ok: Boolean?) = when (ok) { true -> "✓"; false -> "✗"; null -> "–" }
private fun esc(s: String) = s.replace("|", "\\|").replace("\n", " ")

object ScenarioReport {
    private val CHECK_LABELS = linkedMapOf(
        "expected_mode" to "answer vs \"I don't know\" as expected",
        "sources_present" to "answers with sources + verified quotes",
        "idk_has_clarification" to "IDK with clarification question, no sources",
        "evidence_integrity" to "no fabricated sources/quotes (code-checked)",
        "language" to "reply in the user's language",
        "followup_resolved" to "follow-up resolved in the search query",
        "expected_source" to "expected source file cited",
        "answer_mentions" to "answer reflects the fixed constraints/plan",
        "answer_avoids" to "answer avoids what the user excluded",
        "goal_in_memory" to "goal still in task memory (memory state)",
        "constraint_in_memory" to "constraint stored in memory (memory state)",
        "clarification_in_memory" to "clarification stored in memory (memory state)",
        "memory_change_logged" to "correction applied and logged (memory state)",
        "idk_keeps_goal" to "IDK reply restates the goal",
    )

    fun checksOf(runs: List<ScenarioRun>, name: String): Pair<Int, Int> {
        val all = runs.flatMap { it.turns }.flatMap { it.checks }.filter { it.name == name }
        return all.count { it.ok } to all.size
    }

    fun render(report: ScenariosReport, scenarios: Map<String, Scenario>): String = buildString {
        val variants = MemoryMode.entries.filter { v -> report.runs.any { it.variant == v } }
        appendLine("# Day 25 scenarios report\n")
        appendLine("${report.spec}\n")
        appendLine("**How to read it.** Every check marked *code* is computed by the program (task-memory state, search query, answer text, sources, quotes). " +
            "\"judge\" rows are **LLM-judged** by the same model family that answers (same-model bias, noisy second opinion). \"manual\" is my own verdict after re-reading the answer. " +
            "Two scenarios, one run each, no repetitions: these numbers show a tendency, not a statistically meaningful difference.\n")
        appendLine("Variants: **FULL** = history + rolling summary + task memory; **HISTORY_ONLY** = same without task memory (ablation); **NONE** = every message answered single-shot (baseline). Memory-state checks exist only for FULL.\n")

        appendLine("## Summary (both scenarios together)\n")
        appendLine("| check | ${variants.joinToString(" | ") { it.name }} |")
        appendLine("|---|${variants.joinToString("") { "---|" }}")
        for ((name, label) in CHECK_LABELS) {
            val cells = variants.map { v -> checksOf(report.runs.filter { it.variant == v }, name).let { (ok, n) -> pct(ok, n) } }
            if (cells.all { it == "n/a" }) continue
            appendLine("| $label (code) | ${cells.joinToString(" | ")} |")
        }
        for ((label, f) in listOf<Pair<String, (JudgeVerdict) -> Boolean>>("goal kept (LLM-judged)" to { it.goalKept }, "constraints respected (LLM-judged)" to { it.constraintsRespected })) {
            val cells = variants.map { v -> report.runs.filter { it.variant == v }.flatMap { it.turns }.mapNotNull { it.judge }.let { js -> pct(js.count(f), js.size) } }
            appendLine("| $label | ${cells.joinToString(" | ")} |")
        }
        val manual = variants.map { v -> report.runs.filter { it.variant == v }.flatMap { it.turns }.mapNotNull { it.manual }.let { ms -> if (ms.isEmpty()) "–" else pct(ms.count { it.verdict == "ok" }, ms.size) } }
        appendLine("| manual verdict ok (author) | ${manual.joinToString(" | ")} |")
        val cost = variants.map { v ->
            val ts = report.runs.filter { it.variant == v }.flatMap { it.turns }.filter { it.error == null }
            if (ts.isEmpty()) "–" else "%.1f calls, %.1f s".format(ts.sumOf { it.llmCalls }.toDouble() / ts.size, ts.sumOf { it.llmMs } / 1000.0 / ts.size)
        }
        appendLine("| LLM calls and LLM time per turn (answer side) | ${cost.joinToString(" | ")} |")
        appendLine("| turns with errors | ${variants.joinToString(" | ") { v -> report.runs.filter { it.variant == v }.sumOf { r -> r.turns.count { it.error != null } }.toString() }} |")
        appendLine()

        for (sc in report.runs.map { it.scenarioId }.distinct()) {
            val scenario = scenarios[sc]
            appendLine("## Scenario `$sc`${scenario?.let { " - ${it.title}" } ?: ""}\n")
            scenario?.let { appendLine("Reference goal: *${it.goal}*\n") }
            val full = report.runs.firstOrNull { it.scenarioId == sc && it.variant == MemoryMode.FULL }
            if (full != null) {
                appendLine("### FULL: per-turn table\n")
                appendLine("| # | user message | note | search query (rewrite) | sources | quotes ✓/total | IDK | calls / s | failed code checks | goal kept (judge) | constraints (judge) | manual |")
                appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
                for (t in full.turns) {
                    val failed = t.checks.filter { !it.ok }.joinToString(", ") { it.name }.ifEmpty { "none" }
                    appendLine(
                        "| ${t.turn} | ${esc(t.user)} | ${esc(t.note)} | ${esc(t.searchQuery ?: "–")}${if (t.rewriteFallback != null) " (fallback: ${esc(t.rewriteFallback)})" else ""} | ${t.sources.size} | " +
                            "${if (t.idk) "–" else "${t.quotesVerified}/${t.quotesTotal}"} | ${if (t.idk) "yes (${t.idkReason})" else "no"} | ${t.llmCalls} / ${"%.1f".format(t.llmMs / 1000.0)} | $failed | " +
                            "${mark(t.judge?.goalKept)} | ${mark(t.judge?.constraintsRespected)} | ${t.manual?.verdict ?: "–"} |"
                    )
                }
                appendLine()
                appendLine("Final task memory (FULL):\n")
                full.finalMemory?.let { m ->
                    appendLine("- goal: ${m.goal}")
                    m.constraints.forEach { (k, v) -> appendLine("- constraint `$k`: $v") }
                    m.clarifications.forEach { appendLine("- clarified (turn ${it.turn}): ${it.text}") }
                    m.openQuestions.forEach { appendLine("- open question: $it") }
                    m.changes.filter { it.kind == "correct" || it.field == "goal" }.forEach { appendLine("- change (turn ${it.turn}, ${it.source}): ${it.field} ${it.key ?: ""} `${it.old}` -> `${it.new}`") }
                }
                appendLine()
            }
            val runs = report.runs.filter { it.scenarioId == sc }
            appendLine("### Ablation: failed code checks per turn\n")
            appendLine("| # | ${runs.joinToString(" | ") { it.variant.name }} |")
            appendLine("|---|${runs.joinToString("") { "---|" }}")
            val n = runs.maxOf { it.turns.size }
            for (i in 0 until n) {
                appendLine("| ${i + 1} | " + runs.joinToString(" | ") { r ->
                    r.turns.getOrNull(i)?.let { t -> if (t.error != null) "error" else t.checks.filter { !it.ok }.joinToString(", ") { c -> c.name }.ifEmpty { "–" } } ?: "" } + " |")
            }
            appendLine()
            if (full != null) {
                appendLine("### FULL: answers\n")
                for (t in full.turns) {
                    appendLine("**#${t.turn} ${t.user}**\n")
                    appendLine((t.error?.let { "ERROR: $it" } ?: t.answer).trim().prependIndent("> ") + "\n")
                    if (t.sources.isNotEmpty()) appendLine("Sources: ${t.sources.joinToString("; ")}\n")
                    t.checks.filter { !it.ok }.forEach { appendLine("- ✗ ${it.name}: ${it.detail}") }
                    t.memoryDiff.takeIf { it.isNotEmpty() }?.let { d -> appendLine("- memory diff: " + d.joinToString("; ") { "${it.kind} ${it.field}${it.key?.let { k -> " $k" } ?: ""}: ${it.old ?: "∅"} → ${it.new ?: "∅"}" }) }
                    t.judge?.let { appendLine("- judge (LLM): goal kept ${mark(it.goalKept)}, constraints ${mark(it.constraintsRespected)} - ${it.reason}") }
                    t.manual?.let { appendLine("- manual: **${it.verdict}** - ${it.note}") }
                    appendLine()
                }
            }
        }
    }
}
