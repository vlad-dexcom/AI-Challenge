package com.example.geminichat.agent

/**
 * A single turn in a conversation, used to build [AgentRequest.history].
 */
data class AgentMessage(
    val role: Role,
    val text: String
) {
    enum class Role { USER, AGENT }
}

/**
 * Input to [Agent.handle].
 *
 * [history] carries the prior turns of the current chat, which [LlmAgent] folds into the
 * prompt (see [LlmAgent.renderHistory]) so the model has conversational context. This is just
 * the in-memory transcript for a single request — the caller ([com.example.geminichat.ChatViewModel])
 * decides what to pass in (currently: the whole visible chat, unbounded) and is also
 * responsible for persisting/restoring it across app restarts via
 * [com.example.geminichat.ChatHistoryStore], so a chat now resumes instead of starting empty
 * on every launch.
 */
data class AgentRequest(
    val userMessage: String,
    val history: List<AgentMessage> = emptyList(),
    /** Optional override of the [AgentConfig.model] the agent would otherwise use. */
    val modelOverride: String? = null,
    /**
     * Optional condensed stand-in for older turns that have been folded out of [history] by
     * [com.example.geminichat.agent.HistoryCompressor] (Day 9, the "Summary" strategy — see
     * [com.example.geminichat.agent.ContextStrategy]). When set, [LlmAgent] prepends it to the
     * prompt ahead of [history] instead of requiring the full, ever-growing transcript to be
     * replayed every turn.
     */
    val summary: String? = null,
    /**
     * Optional rendered "sticky facts" key-value memory block (Day 10, the "Facts" strategy —
     * see [com.example.geminichat.agent.FactsExtractor]): durable facts distilled from the
     * conversation (goal, constraints, preferences, decisions) that should survive regardless
     * of how much raw history is dropped. Rendered ahead of [summary]/[history] when set.
     */
    val facts: String? = null,
    /**
     * Optional rendered [com.example.geminichat.agent.memory.MemoryLayer.LONG_TERM] block
     * (Day 11, the "Memory layers" strategy): durable facts about the *user* — profile,
     * standing decisions, knowledge — that persist across tasks, branches, and even agent
     * personas (see [com.example.geminichat.agent.memory.MemoryStore]). Rendered first, ahead
     * of everything else, since it's the most stable, least likely to change.
     */
    val longTermMemory: String? = null,
    /**
     * Optional rendered [com.example.geminichat.agent.memory.MemoryLayer.WORKING] block
     * (Day 11): facts about the *current task* only — goal, constraints, steps already
     * decided, open questions — cleared independently of [longTermMemory] once the task ends.
     * Rendered right after [longTermMemory], ahead of [summary]/[history].
     */
    val workingMemory: String? = null
)

/** Successful output of [Agent.handle]. */
data class AgentResponse(
    val text: String,
    val agentId: String,
    val model: String,
    val elapsedMs: Long,
    val tokenUsage: TokenUsage
)

/**
 * Token accounting for a single [Agent.handle] call, estimated via [TokenEstimator] (see its
 * doc for why this is an approximation, not a billed count).
 *
 * - [requestTokens] — just the new user message.
 * - [historyTokens] — the raw conversation turns folded into the prompt (see
 *   [LlmAgent.renderHistory]); zero for the first turn of a chat, and — once Day 9's
 *   compression kicks in — only the *recent*, uncompressed tail rather than the whole history.
 * - [summaryTokens] — the condensed stand-in for older turns (see [AgentRequest.summary] /
 *   [com.example.geminichat.agent.HistoryCompressor]); zero when no compression has happened
 *   yet or compression is disabled.
 * - [factsTokens] — the rendered sticky facts key-value memory (see [AgentRequest.facts] /
 *   [com.example.geminichat.agent.FactsExtractor]); zero unless the Facts strategy is active.
 * - [longTermMemoryTokens] — the rendered
 *   [com.example.geminichat.agent.memory.MemoryLayer.LONG_TERM] block (see
 *   [AgentRequest.longTermMemory]); zero unless the Memory-layers strategy is active.
 * - [workingMemoryTokens] — the rendered
 *   [com.example.geminichat.agent.memory.MemoryLayer.WORKING] block (see
 *   [AgentRequest.workingMemory]); zero unless the Memory-layers strategy is active.
 * - [systemInstructionTokens] — the agent's persona/system instruction, sent separately from
 *   [LlmRequestSpec.input] but still counted against the model's context window.
 * - [promptTokens] — everything actually sent to the model for this call
 *   (`requestTokens + historyTokens + summaryTokens + factsTokens + longTermMemoryTokens +
 *   workingMemoryTokens + systemInstructionTokens`).
 * - [completionTokens] — the model's reply.
 * - [totalTokens] — `promptTokens + completionTokens`, i.e. this call's full token cost.
 */
data class TokenUsage(
    val requestTokens: Int,
    val historyTokens: Int,
    val systemInstructionTokens: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val summaryTokens: Int = 0,
    val factsTokens: Int = 0,
    val longTermMemoryTokens: Int = 0,
    val workingMemoryTokens: Int = 0
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}
