package com.example.rag

import kotlinx.coroutines.CancellationException
import com.example.core.llm.CallPurpose
import com.example.core.llm.GenerationOptions
import com.example.core.llm.TextGenerator

enum class RagMode { NO_RAG, RAG }

/**
 * One answer plus the chunks that were put into the prompt (empty for [RagMode.NO_RAG]).
 * [trace] has the per-stage details; [insufficientContext] means no chunk passed the filter, so the model was not called.
 */
data class RagAnswer(
    val mode: RagMode,
    val question: String,
    val answer: String,
    val hits: List<SearchHit>,
    val trace: RetrievalTrace? = null,
    val insufficientContext: Boolean = false,
    /** Day 24: verified sources/quotes or an "I don't know"; null for [RagMode.NO_RAG] and when citations are off. */
    val structured: StructuredAnswer? = null,
) {
    val sources: List<String> get() = hits.map { RagPromptBuilder.sourceLabel(it.chunk) }.distinct()
}

data class RagComparison(val question: String, val withoutRag: RagAnswer, val withRag: RagAnswer)

/**
 * question -> [rewrite] -> retrieve a pool of `topKBefore` -> [filter by cosine threshold] -> [rerank, trim to `topKAfter`]
 * -> prompt with the ORIGINAL question -> LLM. [config] is the default; every call may pass its own.
 * `RagPipeline(retriever, generator)` keeps the Day 22 behaviour (retriever's own top-k, no stages).
 */
class RagPipeline(
    private val retriever: Retriever,
    private val generator: TextGenerator,
    private val rewriter: QueryRewriter? = null,
    private val reranker: Reranker = HeuristicReranker(),
    private val filterFactory: (RagConfig) -> ChunkFilter = { ThresholdFilter(it.threshold) },
    private val config: RagConfig = RagConfig.PLAIN,
    /** Day 24: RAG answers use the JSON contract with verified sources/quotes and an "I don't know" mode. Off keeps Day 22/23 free text. */
    private val citations: Boolean = false,
    /** Day 29: wording of the cited-answer prompts; [PromptProfile.DEFAULT] keeps the original text. */
    profile: PromptProfile = PromptProfile.DEFAULT,
) {
    private val cited = CitedAnswerer(generator, profile)

    /** Retrieval + stage 2 only (no answer generation); also used by the evals. */
    suspend fun retrieve(question: String, config: RagConfig = this.config, history: List<HistoryMessage> = emptyList()): RetrievalTrace {
        val q = question.trim()
        val legacy = config == RagConfig.PLAIN
        val outcome = if (config.rewrite && rewriter != null) rewriter.rewrite(q, history) else RewriteOutcome(q)
        val retrieved = if (legacy) retriever.retrieve(outcome.query) else retriever.retrieve(outcome.query, config.topKBefore)
        val filtered = if (config.filter) filterFactory(config).filter(outcome.query, retrieved) else retrieved
        val reranked = if (config.rerank) reranker.rerank(outcome.query, filtered, config.topKAfter)
        else KeepOrderReranker.rerank(outcome.query, filtered, if (legacy) filtered.size else config.topKAfter)
        return RetrievalTrace(q, outcome.query, outcome.fallbackReason, retrieved, filtered, reranked)
    }

    suspend fun ask(
        question: String,
        mode: RagMode,
        config: RagConfig = this.config,
        history: List<HistoryMessage> = emptyList(),
        /** Day 25: task memory + dialogue block for the cited answer prompt (null = single-shot, Day 24 prompts). */
        dialogContext: String? = null,
    ): Result<RagAnswer> {
        val q = question.trim()
        require(q.isNotEmpty()) { "Question must not be blank" }
        var trace: RetrievalTrace? = null
        if (mode == RagMode.RAG) {
            try {
                trace = retrieve(q, config, history)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(e)
            }
            if (citations) return askCited(q, trace, dialogContext)
            if (config.filter && trace.finalHits.isEmpty()) {
                return Result.success(RagAnswer(mode, q, INSUFFICIENT_ANSWER, emptyList(), trace, insufficientContext = true))
            }
        }
        val hits = trace?.finalHits ?: emptyList()
        return generator.generate(RagPromptBuilder.systemPrompt(mode), RagPromptBuilder.userPrompt(mode, q, hits), GenerationOptions(purpose = CallPurpose.ANSWER))
            .map { RagAnswer(mode, q, it, hits, trace) }
    }

    private suspend fun askCited(q: String, trace: RetrievalTrace, dialogContext: String?): Result<RagAnswer> {
        val hits = trace.finalHits
        if (hits.isEmpty()) {
            val idk = IdkResponder.build(q, IdkReason.BELOW_THRESHOLD, trace.retrieved)
            return Result.success(RagAnswer(RagMode.RAG, q, idk.answer, emptyList(), trace, insufficientContext = true, structured = idk))
        }
        return cited.answer(q, hits, trace.retrieved, dialogContext).map { RagAnswer(RagMode.RAG, q, it.answer, hits, trace, structured = it) }
    }

    suspend fun compare(question: String, config: RagConfig = this.config): Result<RagComparison> {
        val without = ask(question, RagMode.NO_RAG, config).getOrElse { return Result.failure(it) }
        val with = ask(question, RagMode.RAG, config).getOrElse { return Result.failure(it) }
        return Result.success(RagComparison(question.trim(), without, with))
    }

    companion object {
        /** Returned without calling the model when the filter rejects every candidate. Contains [RagPromptBuilder.NOT_FOUND_HINT]. */
        const val INSUFFICIENT_ANSWER =
            "Not enough information: the ${RagPromptBuilder.NOT_FOUND_HINT} this question (no sufficiently relevant excerpt was found)."
    }
}
