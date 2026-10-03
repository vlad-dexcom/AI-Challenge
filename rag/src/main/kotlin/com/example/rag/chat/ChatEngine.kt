package com.example.rag.chat

import com.example.rag.ChatChunk
import com.example.rag.ChatSource
import com.example.rag.ChatStageHit
import com.example.rag.ChatTrace
import com.example.rag.HeuristicReranker
import com.example.rag.IdkResponder
import com.example.rag.LlmReranker
import com.example.rag.LlmUsage
import com.example.rag.RagMode
import com.example.rag.RagPipeline
import com.example.rag.RagPromptBuilder
import com.example.rag.Reranker
import com.example.rag.Retriever
import com.example.rag.RetrievalTrace
import com.example.rag.SearchHit
import com.example.rag.StructuredAnswer
import com.example.rag.TextGenerator
import kotlinx.coroutines.CancellationException

/**
 * One chat turn (Day 25):
 * 1. [MemoryExtractor] proposes a patch for the user message -> [TaskMemory.merge] (failure = memory unchanged);
 * 2. [ContextualRewriter] builds the search query from question + memory + summary + recent turns;
 * 3. retrieve -> threshold filter -> rerank -> cited answer (Day 24 contract) with the dialogue block in the prompt;
 * 4. an "I don't know" keeps the goal in view and records the open question; sources/closest topics are always attached;
 * 5. the verbatim window slides; a full batch of evicted turns is folded into the rolling summary.
 * The session is only changed (returned as a new value) when the answer succeeded, so a failed call can simply be retried.
 * [generator] should be wrapped in a counting generator that reports into [usage].
 */
class ChatEngine(
    private val retriever: Retriever,
    private val generator: TextGenerator,
    private val usage: LlmUsage = LlmUsage(),
    private val reranker: Reranker? = null,
    private val clock: () -> Long = System::nanoTime,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val extractor = MemoryExtractor(generator)
    private val summarizer = Summarizer(generator)

    suspend fun send(session: ChatSession, text: String, options: ChatOptions = ChatOptions()): Result<ChatSession> {
        val question = text.trim()
        require(question.isNotEmpty()) { "Message must not be blank" }
        val start = clock()
        val calls0 = usage.calls
        val ms0 = usage.millis
        val turn = session.turnCount + 1
        val full = options.memoryMode == MemoryMode.FULL
        val history = HistoryBudget.verbatim(session, options)
        val summary = if (options.memoryMode == MemoryMode.NONE) "" else session.summary

        var memory = session.memory
        val diff = mutableListOf<MemoryChange>()
        val notes = mutableListOf<String>()
        try {
            if (full) {
                val outcome = extractor.extract(memory, history, question)
                if (outcome.patch == null) notes += "memory unchanged: ${outcome.note}"
                else memory.merge(outcome.patch, turn).also { memory = it.memory; diff += it.diff; notes += it.notes }
            }

            val memoryForPrompt = if (full) memory else null
            val cfg = options.ragConfig()
            val pipeline = RagPipeline(
                retriever, generator, ContextualRewriter(generator, memoryForPrompt, summary),
                reranker ?: if (options.llmRerank) LlmReranker(generator) else HeuristicReranker(),
                config = cfg, citations = true,
            )
            val answer = pipeline.ask(question, RagMode.RAG, cfg, history, HistoryBudget.dialogContext(memoryForPrompt, summary, history)).getOrElse { return Result.failure(it) }
            val raw = answer.structured ?: error("pipeline was built without citations")
            val trace = answer.trace

            var structured = raw
            if (raw.idk) {
                if (full) {
                    structured = withGoalReminder(raw, memory.goal, question)
                    memory.withOpenQuestion(question, turn).also { memory = it.memory; diff += it.diff }
                }
            }
            val sources = if (structured.idk) emptyList() else structured.sources.map { s ->
                val hit = answer.hits.firstOrNull { it.chunk.chunkId == s.chunkId }
                ChatSource(if (s.section.isBlank()) s.file else "${s.file} > ${s.section}", s.score ?: hit?.score ?: 0f, s.chunkId)
            }
            val consulted = if (structured.idk) trace?.retrieved.orEmpty().filter { it.score >= IdkResponder.RELATED_MIN_SCORE }.take(3).map(::source) else emptyList()

            val info = TurnInfo(
                structured = structured, sources = sources, consulted = consulted,
                chunks = answer.hits.map { ChatChunk(it.chunk.chunkId, it.chunk.source, it.chunk.section, it.chunk.text, it.score) }, trace = trace?.let { traceView(it, cfg) },
                memoryDiff = diff, memoryNotes = notes, memoryMode = options.memoryMode,
            )
            val messages = session.messages + ChatMessage("user", question, turn) + ChatMessage("assistant", structured.answer, turn, info)
            var next = session.copy(
                title = if (session.messages.isEmpty()) question.take(50) else session.title,
                updatedAt = now(), messages = messages, memory = memory,
            )
            var summaryUpdated = false
            val evict = HistoryBudget.evictable(next, turn, options)
            if (evict.isNotEmpty()) {
                val (s, _) = summarizer.update(next.summary, evict)
                next = next.copy(summary = s, summarizedUpTo = evict.maxOf { it.turn })
                summaryUpdated = true
            }
            val finalInfo = info.copy(llmCalls = usage.calls - calls0, llmMs = usage.millis - ms0, wallMs = (clock() - start) / 1_000_000, summaryUpdated = summaryUpdated)
            val finalMessages = next.messages.map { if (it.role == "assistant" && it.turn == turn) it.copy(info = finalInfo) else it }
            return Result.success(next.copy(messages = finalMessages))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /** "I don't know" must not lose the thread: keep the closest topics (already in the text) and restate the goal, in the user's language. */
    private fun withGoalReminder(st: StructuredAnswer, goal: String?, question: String): StructuredAnswer {
        if (goal.isNullOrBlank()) return st
        val line = if (IdkResponder.detectLanguage(question) == "ru") "Цель диалога остаётся прежней: «$goal». Можем продолжить с ней или уточнить вопрос выше."
        else "Our goal stays the same: \"$goal\". We can carry on with it, or you can clarify the question above."
        return st.copy(answer = st.answer + "\n\n" + line)
    }

    private fun source(h: SearchHit) = ChatSource(RagPromptBuilder.sourceLabel(h.chunk), h.score, h.chunk.chunkId)

    private fun traceView(t: RetrievalTrace, config: com.example.rag.RagConfig): ChatTrace {
        fun view(h: SearchHit, rerank: Double? = null) = ChatStageHit(h.chunk.chunkId, RagPromptBuilder.sourceLabel(h.chunk), h.score, rerank)
        return ChatTrace(
            t.originalQuery, t.searchQuery, t.rewriteFallback,
            t.retrieved.map { view(it) }, t.filtered.map { view(it) }, t.reranked.map { view(it.hit, it.rerankScore) },
            threshold = config.threshold, topKBefore = config.topKBefore, topKAfter = config.topKAfter,
        )
    }
}
