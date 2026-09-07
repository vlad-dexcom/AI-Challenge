package com.example.geminichat.agent

/**
 * The default [Agent] implementation: a single persona ([config]) backed by any [LlmClient].
 * This is where "the logic of request/response is encapsulated in the agent" lives — the
 * ViewModel never builds a prompt, picks a model, or interprets a raw HTTP/LLM error; it just
 * calls [handle].
 *
 * Responsibilities owned here:
 * - Validating the incoming request (e.g. rejecting blank input) before spending an API call.
 * - Assembling the [LlmRequestSpec] from [config] (persona, model, generation params) and the
 *   [AgentRequest] (user message, optional model override).
 * - Rendering conversation history into the prompt — currently a no-op since agents are
 *   stateless, but the seam is here: see [renderPrompt].
 * - Turning the raw completion into a validated [AgentResponse], with timing metadata.
 * - Mapping [LlmClient] failures into a single [Result.failure] the UI can display as-is.
 *
 * Tool-calling would extend [handle]: after receiving a first completion, detect a
 * tool-call intent, execute the matching tool, and issue a second [LlmClient.complete] call
 * with the tool result appended to the prompt before returning the final [AgentResponse].
 */
class LlmAgent(
    override val config: AgentConfig,
    private val client: LlmClient
) : Agent {

    override suspend fun handle(request: AgentRequest): Result<AgentResponse> {
        val userMessage = request.userMessage.trim()
        if (userMessage.isEmpty()) {
            return Result.failure(IllegalArgumentException("Please enter a message."))
        }

        val model = request.modelOverride ?: config.model
        val spec = LlmRequestSpec(
            model = model,
            input = renderPrompt(request, userMessage),
            systemInstruction = config.systemInstruction,
            maxOutputTokens = config.maxOutputTokens,
            temperature = config.temperature
        )

        val startedAt = System.currentTimeMillis()
        val result = client.complete(spec)
        val elapsedMs = System.currentTimeMillis() - startedAt

        return result.fold(
            onSuccess = { text ->
                val answer = text.trim()
                if (answer.isEmpty()) {
                    Result.failure(Exception("${config.displayName} returned an empty response."))
                } else {
                    Result.success(
                        AgentResponse(
                            text = answer,
                            agentId = config.id,
                            model = model,
                            elapsedMs = elapsedMs
                        )
                    )
                }
            },
            onFailure = { error -> Result.failure(error) }
        )
    }

    /**
     * Renders the final prompt sent to the LLM. [AgentRequest.history] is intentionally
     * ignored today (agents are stateless), but this is the single place to start folding
     * prior turns in later — e.g. prefixing them as "User: ...\nAgent: ..." pairs, or
     * switching [LlmRequestSpec] to carry a structured message list instead of one string.
     */
    private fun renderPrompt(request: AgentRequest, userMessage: String): String {
        if (request.history.isEmpty()) return userMessage
        // Placeholder for future multi-turn support; not exercised while history is empty.
        val transcript = request.history.joinToString("\n") { message ->
            val speaker = if (message.role == AgentMessage.Role.USER) "User" else config.displayName
            "$speaker: ${message.text}"
        }
        return "$transcript\nUser: $userMessage"
    }
}
