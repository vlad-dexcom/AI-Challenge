package com.example.rag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class EvalQuestion(
    val id: String,
    val type: String,
    val question: String,
    val expectedSource: String? = null,
    /** Case-insensitive keyword that must appear in a heading of [expectedSource]. */
    val expectedSection: String? = null,
) {
    val inCorpus: Boolean get() = expectedSource != null
}

@Serializable
private data class EvalFile(val description: String = "", val questions: List<EvalQuestion>)

object EvalSet {
    val TYPES = setOf("direct", "paraphrase", "out_of_corpus")

    fun load(file: File): List<EvalQuestion> =
        Json { ignoreUnknownKeys = true }.decodeFromString(EvalFile.serializer(), file.readText()).questions

    /** Returns a list of human-readable problems (empty when the set is valid for [corpus]). */
    fun validate(questions: List<EvalQuestion>, corpus: List<Document>): List<String> {
        val problems = mutableListOf<String>()
        val bySource = corpus.associateBy { it.source }
        val ids = questions.map { it.id }
        if (ids.size != ids.toSet().size) problems += "duplicate question ids"
        for (q in questions) {
            if (q.question.isBlank()) problems += "${q.id}: empty question"
            if (q.type !in TYPES) problems += "${q.id}: unknown type '${q.type}'"
            if ((q.type == "out_of_corpus") == q.inCorpus) problems += "${q.id}: type '${q.type}' inconsistent with expectedSource"
            val src = q.expectedSource ?: continue
            val doc = bySource[src]
            if (doc == null) { problems += "${q.id}: expected source '$src' is not in the corpus"; continue }
            val kw = q.expectedSection
            if (kw != null && findHeadings(doc.text).none { it.text.contains(kw, ignoreCase = true) }) {
                problems += "${q.id}: no heading containing '$kw' in $src"
            }
        }
        return problems
    }
}

@Serializable
data class QuestionResult(
    val id: String,
    val type: String,
    val question: String,
    val expectedSource: String?,
    /** 1-based rank of the first chunk from the expected source within top-k, null = miss. */
    val rank: Int?,
    /** Same, but the chunk's section must also contain the expected heading keyword. */
    val sectionRank: Int?,
    val topScore: Float,
    val topChunkId: String,
)

@Serializable
data class EvalMetrics(
    val strategy: String,
    val embeddingModel: String,
    val k: Int,
    val inCorpusCount: Int,
    val hitAt1: Double,
    val hitAt3: Double,
    val hitAt5: Double,
    val sectionHitAt3: Double,
    val mrr: Double,
    val hitAt3Direct: Double,
    val hitAt3Paraphrase: Double,
    val avgTop1InCorpus: Double,
    val avgTop1OutOfCorpus: Double?,
    val minTop1InCorpus: Double,
    val maxTop1OutOfCorpus: Double?,
    val results: List<QuestionResult>,
) {
    val misses: List<QuestionResult> get() = results.filter { it.expectedSource != null && it.rank == null }
}

object Evaluator {
    /** [k] is raised to at least 5 so hit@5 is always defined. */
    suspend fun run(index: VectorIndex, embedder: EmbeddingClient, questions: List<EvalQuestion>, k: Int = 5): EvalMetrics {
        val depth = maxOf(k, 5)
        val vectors = embedder.embed(questions.map { it.question }, EmbeddingTaskType.RETRIEVAL_QUERY)
        val results = questions.zip(vectors) { q, v ->
            val hits = index.search(v, depth)
            QuestionResult(
                q.id, q.type, q.question, q.expectedSource,
                rank = q.expectedSource?.let { s -> hits.indexOfFirst { it.chunk.source == s }.takeIf { it >= 0 }?.plus(1) },
                sectionRank = q.expectedSource?.let { s ->
                    hits.indexOfFirst { it.chunk.source == s && it.chunk.section.contains(q.expectedSection ?: "", ignoreCase = true) }
                        .takeIf { it >= 0 }?.plus(1)
                },
                topScore = hits.firstOrNull()?.score ?: 0f,
                topChunkId = hits.firstOrNull()?.chunk?.chunkId ?: "-",
            )
        }
        return compute(index.meta, depth, results)
    }

    fun compute(meta: IndexMeta, k: Int, results: List<QuestionResult>): EvalMetrics {
        val inCorpus = results.filter { it.expectedSource != null }
        val ooc = results.filter { it.expectedSource == null }
        fun hit(rs: List<QuestionResult>, n: Int) = if (rs.isEmpty()) 0.0 else rs.count { (it.rank ?: Int.MAX_VALUE) <= n }.toDouble() / rs.size
        return EvalMetrics(
            strategy = meta.strategy.id, embeddingModel = meta.embeddingModel, k = k, inCorpusCount = inCorpus.size,
            hitAt1 = hit(inCorpus, 1), hitAt3 = hit(inCorpus, 3), hitAt5 = hit(inCorpus, 5),
            sectionHitAt3 = if (inCorpus.isEmpty()) 0.0 else inCorpus.count { (it.sectionRank ?: Int.MAX_VALUE) <= 3 }.toDouble() / inCorpus.size,
            mrr = if (inCorpus.isEmpty()) 0.0 else inCorpus.sumOf { r -> r.rank?.let { 1.0 / it } ?: 0.0 } / inCorpus.size,
            hitAt3Direct = hit(inCorpus.filter { it.type == "direct" }, 3),
            hitAt3Paraphrase = hit(inCorpus.filter { it.type == "paraphrase" }, 3),
            avgTop1InCorpus = inCorpus.map { it.topScore.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0,
            avgTop1OutOfCorpus = ooc.map { it.topScore.toDouble() }.average().takeIf { !it.isNaN() },
            minTop1InCorpus = inCorpus.minOfOrNull { it.topScore.toDouble() } ?: 0.0,
            maxTop1OutOfCorpus = ooc.maxOfOrNull { it.topScore.toDouble() },
            results = results,
        )
    }

    fun renderMarkdown(embedderLabel: String, metrics: List<EvalMetrics>): String = buildString {
        appendLine("# Retrieval eval report (`$embedderLabel`)")
        appendLine()
        val first = metrics.first()
        appendLine("Embedding model: `${first.embeddingModel}`. Questions: ${first.inCorpusCount} in-corpus + ${first.results.size - first.inCorpusCount} out-of-corpus. Chunk-level top-k cosine; a hit = a top-k chunk comes from the expected source file.")
        if (first.embeddingModel.startsWith("offline-")) {
            appendLine()
            appendLine("> Offline hashing embedder: lexical overlap only, so paraphrase questions are expected to do poorly. Not a semantic-quality result.")
        }
        appendLine()
        appendLine("| Metric | " + metrics.joinToString(" | ") { it.strategy } + " |")
        appendLine("|---|" + metrics.joinToString("") { "---|" })
        fun row(name: String, f: (EvalMetrics) -> String) = appendLine("| $name | " + metrics.joinToString(" | ") { f(it) } + " |")
        fun pct(x: Double) = "%.1f%%".format(100 * x)
        row("hit@1") { pct(it.hitAt1) }
        row("hit@3") { pct(it.hitAt3) }
        row("hit@5") { pct(it.hitAt5) }
        row("MRR") { "%.3f".format(it.mrr) }
        row("section hit@3 (heading keyword also matches)") { pct(it.sectionHitAt3) }
        row("hit@3 direct questions") { pct(it.hitAt3Direct) }
        row("hit@3 paraphrased questions") { pct(it.hitAt3Paraphrase) }
        row("avg top-1 score, in-corpus") { "%.3f".format(it.avgTop1InCorpus) }
        row("avg top-1 score, out-of-corpus") { it.avgTop1OutOfCorpus?.let { v -> "%.3f".format(v) } ?: "-" }
        row("min top-1 in-corpus / max top-1 out-of-corpus") { "%.3f / %s".format(it.minTop1InCorpus, it.maxTop1OutOfCorpus?.let { v -> "%.3f".format(v) } ?: "-") }
        for (m in metrics) {
            appendLine()
            appendLine("## ${m.strategy}: per-question results")
            appendLine()
            appendLine("| id | type | question | expected source | rank | top score | top chunk |")
            appendLine("|---|---|---|---|---|---|---|")
            m.results.forEach {
                appendLine("| ${it.id} | ${it.type} | ${it.question} | ${it.expectedSource?.let { s -> "`$s`" } ?: "-"} | ${it.rank ?: if (it.expectedSource == null) "n/a" else "miss"} | %.3f | `${it.topChunkId}` |".format(it.topScore))
            }
            appendLine()
            appendLine("Misses (expected source not in top-${m.k}): " + (m.misses.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.id } ?: "none"))
        }
    }
}
