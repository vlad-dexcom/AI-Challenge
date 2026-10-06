package com.example.rag.chat

import com.example.core.llm.GenerationOptions
import com.example.rag.HistoryMessage
import com.example.rag.LlmQueryRewriter
import com.example.rag.QueryRewriter
import com.example.rag.RagConfig
import com.example.rag.RewriteOutcome
import com.example.core.llm.TextGenerator
import kotlinx.serialization.Serializable

/** What the chat gets besides the question: FULL = task memory + history, HISTORY_ONLY = history only (ablation), NONE = single-shot (baseline). */
@Serializable
enum class MemoryMode { FULL, HISTORY_ONLY, NONE }

@Serializable
data class ChatOptions(
    val memoryMode: MemoryMode = MemoryMode.FULL,
    /** Last N exchanges sent verbatim. */
    val keepLastTurns: Int = 4,
    /** Evicted exchanges are folded into the rolling summary in batches of this size (fewer LLM calls). */
    val summaryBatch: Int = 2,
    /** Hard cap for the verbatim history block; the oldest exchanges go first. Task memory is never cut. */
    val maxHistoryChars: Int = 6000,
    val strategy: String = "structure",
    val topK: Int = 4,
    val topKBefore: Int = 10,
    val threshold: Float = RagConfig.DEFAULT.threshold,
    val llmRerank: Boolean = false,
    val model: String? = null,
) {
    fun ragConfig() = RagConfig(topKBefore, topK, threshold, filter = true, rerank = true, rewrite = true)
}

/** Verbatim-window + rolling-summary bookkeeping. */
object HistoryBudget {
    const val USER_CLIP = 600
    const val ASSISTANT_CLIP = 400

    /** Messages not yet folded into the summary (all of them in NONE mode: none), clipped and capped to [ChatOptions.maxHistoryChars]. */
    fun verbatim(session: ChatSession, options: ChatOptions): List<HistoryMessage> {
        if (options.memoryMode == MemoryMode.NONE) return emptyList()
        val byTurn = session.messages.filter { it.turn > session.summarizedUpTo }.groupBy { it.turn }.toSortedMap()
        val turns = byTurn.values.map { msgs ->
            msgs.mapNotNull { m ->
                val clip = if (m.role == "user") USER_CLIP else ASSISTANT_CLIP
                HistoryMessage(m.role, m.text.take(clip))
            }
        }.toMutableList()
        fun chars() = turns.sumOf { t -> t.sumOf { it.text.length } }
        while (turns.size > 1 && chars() > options.maxHistoryChars) turns.removeAt(0)
        return turns.flatten()
    }

    /** Turns that fall out of the verbatim window after turn [currentTurn] and are not summarized yet; empty until a full batch is ready. */
    fun evictable(session: ChatSession, currentTurn: Int, options: ChatOptions): List<ChatMessage> {
        if (options.memoryMode == MemoryMode.NONE) return emptyList()
        val upTo = currentTurn - options.keepLastTurns.coerceAtLeast(1)
        val msgs = session.messages.filter { it.turn > session.summarizedUpTo && it.turn <= upTo }
        return if (msgs.map { it.turn }.distinct().size >= options.summaryBatch.coerceAtLeast(1)) msgs else emptyList()
    }

    /** The dialogue part of the answer prompt; null when there is nothing to add (first turn, empty memory). */
    fun dialogContext(memory: TaskMemory?, summary: String, history: List<HistoryMessage>): String? {
        val parts = buildList {
            if (memory != null) add(memory.render())
            if (summary.isNotBlank()) add("Summary of the earlier dialogue:\n$summary")
            if (history.isNotEmpty()) add("Dialogue so far:\n" + history.joinToString("\n") { "${it.role}: ${it.text}" })
        }
        if (memory?.isEmpty == true && summary.isBlank() && history.isEmpty()) return null
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }
}

/** Folds evicted exchanges into the rolling summary; a deterministic extract of the user's lines is the fallback. */
class Summarizer(private val generator: TextGenerator) {
    /** Returns the new summary and whether the deterministic fallback was used. */
    suspend fun update(previous: String, evicted: List<ChatMessage>): Pair<String, Boolean> {
        val dialog = evicted.joinToString("\n") { "${it.role}: ${it.text.take(if (it.role == "user") USER else ASSISTANT)}" }
        val prompt = "Previous summary:\n${previous.ifBlank { "(none)" }}\n\nNew exchanges:\n$dialog\n\nWrite the updated summary."
        val reply = generator.generate(SYSTEM, prompt, GenerationOptions(temperature = 0.0)).getOrNull()?.trim()
        if (!reply.isNullOrEmpty()) return reply.take(MAX_SUMMARY) to false
        return fallback(previous, evicted) to true
    }

    companion object {
        const val SYSTEM = "You keep a rolling summary of a personal-trainer chat for later turns. Keep what the user asked, what was answered or planned " +
            "(plan items, numbers), and facts about the user. Plain text, at most 120 words, no preamble. Same language as the dialogue."
        const val MAX_SUMMARY = 1500
        private const val USER = 200
        private const val ASSISTANT = 300

        fun fallback(previous: String, evicted: List<ChatMessage>): String =
            (previous.lines().filter { it.isNotBlank() } + evicted.filter { it.role == "user" }.map { "- user asked: ${it.text.take(120)}" })
                .joinToString("\n").takeLast(MAX_SUMMARY)
    }
}

/**
 * Standalone search query from the question + task memory + summary + recent turns ("what about for my knee?" -> "knee-friendly squat
 * alternatives beginner"). Unlike [LlmQueryRewriter] it does not reject a query that shares no words with the follow-up. On any failure
 * the fallback is the question plus the goal/constraint terms, so retrieval still sees what the dialogue has fixed.
 */
class ContextualRewriter(
    private val generator: TextGenerator,
    private val memory: TaskMemory?,
    private val summary: String,
) : QueryRewriter {
    override suspend fun rewrite(question: String, history: List<HistoryMessage>): RewriteOutcome {
        val contextual = history.isNotEmpty() || summary.isNotBlank() || memory?.isEmpty == false
        val fallbackQuery = listOfNotNull(question, memory?.searchTerms()?.takeIf { it.isNotBlank() }).joinToString(" ")
        val prompt = buildString {
            memory?.takeIf { !it.isEmpty }?.let { appendLine(it.render()); appendLine() }
            if (summary.isNotBlank()) { appendLine("Summary of the earlier dialogue:\n$summary"); appendLine() }
            if (history.isNotEmpty()) {
                appendLine("Conversation so far:")
                history.takeLast(6).forEach { appendLine("${it.role}: ${it.text.take(300)}") }
                appendLine()
            }
            append("Question: $question")
        }
        val reply = generator.generate(SYSTEM, prompt, GenerationOptions(temperature = 0.0)).getOrElse {
            return RewriteOutcome(fallbackQuery, "rewrite call failed: ${it.message?.take(80)}")
        }
        val out = LlmQueryRewriter.validate(question, reply, checkDrift = !contextual)
        return if (out.fallbackReason == null) out else out.copy(query = fallbackQuery)
    }

    companion object {
        val SYSTEM = LlmQueryRewriter.SYSTEM + " If a task memory is given, it holds the user's goal and fixed constraints: when the question depends on them " +
            "(\"what about for my knee?\", \"a plan for me\"), put the relevant keywords (injury, equipment, diet, level) into the query; " +
            "do not add constraints that have nothing to do with the question."
    }
}
