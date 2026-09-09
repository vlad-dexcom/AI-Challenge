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
 * prompt (see [LlmAgent.renderPrompt]) so the model has conversational context. This history
 * is kept in memory only for the lifetime of the app process/session — nothing is persisted
 * across restarts, and the caller ([com.example.geminichat.ChatViewModel]) is responsible for
 * deciding what to pass in (currently: the whole visible chat, unbounded).
 */
data class AgentRequest(
    val userMessage: String,
    val history: List<AgentMessage> = emptyList(),
    /** Optional override of the [AgentConfig.model] the agent would otherwise use. */
    val modelOverride: String? = null
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
 * - [historyTokens] — the prior conversation folded into the prompt (see
 *   [LlmAgent.renderPrompt]); zero for the first turn of a chat.
 * - [systemInstructionTokens] — the agent's persona/system instruction, sent separately from
 *   [LlmRequestSpec.input] but still counted against the model's context window.
 * - [promptTokens] — everything actually sent to the model for this call
 *   (`requestTokens + historyTokens + systemInstructionTokens`).
 * - [completionTokens] — the model's reply.
 * - [totalTokens] — `promptTokens + completionTokens`, i.e. this call's full token cost.
 */
data class TokenUsage(
    val requestTokens: Int,
    val historyTokens: Int,
    val systemInstructionTokens: Int,
    val promptTokens: Int,
    val completionTokens: Int
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}
