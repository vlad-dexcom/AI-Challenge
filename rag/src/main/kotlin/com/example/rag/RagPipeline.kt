package com.example.rag

import kotlinx.coroutines.CancellationException

enum class RagMode { NO_RAG, RAG }

/** One answer plus the chunks that were put into the prompt (empty for [RagMode.NO_RAG]). */
data class RagAnswer(
    val mode: RagMode,
    val question: String,
    val answer: String,
    val hits: List<SearchHit>,
) {
    val sources: List<String> get() = hits.map { RagPromptBuilder.sourceLabel(it.chunk) }.distinct()
}

data class RagComparison(val question: String, val withoutRag: RagAnswer, val withRag: RagAnswer)

/** question -> retrieve chunks -> combine with the question -> LLM, in either mode. */
class RagPipeline(private val retriever: Retriever, private val generator: TextGenerator) {

    suspend fun ask(question: String, mode: RagMode): Result<RagAnswer> {
        val q = question.trim()
        require(q.isNotEmpty()) { "Question must not be blank" }
        val hits = if (mode == RagMode.RAG) {
            try {
                retriever.retrieve(q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(e)
            }
        } else emptyList()
        return generator.generate(RagPromptBuilder.systemPrompt(mode), RagPromptBuilder.userPrompt(mode, q, hits))
            .map { RagAnswer(mode, q, it, hits) }
    }

    suspend fun compare(question: String): Result<RagComparison> {
        val without = ask(question, RagMode.NO_RAG).getOrElse { return Result.failure(it) }
        val with = ask(question, RagMode.RAG).getOrElse { return Result.failure(it) }
        return Result.success(RagComparison(question.trim(), without, with))
    }
}
