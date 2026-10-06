package com.example.rag

import kotlin.math.max
import com.example.core.llm.GenerationOptions
import com.example.core.llm.TextGenerator

/** Chat turn for [QueryRewriter]; empty in single-shot mode (Day 25 will pass the conversation). */
data class HistoryMessage(val role: String, val text: String)

/** Settings of the two-stage retrieval. [filter]/[rerank]/[rewrite] switch the stages; with all off the pipeline behaves like Day 22. */
data class RagConfig(
    /** Size of the candidate pool taken from the vector index. */
    val topKBefore: Int = 10,
    /** Chunks that reach the prompt. */
    val topKAfter: Int = 4,
    /** Minimum cosine similarity to keep a chunk (applied before reranking). */
    val threshold: Float = 0.65f,
    val filter: Boolean = false,
    val rerank: Boolean = false,
    val rewrite: Boolean = false,
) {
    init {
        require(topKBefore >= 1 && topKAfter >= 1) { "topK must be positive" }
        require(topKAfter <= topKBefore) { "topKAfter must not exceed topKBefore" }
    }

    companion object {
        /** Defaults of the staged pipeline (chosen from the Day 23 sweep, see docs/day23-reranking.md). */
        val DEFAULT = RagConfig(filter = true, rerank = true)

        /** Day 22 behaviour: top-4 by cosine, no second stage. */
        val PLAIN = RagConfig(topKBefore = 4, topKAfter = 4)
    }
}

/** Stage 2a: drops irrelevant candidates. Scores of the survivors stay cosine similarities. */
fun interface ChunkFilter {
    suspend fun filter(query: String, hits: List<SearchHit>): List<SearchHit>
}

class ThresholdFilter(private val minScore: Float) : ChunkFilter {
    override suspend fun filter(query: String, hits: List<SearchHit>): List<SearchHit> = hits.filter { it.score >= minScore }
}

/** A chunk with its reranker score; the scale depends on the reranker and is NOT comparable with cosine similarity. */
data class RerankedHit(val hit: SearchHit, val rerankScore: Double)

/** Stage 2b: reorders the (already filtered) candidates and trims them to [topK]. */
fun interface Reranker {
    suspend fun rerank(query: String, hits: List<SearchHit>, topK: Int): List<RerankedHit>
}

/** What each stage produced for one question, for debugging, the UI and the evals. */
data class RetrievalTrace(
    val originalQuery: String,
    val searchQuery: String,
    val rewriteFallback: String?,
    val retrieved: List<SearchHit>,
    val filtered: List<SearchHit>,
    val reranked: List<RerankedHit>,
) {
    /** Chunks that go into the prompt, best first. */
    val finalHits: List<SearchHit> get() = reranked.map { it.hit }
}

/** Search query plus the reason the rewrite was discarded (then [query] is the original question). */
data class RewriteOutcome(val query: String, val fallbackReason: String? = null)

/** Rewrites a question into a standalone search query. [history] is empty in single-shot mode. */
fun interface QueryRewriter {
    suspend fun rewrite(question: String, history: List<HistoryMessage>): RewriteOutcome
}

/** Keeps the incoming order (cosine) and only trims. Used when reranking is off. */
object KeepOrderReranker : Reranker {
    override suspend fun rerank(query: String, hits: List<SearchHit>, topK: Int) =
        hits.take(topK).map { RerankedHit(it, it.score.toDouble()) }
}

internal object Text {
    private val STOP = setOf(
        "the", "and", "for", "are", "that", "this", "with", "you", "your", "can", "not", "but", "from", "have", "has",
        "was", "were", "will", "how", "what", "when", "which", "its", "into", "than", "then", "they", "them", "their",
        "more", "most", "should", "does", "any", "why", "who", "where", "about", "much", "many", "get", "use", "using",
        "need", "good", "best", "i", "do", "is", "it", "to", "of", "in", "on", "my", "me", "a", "an", "be", "or",
    )

    /** Lower-case alphanumeric tokens without stop words and with a trivial plural stem. */
    fun terms(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP }
            .map { if (it.length > 4 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }
            .toSet()
}

/**
 * Cheap lexical reranker: cosine + keyword overlap with the text + overlap with the heading/title,
 * then greedy MMR-style selection that penalises near-duplicates of chunks already selected.
 * Scores are `cosine + 0.15*textOverlap + 0.15*headingOverlap - 0.3*maxSimilarityToSelected` (not cosine).
 */
class HeuristicReranker(
    private val textWeight: Double = 0.15,
    private val headingWeight: Double = 0.15,
    private val redundancyWeight: Double = 0.3,
) : Reranker {
    override suspend fun rerank(query: String, hits: List<SearchHit>, topK: Int): List<RerankedHit> {
        if (hits.isEmpty()) return emptyList()
        val q = Text.terms(query)
        val prepared = hits.map { h ->
            val text = Text.terms(h.chunk.text)
            val heading = Text.terms(h.chunk.section + " " + h.chunk.title.replace(Regex("\\.md$"), "").replace('-', ' '))
            val relevance = h.score + if (q.isEmpty()) 0.0 else {
                textWeight * q.count { it in text } / q.size + headingWeight * q.count { it in heading } / q.size
            }
            Triple(h, relevance, text)
        }
        val remaining = prepared.toMutableList()
        val picked = mutableListOf<Triple<SearchHit, Double, Set<String>>>()
        val out = mutableListOf<RerankedHit>()
        fun penalty(c: Triple<SearchHit, Double, Set<String>>): Double =
            redundancyWeight * (picked.maxOfOrNull { jaccard(it.third, c.third) } ?: 0.0) +
                if (picked.any { it.first.chunk.source == c.first.chunk.source && it.first.chunk.section == c.first.chunk.section }) redundancyWeight else 0.0
        while (remaining.isNotEmpty() && out.size < topK) {
            val best = remaining.maxByOrNull { it.second - penalty(it) }!!
            out += RerankedHit(best.first, best.second - penalty(best))
            picked += best
            remaining -= best
        }
        return out
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size
    }
}

/**
 * LLM reranker: one extra model call scores each candidate 0-10 for relevance to the question.
 * Any failure or unparsable reply falls back to the incoming (cosine) order, so it never makes retrieval fail.
 */
class LlmReranker(private val generator: TextGenerator, private val maxChunkChars: Int = 600) : Reranker {
    override suspend fun rerank(query: String, hits: List<SearchHit>, topK: Int): List<RerankedHit> {
        if (hits.size <= 1) return KeepOrderReranker.rerank(query, hits, topK)
        val prompt = buildString {
            appendLine("Question: $query")
            appendLine()
            hits.forEachIndexed { i, h -> appendLine("[${i + 1}] ${RagPromptBuilder.sourceLabel(h.chunk)}\n${h.chunk.text.trim().take(maxChunkChars)}\n") }
        }
        val reply = generator.generate(SYSTEM, prompt, GenerationOptions(temperature = 0.0)).getOrNull()
            ?: return KeepOrderReranker.rerank(query, hits, topK)
        val scores = parse(reply, hits.size)
        if (scores.isEmpty()) return KeepOrderReranker.rerank(query, hits, topK)
        return hits.mapIndexed { i, h -> RerankedHit(h, (scores[i + 1] ?: 0.0) + h.score / 100.0) }
            .sortedByDescending { it.rerankScore }
            .take(topK)
    }

    companion object {
        const val SYSTEM = "You rate how useful each numbered excerpt is for answering the question. " +
            "Reply with one line per excerpt in the form `<number>: <score>` where score is an integer 0-10 " +
            "(0 = unrelated, 10 = directly answers). No other text."

        fun parse(reply: String, n: Int): Map<Int, Double> =
            Regex("\\[?(\\d+)]?\\s*[:=-]\\s*(\\d+(?:\\.\\d+)?)").findAll(reply)
                .mapNotNull { m -> m.groupValues[1].toInt().takeIf { it in 1..n }?.let { it to m.groupValues[2].toDouble().coerceIn(0.0, 10.0) } }
                .toMap()
    }
}

/**
 * LLM query rewriter: a standalone, keyword-rich English search query (a Russian question is translated).
 * The final answer is still generated from the original question. Falls back to the original on any
 * failure, an empty/multi-line/over-long reply, or drift (a Latin-script question and a query sharing none of its terms).
 */
class LlmQueryRewriter(private val generator: TextGenerator) : QueryRewriter {
    override suspend fun rewrite(question: String, history: List<HistoryMessage>): RewriteOutcome {
        val prompt = buildString {
            if (history.isNotEmpty()) {
                appendLine("Conversation so far:")
                history.takeLast(6).forEach { appendLine("${it.role}: ${it.text.take(300)}") }
                appendLine()
            }
            append("Question: $question")
        }
        val reply = generator.generate(SYSTEM, prompt, GenerationOptions(temperature = 0.0)).getOrElse {
            return RewriteOutcome(question, "rewrite call failed: ${it.message?.take(80)}")
        }
        return validate(question, reply)
    }

    companion object {
        const val SYSTEM = "You rewrite a user question into a standalone search query for a knowledge base of English " +
            "articles about strength training, nutrition and recovery. Resolve references using the conversation if given, " +
            "translate to English if needed, and use the specific keywords and synonyms that the articles would contain. " +
            "Reply with the query only: one line, at most 25 words, no quotes, no explanation."

        /** [checkDrift] = false for follow-ups: a resolved query legitimately shares no words with "what about my knee?". */
        fun validate(question: String, reply: String, checkDrift: Boolean = true): RewriteOutcome {
            val q = reply.trim().removeSurrounding("\"").trim().removePrefix("Query:").removePrefix("Search query:").trim()
            if (q.isEmpty()) return RewriteOutcome(question, "empty rewrite")
            if (q.contains('\n')) return RewriteOutcome(question, "multi-line rewrite")
            if (q.length > max(200, question.length * 3)) return RewriteOutcome(question, "rewrite too long")
            val latinQuestion = question.none { it in 'А'..'я' || it == 'ё' || it == 'Ё' }
            if (latinQuestion) {
                if (!checkDrift) return RewriteOutcome(q)
                val a = Text.terms(question)
                if (a.isNotEmpty() && a.intersect(Text.terms(q)).isEmpty()) return RewriteOutcome(question, "drift: no shared terms")
            } else if (q.any { it in 'А'..'я' }) {
                return RewriteOutcome(question, "rewrite not translated")
            }
            return RewriteOutcome(q)
        }
    }
}
