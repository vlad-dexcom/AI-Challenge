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
 * prompt (see [LlmAgent.renderPrompt]) so the model has conversational context. This is just
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
    val modelOverride: String? = null
)

/** Successful output of [Agent.handle]. */
data class AgentResponse(
    val text: String,
    val agentId: String,
    val model: String,
    val elapsedMs: Long
)
