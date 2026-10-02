package com.example.rag

import kotlinx.coroutines.CancellationException

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
) {
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
            if (config.filter && trace.finalHits.isEmpty()) {
                return Result.success(RagAnswer(mode, q, INSUFFICIENT_ANSWER, emptyList(), trace, insufficientContext = true))
            }
        }
        val hits = trace?.finalHits ?: emptyList()
        return generator.generate(RagPromptBuilder.systemPrompt(mode), RagPromptBuilder.userPrompt(mode, q, hits))
            .map { RagAnswer(mode, q, it, hits, trace) }
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
