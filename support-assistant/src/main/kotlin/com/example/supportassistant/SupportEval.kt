package com.example.supportassistant

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class EvalCase(
    val ticket: String,
    val question: String,
    /** Any of these documentation files is an acceptable basis for the answer. */
    val expectArticles: List<String>,
    val expectEscalation: Boolean,
)

/** One answer of one case in one mode. */
data class EvalOutcome(
    /** 1-based position of the first expected article among the retrieved files; null if it was not retrieved. */
    val rank: Int?,
    /** The answer cites the expected article and is not an "I don't know". */
    val cited: Boolean,
    val escalationCorrect: Boolean,
    val idk: Boolean,
    val error: String? = null,
)

data class EvalRow(val case: EvalCase, val withoutTicket: EvalOutcome, val withTicket: EvalOutcome)

/**
 * Day 33 evaluation: every question is asked twice, once as a plain FAQ question and once with the ticket context. The questions are
 * deliberately vague, as customers write them ("Why does it not work?"), so the difference is the value of the ticket data.
 */
object SupportEval {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(file: File): List<EvalCase> = json.decodeFromString(ListSerializer(EvalCase.serializer()), file.readText())

    suspend fun run(assistant: SupportAssistant, cases: List<EvalCase>, progress: (String) -> Unit = {}): List<EvalRow> = cases.map { c ->
        progress("${c.ticket}: ${c.question}")
        EvalRow(c, outcome(assistant, c, useTicket = false), outcome(assistant, c, useTicket = true))
    }

    private suspend fun outcome(assistant: SupportAssistant, c: EvalCase, useTicket: Boolean): EvalOutcome {
        val a = assistant.ask(c.ticket, c.question, useTicket).getOrElse { return EvalOutcome(null, false, false, false, it.message ?: "failed") }
        val cites = a.structured.sources.any { it.file in c.expectArticles }
        return EvalOutcome(
            rank = a.docsConsulted.indexOfFirst { it in c.expectArticles }.takeIf { it >= 0 }?.plus(1),
            cited = cites && !a.structured.idk,
            escalationCorrect = a.needsEscalation == c.expectEscalation,
            idk = a.structured.idk,
        )
    }

    fun markdown(rows: List<EvalRow>, model: String? = null): String = buildString {
        fun mark(b: Boolean) = if (b) "✅" else "❌"
        fun cell(o: EvalOutcome) = if (o.error != null) "error" else "#${o.rank ?: "-"} ${mark(o.cited)} ${mark(o.escalationCorrect)}"
        appendLine("# Support assistant eval" + (model?.let { " ($it)" } ?: ""))
        appendLine()
        appendLine("Each cell: rank of the expected article among the retrieved files (#1 = best) / cited in the answer / escalation decision correct.")
        appendLine()
        appendLine("| Ticket | Question | Expected article | FAQ only | With ticket |")
        appendLine("|---|---|---|---|---|")
        rows.forEach { r -> appendLine("| ${r.case.ticket} | ${r.case.question} | ${r.case.expectArticles.joinToString(" or ") { it.substringBefore('-') }} | ${cell(r.withoutTicket)} | ${cell(r.withTicket)} |") }
        appendLine()
        val n = rows.size
        fun count(pick: (EvalOutcome) -> Boolean, side: (EvalRow) -> EvalOutcome) = rows.count { pick(side(it)) }
        appendLine("| Metric | FAQ only | With ticket |")
        appendLine("|---|---|---|")
        appendLine("| Expected article ranked first | ${count({ it.rank == 1 }, EvalRow::withoutTicket)}/$n | ${count({ it.rank == 1 }, EvalRow::withTicket)}/$n |")
        fun meanRank(side: (EvalRow) -> EvalOutcome) = rows.mapNotNull { side(it).rank }.average().let { if (it.isNaN()) "-" else "%.1f".format(java.util.Locale.ROOT, it) }
        appendLine("| Mean rank of the expected article | ${meanRank(EvalRow::withoutTicket)} | ${meanRank(EvalRow::withTicket)} |")
        appendLine("| Expected article cited | ${count({ it.cited }, EvalRow::withoutTicket)}/$n | ${count({ it.cited }, EvalRow::withTicket)}/$n |")
        appendLine("| Escalation decision correct | ${count({ it.escalationCorrect }, EvalRow::withoutTicket)}/$n | ${count({ it.escalationCorrect }, EvalRow::withTicket)}/$n |")
        appendLine("| \"I don't know\" answers | ${count({ it.idk }, EvalRow::withoutTicket)}/$n | ${count({ it.idk }, EvalRow::withTicket)}/$n |")
    }.trimEnd() + "\n"
}
