package com.example.rag

import kotlinx.serialization.Serializable
import com.example.core.llm.EmbeddingClient
import com.example.core.llm.TextGenerator

/** One row of the comparison: no RAG, or RAG with a particular combination of stages. */
data class ModeSpec(val id: String, val name: String, val rag: Boolean, val config: RagConfig, val llmRerank: Boolean = false)

object ModeSpecs {
    /** A no RAG, B plain RAG (Day 22), C +filter, D +rerank, E +rewrite, F all three; G = F with the LLM reranker, D2 = D with it. */
    fun all(before: Int, after: Int, threshold: Float): List<ModeSpec> {
        val base = RagConfig(topKBefore = before, topKAfter = after, threshold = threshold)
        return listOf(
            ModeSpec("A", "no RAG", false, base),
            ModeSpec("B", "plain RAG (top-$after)", true, RagConfig(after, after, threshold)),
            ModeSpec("C", "+ filter", true, base.copy(filter = true)),
            ModeSpec("D", "+ rerank (heuristic)", true, base.copy(rerank = true)),
            ModeSpec("D2", "+ rerank (LLM)", true, base.copy(rerank = true), llmRerank = true),
            ModeSpec("E", "+ query rewrite", true, RagConfig(after, after, threshold, rewrite = true)),
            ModeSpec("F", "filter + rerank + rewrite", true, base.copy(filter = true, rerank = true, rewrite = true)),
            ModeSpec("G", "F with LLM rerank", true, base.copy(filter = true, rerank = true, rewrite = true), llmRerank = true),
        )
    }
}

@Serializable
data class ModeItem(
    val mode: String,
    /** `eval` (the 30 retrieval questions) or `control` (the 10 Day 22 questions). */
    val set: String,
    val id: String,
    val category: String,
    val question: String,
    val searchQuery: String? = null,
    val rewriteFallback: String? = null,
    /** 1-based rank of the first final chunk from an expected source file, null = not retrieved. */
    val rank: Int? = null,
    val finalCount: Int = 0,
    val insufficient: Boolean = false,
    val answer: String? = null,
    val factsHit: Int = 0,
    val factsTotal: Int = 0,
    val admitsNoInfo: Boolean = false,
    val judge: String? = null,
    val judgeReason: String? = null,
    val llmCalls: Int = 0,
    val llmMs: Long = 0,
)

@Serializable
data class ModeSummary(
    val mode: String,
    val name: String,
    val hitAt1: Double?, val hitAt3: Double?, val hitAt5: Double?, val mrr: Double?,
    val factsHit: Int, val factsTotal: Int,
    val judgeCorrect: Int, val judgePartial: Int, val judgeWrong: Int, val judged: Int,
    val oocRejected: Int, val oocTotal: Int,
    val inCorpusRejected: Int, val inCorpusTotal: Int,
    val avgLlmCalls: Double, val avgLlmSeconds: Double,
)

@Serializable
data class ModesReport(val spec: String, val summaries: List<ModeSummary>, val items: List<ModeItem>)

class ModesEvaluator(
    private val index: VectorIndex,
    private val embedder: EmbeddingClient,
    private val generator: TextGenerator,
    private val usage: LlmUsage,
    private val judge: Judge?,
) {
    private fun pipeline(spec: ModeSpec): RagPipeline = RagPipeline(
        VectorRetriever(embedder, index, spec.config.topKAfter), generator,
        LlmQueryRewriter(generator), if (spec.llmRerank) LlmReranker(generator) else HeuristicReranker(),
        config = spec.config,
    )

    suspend fun run(
        specs: List<ModeSpec>, eval: List<EvalQuestion>, control: List<ControlQuestion>,
        onProgress: (String) -> Unit = {},
    ): ModesReport {
        val items = mutableListOf<ModeItem>()
        for (spec in specs) {
            val p = pipeline(spec)
            onProgress("mode ${spec.id}: ${spec.name}")
            for (q in eval) {
                if (q.inCorpus && spec.rag) {
                    val t = p.retrieve(q.question, spec.config)
                    items += ModeItem(
                        spec.id, "eval", q.id, q.type, q.question, t.searchQuery, t.rewriteFallback,
                        rank = rank(t.finalHits) { it.chunk.source == q.expectedSource }, finalCount = t.finalHits.size,
                    )
                } else if (q.inCorpus) {
                    items += ModeItem(spec.id, "eval", q.id, q.type, q.question)
                } else {
                    items += answered(spec, p, "eval", q.id, "out_of_corpus", q.question, emptyList(), true) { false }
                }
            }
            for (q in control) {
                val sources = q.expectedSources.map { it.source }.toSet()
                items += answered(spec, p, "control", q.id, q.category, q.question, q.mustContain, !q.inCorpus) { it.chunk.source in sources }
            }
        }
        return ModesReport(
            "modes A-G; ${eval.size} eval + ${control.size} control questions",
            specs.map { summarize(it, items.filter { i -> i.mode == it.id }) }, items,
        )
    }

    private suspend fun answered(
        spec: ModeSpec, p: RagPipeline, set: String, id: String, category: String, question: String,
        facts: List<List<String>>, ooc: Boolean, relevant: (SearchHit) -> Boolean,
    ): ModeItem {
        usage.reset()
        val a = p.ask(question, if (spec.rag) RagMode.RAG else RagMode.NO_RAG, spec.config).getOrThrow()
        val calls = usage.calls
        val ms = usage.millis
        val missing = facts.filter { g -> g.none { a.answer.contains(it, ignoreCase = true) } }
        val verdict = judge?.judge(question, a.answer, facts, ooc)?.getOrNull()
        return ModeItem(
            spec.id, set, id, category, question, a.trace?.searchQuery, a.trace?.rewriteFallback,
            rank = a.trace?.let { rank(it.finalHits, relevant) }, finalCount = a.hits.size, insufficient = a.insufficientContext,
            answer = a.answer, factsHit = facts.size - missing.size, factsTotal = facts.size,
            admitsNoInfo = a.insufficientContext || ControlScorer.score(ControlQuestion("", "", question), a.answer).admitsNoInfo,
            judge = verdict?.verdict?.name, judgeReason = verdict?.reason, llmCalls = calls, llmMs = ms,
        )
    }

    private fun rank(hits: List<SearchHit>, relevant: (SearchHit) -> Boolean) =
        hits.indexOfFirst(relevant).takeIf { it >= 0 }?.plus(1)

    companion object {
        fun summarize(spec: ModeSpec, items: List<ModeItem>): ModeSummary {
            val retr = items.filter { it.set == "eval" && it.category != "out_of_corpus" }
            fun hit(n: Int) = if (!spec.rag || retr.isEmpty()) null else retr.count { (it.rank ?: Int.MAX_VALUE) <= n }.toDouble() / retr.size
            val answered = items.filter { it.answer != null }
            val controlIn = items.filter { it.set == "control" && it.category != "out_of_corpus" }
            val ooc = items.filter { it.category == "out_of_corpus" }
            val judged = items.filter { it.set == "control" && it.judge != null }
            val inCorpusAll = retr + controlIn
            return ModeSummary(
                spec.id, spec.name, hit(1), hit(3), hit(5),
                if (!spec.rag || retr.isEmpty()) null else retr.sumOf { r -> r.rank?.let { 1.0 / it } ?: 0.0 } / retr.size,
                controlIn.sumOf { it.factsHit }, controlIn.sumOf { it.factsTotal },
                judged.count { it.judge == "CORRECT" }, judged.count { it.judge == "PARTIAL" }, judged.count { it.judge == "WRONG" }, judged.size,
                ooc.count { it.admitsNoInfo }, ooc.size,
                if (spec.rag) inCorpusAll.count { it.finalCount == 0 } else 0, inCorpusAll.size,
                if (answered.isEmpty()) 0.0 else answered.map { it.llmCalls }.average(),
                if (answered.isEmpty()) 0.0 else answered.map { it.llmMs / 1000.0 }.average(),
            )
        }

        private fun pct(x: Double?) = x?.let { "%.1f%%".format(100 * it) } ?: "n/a"

        fun renderMarkdown(r: ModesReport): String = buildString {
            appendLine("| mode | hit@1 | hit@3 | hit@5 | MRR | facts (control, 8 in-corpus) | judge ✅/⚠️/❌ (control 10, LLM-judged) | OOC refused | in-corpus wrongly refused | LLM calls / q | LLM latency / q |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
            for (s in r.summaries) appendLine(
                "| ${s.mode} ${s.name} | ${pct(s.hitAt1)} | ${pct(s.hitAt3)} | ${pct(s.hitAt5)} | ${s.mrr?.let { "%.3f".format(it) } ?: "n/a"} | " +
                    "${s.factsHit}/${s.factsTotal} | ${s.judgeCorrect}/${s.judgePartial}/${s.judgeWrong} | ${s.oocRejected}/${s.oocTotal} | " +
                    "${if (s.mode == "A") "n/a" else "${s.inCorpusRejected}/${s.inCorpusTotal}"} | %.2f | %.1f s |".format(s.avgLlmCalls, s.avgLlmSeconds)
            )
        }
    }
}
