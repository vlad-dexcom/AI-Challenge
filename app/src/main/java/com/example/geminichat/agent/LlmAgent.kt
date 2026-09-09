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
 * - Rendering conversation history into the prompt — the ViewModel passes the current chat's
 *   prior turns as [AgentRequest.history]; see [renderHistory] for how they're folded in.
 * - Estimating token counts for the request, history, system instruction, and reply (see
 *   [TokenUsage]), and refusing to call the client at all when the estimated prompt plus a
 *   reserved output budget would exceed the model's context window (see
 *   [ContextWindowExceededException]).
 * - Turning the raw completion into a validated [AgentResponse], with timing and token metadata.
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

    companion object {
        /**
         * Tokens reserved for the model's reply when [AgentConfig.maxOutputTokens] isn't set,
         * used only to budget against [LlmClient.contextWindowTokens] before sending a request.
         */
        const val DEFAULT_RESERVED_OUTPUT_TOKENS = 2048
    }

    override suspend fun handle(request: AgentRequest): Result<AgentResponse> {
        val userMessage = request.userMessage.trim()
        if (userMessage.isEmpty()) {
            return Result.failure(IllegalArgumentException("Please enter a message."))
        }

        val model = request.modelOverride ?: config.model
        val historyText = renderHistory(request.history)
        val input = if (historyText.isEmpty()) userMessage else "$historyText\nUser: $userMessage"

        // Count tokens for each part *before* calling the client, so an over-budget
        // conversation can be refused without ever making the network call (see
        // [ContextWindowExceededException]) instead of sending a request the model would
        // truncate or reject.
        val requestTokens = TokenEstimator.estimate(userMessage)
        val historyTokens = TokenEstimator.estimate(historyText)
        val systemInstructionTokens = TokenEstimator.estimate(config.systemInstruction)
        val promptTokens = requestTokens + historyTokens + systemInstructionTokens

        val reservedOutputTokens = config.maxOutputTokens ?: DEFAULT_RESERVED_OUTPUT_TOKENS
        val contextWindowTokens = client.contextWindowTokens(model)
        if (promptTokens + reservedOutputTokens > contextWindowTokens) {
            return Result.failure(
                ContextWindowExceededException(
                    promptTokens = promptTokens,
                    reservedOutputTokens = reservedOutputTokens,
                    contextWindowTokens = contextWindowTokens
                )
            )
        }

        val spec = LlmRequestSpec(
            model = model,
            input = input,
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
                            elapsedMs = elapsedMs,
                            tokenUsage = TokenUsage(
                                requestTokens = requestTokens,
                                historyTokens = historyTokens,
                                systemInstructionTokens = systemInstructionTokens,
                                promptTokens = promptTokens,
                                completionTokens = TokenEstimator.estimate(answer)
                            )
                        )
                    )
                }
            },
            onFailure = { error -> Result.failure(error) }
        )
    }

    /**
     * Renders [history] as a "User: ...\n<Agent>: ..." transcript so the model has the full
     * prior conversation as context (empty string when there is no history yet). The caller
     * (currently [com.example.geminichat.ChatViewModel]) decides what history to pass in —
     * this in-memory chat history lasts only for the current app session/process, it is not
     * persisted across restarts. Kept separate from the final prompt assembly in [handle] so
     * its token count can be estimated on its own (see [TokenUsage.historyTokens]).
     */
    private fun renderHistory(history: List<AgentMessage>): String {
        if (history.isEmpty()) return ""
        return history.joinToString("\n") { message ->
            val speaker = if (message.role == AgentMessage.Role.USER) "User" else config.displayName
            "$speaker: ${message.text}"
        }
    }
}
