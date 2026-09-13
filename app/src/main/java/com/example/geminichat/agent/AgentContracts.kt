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
    val facts: String? = null
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
 * - [systemInstructionTokens] — the agent's persona/system instruction, sent separately from
 *   [LlmRequestSpec.input] but still counted against the model's context window.
 * - [promptTokens] — everything actually sent to the model for this call
 *   (`requestTokens + historyTokens + summaryTokens + factsTokens + systemInstructionTokens`).
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
    val factsTokens: Int = 0
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}
