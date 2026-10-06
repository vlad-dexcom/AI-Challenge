package com.example.rag

import kotlinx.serialization.Serializable
import com.example.core.llm.EmbeddingClient
import com.example.core.llm.EmbeddingTaskType

/** A question with its candidate pool (top-[Sweeper.POOL_DEPTH] by cosine), embedded once and reused for the whole grid. */
class PooledQuestion(val question: EvalQuestion, val pool: List<SearchHit>)

@Serializable
data class SweepRow(
    val threshold: Float,
    val topKBefore: Int,
    val topKAfter: Int,
    val rerank: Boolean,
    val hitAt1: Double,
    val hitAt3: Double,
    val hitAt5: Double,
    val mrr: Double,
    /** Share of kept chunks that come from the expected source (in-corpus questions with at least one kept chunk). */
    val precision: Double,
    val avgKept: Double,
    /** Out-of-corpus questions for which nothing passed the filter (the desired outcome). */
    val outOfCorpusRejected: Double,
    /** In-corpus questions for which nothing passed the filter (the cost of the filter). */
    val inCorpusRejected: Double,
) {
    /** Single number used only to rank grid rows in the report: hit@3 on accepted in-corpus + OOC rejection, averaged. */
    val balanced: Double get() = (hitAt3 + outOfCorpusRejected) / 2
}

object Sweeper {
    const val POOL_DEPTH = 20
    val THRESHOLDS = listOf(0f, 0.5f, 0.55f, 0.6f, 0.65f, 0.7f)
    val BEFORE = listOf(5, 10, 15, 20)
    val AFTER = listOf(3, 4, 5)

    suspend fun prepare(index: VectorIndex, embedder: EmbeddingClient, questions: List<EvalQuestion>): List<PooledQuestion> {
        val vectors = embedder.embed(questions.map { it.question }, EmbeddingTaskType.RETRIEVAL_QUERY)
        return questions.zip(vectors) { q, v -> PooledQuestion(q, index.search(v, POOL_DEPTH)) }
    }

    /** The two stages applied to a pool exactly as [RagPipeline] does (filter on cosine first, then rerank/trim). */
    suspend fun apply(p: PooledQuestion, threshold: Float, before: Int, after: Int, reranker: Reranker?): List<SearchHit> {
        val kept = p.pool.take(before).filter { it.score >= threshold }
        return reranker?.rerank(p.question.question, kept, after)?.map { it.hit } ?: kept.take(after)
    }

    suspend fun evaluate(pooled: List<PooledQuestion>, threshold: Float, before: Int, after: Int, rerank: Boolean): SweepRow {
        val reranker = if (rerank) HeuristicReranker() else null
        val finals = pooled.map { it to apply(it, threshold, before, after, reranker) }
        val inCorpus = finals.filter { it.first.question.inCorpus }
        val ooc = finals.filter { !it.first.question.inCorpus }
        fun rank(p: Pair<PooledQuestion, List<SearchHit>>) =
            p.second.indexOfFirst { it.chunk.source == p.first.question.expectedSource }.takeIf { it >= 0 }?.plus(1)
        fun hit(n: Int) = if (inCorpus.isEmpty()) 0.0 else inCorpus.count { (rank(it) ?: Int.MAX_VALUE) <= n }.toDouble() / inCorpus.size
        val nonEmpty = inCorpus.filter { it.second.isNotEmpty() }
        return SweepRow(
            threshold, before, after, rerank,
            hit(1), hit(3), hit(5),
            if (inCorpus.isEmpty()) 0.0 else inCorpus.sumOf { r -> rank(r)?.let { 1.0 / it } ?: 0.0 } / inCorpus.size,
            if (nonEmpty.isEmpty()) 0.0 else nonEmpty.map { f -> f.second.count { it.chunk.source == f.first.question.expectedSource }.toDouble() / f.second.size }.average(),
            finals.map { it.second.size }.average(),
            if (ooc.isEmpty()) 0.0 else ooc.count { it.second.isEmpty() }.toDouble() / ooc.size,
            if (inCorpus.isEmpty()) 0.0 else inCorpus.count { it.second.isEmpty() }.toDouble() / inCorpus.size,
        )
    }

    suspend fun grid(pooled: List<PooledQuestion>): List<SweepRow> =
        buildList {
            for (t in THRESHOLDS) for (b in BEFORE) for (a in AFTER) {
                if (a > b) continue
                for (r in listOf(false, true)) add(evaluate(pooled, t, b, a, r))
            }
        }

    /** The separation the threshold has to find: top-1 cosine of in-corpus vs out-of-corpus questions (eval + control sets). */
    fun renderTop1(top1: List<Triple<String, Float, Boolean>>): String = buildString {
        val inC = top1.filter { it.third }.map { it.second }.sorted()
        appendLine("### Top-1 cosine similarity")
        appendLine("In-corpus (${inC.size} questions): min %.3f, p10 %.3f, median %.3f, max %.3f.".format(inC.first(), inC[inC.size / 10], inC[inC.size / 2], inC.last()))
        appendLine("Out-of-corpus: " + top1.filter { !it.third }.sortedBy { it.second }.joinToString(", ") { "${it.first} %.3f".format(it.second) })
        appendLine("Lowest in-corpus: " + top1.filter { it.third }.sortedBy { it.second }.take(3).joinToString(", ") { "${it.first} %.3f".format(it.second) })
    }

    private fun pct(x: Double) = "%.1f%%".format(100 * x)

    private fun table(rows: List<SweepRow>, vary: (SweepRow) -> String, header: String) = buildString {
        appendLine("| $header | rerank | hit@1 | hit@3 | hit@5 | MRR | precision | avg kept | OOC rejected | in-corpus wrongly rejected |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|")
        for (r in rows) appendLine(
            "| ${vary(r)} | ${if (r.rerank) "heuristic" else "no"} | ${pct(r.hitAt1)} | ${pct(r.hitAt3)} | ${pct(r.hitAt5)} | ${"%.3f".format(r.mrr)} | " +
                "${pct(r.precision)} | ${"%.2f".format(r.avgKept)} | ${pct(r.outOfCorpusRejected)} | ${pct(r.inCorpusRejected)} |"
        )
    }

    /** Three one-factor slices around the reference point plus the best grid rows. */
    fun renderMarkdown(rows: List<SweepRow>, refThreshold: Float, refBefore: Int, refAfter: Int, questionCount: Int): String = buildString {
        appendLine("Sweep over $questionCount questions (in-corpus and out-of-corpus), cosine similarity, structure index.")
        appendLine("Reference point: threshold=$refThreshold, topK-before=$refBefore, topK-after=$refAfter.")
        appendLine()
        appendLine("### Threshold (before=$refBefore, after=$refAfter)")
        appendLine(table(rows.filter { it.topKBefore == refBefore && it.topKAfter == refAfter }.sortedWith(compareBy({ it.threshold }, { it.rerank })), { "${it.threshold}" }, "threshold"))
        appendLine("### topK-before (threshold=$refThreshold, after=$refAfter)")
        appendLine(table(rows.filter { it.threshold == refThreshold && it.topKAfter == refAfter }.sortedWith(compareBy({ it.topKBefore }, { it.rerank })), { "${it.topKBefore}" }, "before"))
        appendLine("### topK-after (threshold=$refThreshold, before=$refBefore)")
        appendLine(table(rows.filter { it.threshold == refThreshold && it.topKBefore == refBefore }.sortedWith(compareBy({ it.topKAfter }, { it.rerank })), { "${it.topKAfter}" }, "after"))
        appendLine("### Top 10 grid rows by balanced score = (hit@3 + OOC rejected) / 2")
        appendLine(table(rows.sortedWith(compareByDescending<SweepRow> { it.balanced }.thenByDescending { it.mrr }).take(10), { "t=${it.threshold} b=${it.topKBefore} a=${it.topKAfter}" }, "config"))
    }
}
