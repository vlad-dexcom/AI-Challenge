package com.example.geminichat.agent

/**
 * An agent is a *separate entity*, not a bare API call: it owns a persona ([config]) and the
 * logic for turning a user request into an LLM call and a validated reply. Callers (e.g. a
 * ViewModel) depend only on this interface and never touch the transport/model APIs directly.
 */
interface Agent {
    val config: AgentConfig

    suspend fun handle(request: AgentRequest): Result<AgentResponse>
}
