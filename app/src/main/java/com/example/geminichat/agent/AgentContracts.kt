package com.example.geminichat.agent

/**
 * A single turn in a conversation, kept generic so it can be reused both for the (currently
 * empty) [AgentRequest.history] and for a future richer multi-turn message list.
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
 * [history] is unused today — the agent is stateless and every request is handled
 * independently — but it's part of the contract now so that adding conversation memory later
 * (e.g. rendering prior turns into the prompt, or switching to a multi-message API payload)
 * doesn't require changing the [Agent] interface or any caller signatures.
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
