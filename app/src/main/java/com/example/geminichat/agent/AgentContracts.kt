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
 * decides what to pass in (currently: a recent tail of the chat — see
 * [com.example.geminichat.agent.memory.MemoryRouter]) and is also responsible for
 * persisting/restoring it across app restarts via [com.example.geminichat.ChatHistoryStore], so
 * a chat now resumes instead of starting empty on every launch.
 */
data class AgentRequest(
    val userMessage: String,
    val history: List<AgentMessage> = emptyList(),
    /** Optional override of the [AgentConfig.model] the agent would otherwise use. */
    val modelOverride: String? = null,
    /**
     * Optional rendered [com.example.geminichat.agent.memory.MemoryLayer.LONG_TERM] block:
     * durable facts about the *user* — profile, standing decisions, knowledge — that persist
     * across tasks, branches, and even agent personas (see
     * [com.example.geminichat.agent.memory.MemoryStore]). Rendered first, ahead of everything
     * else, since it's the most stable, least likely to change.
     */
    val longTermMemory: String? = null,
    /**
     * Optional rendered [com.example.geminichat.agent.memory.MemoryLayer.WORKING] block: facts
     * about the *current task* only — goal, constraints, steps already decided, open
     * questions — cleared independently of [longTermMemory] once the task ends. Rendered right
     * after [longTermMemory], ahead of [history].
     */
    val workingMemory: String? = null,
    /**
     * Optional rendered Day 12 [com.example.geminichat.agent.profile.UserProfile] block (see
     * [com.example.geminichat.agent.profile.ProfileRenderer]): declarative personalization
     * directives (tone, format, constraints) rather than a fact the agent learned. Unlike
     * [longTermMemory]/[workingMemory], which [LlmAgent] folds into the user-turn prompt, this
     * is appended to [AgentConfig.systemInstruction] — see [LlmAgent.handle] — since it's an
     * instruction about *how* to answer, not conversational context.
     */
    val userProfile: String? = null,
    /**
     * Optional Day 13 [com.example.geminichat.agent.task.TaskStateRenderer.render] block: where
     * the current task stands right now — stage, current step, and who's expected to act next
     * (see [com.example.geminichat.agent.task.TaskState]). Unlike [userProfile] (a standing
     * instruction about *how* to answer), this is task *context* that changes turn to turn, so
     * [LlmAgent] folds it into the user-turn prompt right after [workingMemory] rather than the
     * system instruction.
     */
    val taskState: String? = null,
    /**
     * Optional Day 13 [com.example.geminichat.agent.task.TaskStateRenderer.stageRules] text: a
     * short behavioral rule for the task's current stage (e.g. "don't re-propose an already
     * approved plan"). Unlike [taskState], this *is* an instruction about how to behave, so
     * [LlmAgent] appends it to [AgentConfig.systemInstruction] alongside [userProfile] rather
     * than mixing it into the prompt body.
     */
    val taskStageRules: String? = null
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
 *   [LlmAgent.renderHistory]); zero for the first turn of a chat.
 * - [longTermMemoryTokens] — the rendered
 *   [com.example.geminichat.agent.memory.MemoryLayer.LONG_TERM] block (see
 *   [AgentRequest.longTermMemory]); zero when that layer is empty.
 * - [workingMemoryTokens] — the rendered
 *   [com.example.geminichat.agent.memory.MemoryLayer.WORKING] block (see
 *   [AgentRequest.workingMemory]); zero when that layer is empty.
 * - [profileTokens] — the rendered Day 12 [com.example.geminichat.agent.profile.UserProfile]
 *   block (see [AgentRequest.userProfile]), folded into the system instruction; zero when the
 *   profile is empty.
 * - [taskStateTokens] — the rendered Day 13
 *   [com.example.geminichat.agent.task.TaskStateRenderer.render] block (see
 *   [AgentRequest.taskState]), folded into the prompt body; zero when no task is active.
 * - [taskStageRulesTokens] — the Day 13
 *   [com.example.geminichat.agent.task.TaskStateRenderer.stageRules] text (see
 *   [AgentRequest.taskStageRules]), folded into the system instruction; zero when no task is
 *   active.
 * - [systemInstructionTokens] — the agent's persona/system instruction, sent separately from
 *   [LlmRequestSpec.input] but still counted against the model's context window.
 * - [promptTokens] — everything actually sent to the model for this call
 *   (`requestTokens + historyTokens + longTermMemoryTokens + workingMemoryTokens +
 *   profileTokens + taskStateTokens + taskStageRulesTokens + systemInstructionTokens`).
 * - [completionTokens] — the model's reply.
 * - [totalTokens] — `promptTokens + completionTokens`, i.e. this call's full token cost.
 */
data class TokenUsage(
    val requestTokens: Int,
    val historyTokens: Int,
    val systemInstructionTokens: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val longTermMemoryTokens: Int = 0,
    val workingMemoryTokens: Int = 0,
    val profileTokens: Int = 0,
    val taskStateTokens: Int = 0,
    val taskStageRulesTokens: Int = 0
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}
