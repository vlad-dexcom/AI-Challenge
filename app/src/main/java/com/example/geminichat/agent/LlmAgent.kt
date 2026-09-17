package com.example.geminichat.agent

import com.example.geminichat.agent.invariant.InvariantGuard
import com.example.geminichat.agent.invariant.InvariantRenderer
import com.example.geminichat.agent.invariant.InvariantSet

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
    private val client: LlmClient,
    /**
     * Day 14: hard, non-negotiable rules (see
     * [com.example.geminichat.agent.invariant.Invariant]) this agent enforces on every turn.
     * Defaults to an empty set so every pre-Day-14 caller/test is unaffected. See [handle] for
     * how a conflicting request is refused **before** [client] is ever called.
     */
    private val invariants: InvariantSet = InvariantSet()
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

        // Day 14: the deterministic pre-check runs first, before any prompt is even assembled.
        // A conflicting request is refused by code — the model is never called — so an
        // invariant can never be talked around, and the refusal costs nothing (see
        // [TokenUsage] on the returned response). This is the "medium" enforcement depth: only
        // the request is checked, not the model's answer.
        val conflicts = InvariantGuard.check(invariants, userMessage)
        if (conflicts.isNotEmpty()) {
            val requestTokens = TokenEstimator.estimate(userMessage)
            return Result.success(
                AgentResponse(
                    text = InvariantGuard.refusalText(conflicts),
                    agentId = config.id,
                    model = request.modelOverride ?: config.model,
                    elapsedMs = 0,
                    tokenUsage = TokenUsage(
                        requestTokens = requestTokens,
                        historyTokens = 0,
                        systemInstructionTokens = 0,
                        promptTokens = 0,
                        completionTokens = 0
                    ),
                    refusedByInvariantIds = conflicts.map { it.invariant.id }
                )
            )
        }

        val model = request.modelOverride ?: config.model
        val historyText = renderHistory(request.history)
        // The agent's memory layers (see [AgentRequest.longTermMemory]/[AgentRequest.workingMemory]):
        // rendered ahead of everything else — long-term first (most stable: who the user is),
        // then working (what the current task is) — so the model reads "who is this" before
        // "what are we doing right now" before the recent conversation itself.
        val longTermMemoryText = request.longTermMemory?.trim().orEmpty()
        val workingMemoryText = request.workingMemory?.trim().orEmpty()
        // Day 13: where the task currently stands (stage/step/expected action) is task
        // *context*, changing turn to turn just like the memory layers above it — so it's
        // rendered into the prompt body right after working memory, not the system instruction.
        val taskStateText = request.taskState?.trim().orEmpty()
        val contextText = listOf(
            longTermMemoryText.takeIf { it.isNotEmpty() },
            workingMemoryText.takeIf { it.isNotEmpty() },
            taskStateText.takeIf { it.isNotEmpty() },
            historyText.takeIf { it.isNotEmpty() }
        ).filterNotNull().joinToString("\n")
        val input = if (contextText.isEmpty()) userMessage else "$contextText\nUser: $userMessage"

        // Day 12: the profile is personalization *instruction* ("how to answer"), not
        // conversational context, so it's appended to the system instruction rather than
        // mixed into [input] alongside memory/history. Day 13's stage rules are the same kind
        // of thing — behavior, not context — so they're appended the same way. Day 14's
        // invariants block is appended *last*, so it has the final word if it conflicts with
        // either the profile or the stage rules (see [InvariantRenderer]).
        val userProfileText = request.userProfile?.trim().orEmpty()
        val taskStageRulesText = request.taskStageRules?.trim().orEmpty()
        val invariantsText = request.invariants?.trim().orEmpty()
        val systemAdditions =
            listOf(userProfileText, taskStageRulesText, invariantsText).filter { it.isNotEmpty() }
        val effectiveSystemInstruction = if (systemAdditions.isEmpty()) {
            config.systemInstruction
        } else {
            "${config.systemInstruction}\n\n${systemAdditions.joinToString("\n\n")}"
        }

        // Count tokens for each part *before* calling the client, so an over-budget
        // conversation can be refused without ever making the network call (see
        // [ContextWindowExceededException]) instead of sending a request the model would
        // truncate or reject.
        val requestTokens = TokenEstimator.estimate(userMessage)
        val historyTokens = TokenEstimator.estimate(historyText)
        val longTermMemoryTokens = TokenEstimator.estimate(longTermMemoryText)
        val workingMemoryTokens = TokenEstimator.estimate(workingMemoryText)
        val profileTokens = TokenEstimator.estimate(userProfileText)
        val taskStateTokens = TokenEstimator.estimate(taskStateText)
        val taskStageRulesTokens = TokenEstimator.estimate(taskStageRulesText)
        val invariantTokens = TokenEstimator.estimate(invariantsText)
        val systemInstructionTokens = TokenEstimator.estimate(effectiveSystemInstruction)
        val promptTokens = requestTokens + historyTokens +
            longTermMemoryTokens + workingMemoryTokens + profileTokens +
            taskStateTokens + taskStageRulesTokens + invariantTokens + systemInstructionTokens

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
            systemInstruction = effectiveSystemInstruction,
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
                                completionTokens = TokenEstimator.estimate(answer),
                                longTermMemoryTokens = longTermMemoryTokens,
                                workingMemoryTokens = workingMemoryTokens,
                                profileTokens = profileTokens,
                                taskStateTokens = taskStateTokens,
                                taskStageRulesTokens = taskStageRulesTokens,
                                invariantTokens = invariantTokens
                            )
                        )
                    )
                }
            },
            onFailure = { error -> Result.failure(error) }
        )
    }

    /**
     * Renders [history] as a "User: ...\n<Agent>: ..." transcript so the model has the recent
     * prior conversation as context (empty string when there is no history yet). The caller
     * (currently [com.example.geminichat.ChatViewModel]) decides what history to pass in — it
     * is now persisted/restored across app restarts via
     * [com.example.geminichat.ChatHistoryStore], so it survives beyond a single app
     * session/process. Kept separate from the final prompt assembly in [handle] so its token
     * count can be estimated on its own (see [TokenUsage.historyTokens]).
     */
    private fun renderHistory(history: List<AgentMessage>): String {
        if (history.isEmpty()) return ""
        return history.joinToString("\n") { message ->
            val speaker = if (message.role == AgentMessage.Role.USER) "User" else config.displayName
            "$speaker: ${message.text}"
        }
    }
}
