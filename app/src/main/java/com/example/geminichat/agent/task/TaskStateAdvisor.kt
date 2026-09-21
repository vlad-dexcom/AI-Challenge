package com.example.geminichat.agent.task

import com.example.geminichat.agent.LlmClient
import com.example.geminichat.agent.LlmRequestSpec
import com.example.geminichat.agent.TokenEstimator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A transition [TaskStateAdvisor] can propose — deliberately a small subset of
 * [TaskStateMachine]'s operations: only the ones inferable from a chat turn. Pause/resume/reset
 * stay manual-only UI actions, since "should we stop" is a user decision, not something to
 * infer from wording. */
enum class TaskTransitionAction { APPROVE_PLAN, NEXT_STEP, REQUEST_VALIDATION, SEND_BACK_TO_EXECUTION, COMPLETE }

/**
 * One proposed transition, produced by [TaskStateAdvisor.suggest]: "move to [action]", with
 * [reason] shown to the user so applying it is an informed choice, never a silent auto-write
 * (mirrors [com.example.geminichat.agent.profile.PreferenceSuggestion]).
 *
 * [proposedSteps] is only ever populated for [TaskTransitionAction.APPROVE_PLAN] — the advisor
 * extracts the plan's step list from the assistant's last message so the suggestion banner can
 * apply it in one tap instead of forcing the user to retype it into [TaskPanel]'s own field.
 * Empty for every other action, and empty here too if the advisor couldn't confidently find a
 * step list (the banner then falls back to "Dismiss only", same as before).
 */
data class TaskTransitionSuggestion(
    val action: TaskTransitionAction,
    val reason: String,
    val proposedSteps: List<String> = emptyList()
)

private val ADVISOR_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Day 13's optional hybrid update path for [TaskState], modeled directly on
 * [com.example.geminichat.agent.profile.PreferenceAdvisor]: the task is moved through
 * [TaskStateMachine] by explicit UI buttons, but after a user turn that sounds like it just
 * satisfied the current [TaskState.expectedAction] ("готово", "план утверждён", "не сработало,
 * нужно переделать"), one LLM call *proposes* a transition. The suggestion is never applied
 * automatically — [com.example.geminichat.ChatViewModel] surfaces it for the user to approve or
 * dismiss, so [TaskState] only ever changes through [TaskStateMachine], either by a direct
 * button press or by the user's one-tap approval of a suggestion here.
 *
 * Deliberately a *separate* LLM call from [com.example.geminichat.agent.memory.MemoryRouter] and
 * [com.example.geminichat.agent.profile.PreferenceAdvisor]: this decides "did the task's
 * position just change", not "what fact was learned" or "what preference was stated" —
 * independently testable and independently priced (see [TaskStateAdvisorOutcome.tokensUsed]).
 */
class TaskStateAdvisor(private val client: LlmClient) {

    companion object {
        private const val SYSTEM_INSTRUCTION =
            "You watch a chat between a user and an assistant that is tracking a TASK through " +
                "stages: PLANNING, EXECUTION, VALIDATION, DONE. You will be given the task's " +
                "current stage, its expected next action, the assistant's last message, and the " +
                "user's newest message. If the newest message clearly indicates the expected " +
                "action just happened, respond with a single JSON object: {\"action\": one of " +
                "[\"approve_plan\",\"next_step\",\"request_validation\"," +
                "\"send_back_to_execution\",\"complete\"], \"reason\": a short explanation of " +
                "why you inferred this, \"steps\": an array of short step strings}. Only " +
                "propose an action that is valid from the current stage (approve_plan only " +
                "from PLANNING; next_step/request_validation only from EXECUTION; " +
                "send_back_to_execution/complete only from VALIDATION). The \"steps\" field is " +
                "REQUIRED and only meaningful when action is \"approve_plan\": extract the plan " +
                "step list the assistant proposed in its last message (e.g. one entry per " +
                "week/phase/session it laid out) as an array of short strings, in order; if you " +
                "cannot find a clear step list there, use an empty array. For every other " +
                "action, always set \"steps\" to an empty array. If nothing in the message " +
                "indicates a transition, respond with exactly the JSON literal null. Respond " +
                "with ONLY the JSON — no markdown fences, no commentary."

        /** Parses an advisor reply into a [TaskTransitionSuggestion], or `null` when the reply
         * is the JSON literal `null`, unparseable, or names an action this app doesn't
         * understand — any of those cases fail safe by proposing nothing. */
        internal fun parseSuggestion(rawText: String): TaskTransitionSuggestion? {
            val cleaned = rawText.trim()
                .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
                .removeSuffix("```")
                .trim()
            if (cleaned.isEmpty() || cleaned == "null") return null
            return try {
                val root = ADVISOR_JSON.parseToJsonElement(cleaned).jsonObject
                val actionRaw = root["action"]?.jsonPrimitive?.content ?: return null
                val reason = root["reason"]?.jsonPrimitive?.content?.trim().orEmpty()
                val action = actionToTransition(actionRaw) ?: return null
                val steps = root["steps"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.content.trim().takeIf { s -> s.isNotEmpty() } }
                    .orEmpty()
                TaskTransitionSuggestion(action = action, reason = reason, proposedSteps = steps)
            } catch (e: Exception) {
                null
            }
        }

        internal fun actionToTransition(raw: String): TaskTransitionAction? = when (raw.trim().lowercase()) {
            "approve_plan" -> TaskTransitionAction.APPROVE_PLAN
            "next_step" -> TaskTransitionAction.NEXT_STEP
            "request_validation" -> TaskTransitionAction.REQUEST_VALIDATION
            "send_back_to_execution" -> TaskTransitionAction.SEND_BACK_TO_EXECUTION
            "complete" -> TaskTransitionAction.COMPLETE
            else -> null
        }
    }

    /**
     * Calls the LLM to check whether [newUserMessage] indicates [state]'s current expected
     * action just happened. [lastAssistantMessage] (the most recent AGENT turn, if any) is
     * included so the advisor can extract the plan's step list for a one-tap
     * [TaskTransitionAction.APPROVE_PLAN] (see [TaskTransitionSuggestion.proposedSteps]).
     * Returns `Result.success(outcome.suggestion == null)` when [state] isn't active or is
     * paused — there is nothing to advance either way, and this deliberately avoids spending a
     * call on it. Never throws: a transport failure surfaces as [Result.failure] the caller can
     * ignore (a missed suggestion never breaks the chat turn).
     */
    suspend fun suggest(
        state: TaskState,
        newUserMessage: String,
        model: String,
        lastAssistantMessage: String? = null
    ): Result<TaskStateAdvisorOutcome> {
        if (!state.isActive || state.paused || state.stage == TaskStage.DONE) {
            return Result.success(TaskStateAdvisorOutcome(suggestion = null, tokensUsed = 0))
        }
        val input = "Current stage: ${state.stage}\n" +
            "Expected next action: ${state.expectedActor} — ${state.expectedAction}\n\n" +
            (if (!lastAssistantMessage.isNullOrBlank()) "Assistant's last message:\n$lastAssistantMessage\n\n" else "") +
            "Newest user message:\nUser: $newUserMessage\n\n" +
            "Respond now."
        val spec = LlmRequestSpec(model = model, input = input, systemInstruction = SYSTEM_INSTRUCTION)
        val requestTokens = TokenEstimator.estimate(input) + TokenEstimator.estimate(SYSTEM_INSTRUCTION)

        return client.complete(spec).map { text ->
            TaskStateAdvisorOutcome(
                suggestion = parseSuggestion(text),
                tokensUsed = requestTokens + TokenEstimator.estimate(text)
            )
        }
    }
}

/** Outcome of one [TaskStateAdvisor.suggest] call: [suggestion] is `null` when nothing
 * indicated a transition; [tokensUsed] is priced separately from the chat turn and the other
 * advisors' own costs. */
data class TaskStateAdvisorOutcome(
    val suggestion: TaskTransitionSuggestion?,
    val tokensUsed: Int
)
