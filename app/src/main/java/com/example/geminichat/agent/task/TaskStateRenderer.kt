package com.example.geminichat.agent.task

/**
 * Day 13: renders [TaskState] into two separate things, both deterministic (no LLM call) since
 * a task's position is just data the state machine already computed:
 *
 * - [render] — a labeled *context* block describing where the task currently is. Folded into
 *   the user-turn prompt right after Day 11's working memory block (see
 *   [com.example.geminichat.agent.AgentRequest.taskState]): it's task context, not a stylistic
 *   directive, so it belongs alongside [com.example.geminichat.agent.AgentRequest.workingMemory]
 *   rather than the system instruction where Day 12's [com.example.geminichat.agent.profile.ProfileRenderer]
 *   output goes.
 * - [stageRules] — a short *behavioral* rule appended to the agent's system instruction (see
 *   [com.example.geminichat.agent.LlmAgent.handle]) so the stage actually changes what the
 *   agent is allowed to do (e.g. not re-proposing a plan that's already approved), instead of
 *   only being mentioned as inert context the model might ignore.
 */
object TaskStateRenderer {

    private const val HEADER = "Task state (follow it; do not restart or re-explain finished stages):"

    /** Renders [state] as a block, or an empty string for [TaskState.NONE] — in which case the
     * agent's prompt is unchanged from before Day 13. */
    fun render(state: TaskState): String {
        if (!state.isActive) return ""

        val lines = mutableListOf<String>()
        lines += "- Task: ${state.title}"
        lines += "- Stage: ${state.stage}" + (state.progressLabel?.let { " ($it)" } ?: "")
        state.currentStep?.let { lines += "- Current step: $it" }
        if (state.validationOutcome != ValidationOutcome.NOT_RUN) {
            val noteSuffix = if (state.validationNote.isNotBlank()) " (${state.validationNote})" else ""
            lines += "- Validation outcome: ${state.validationOutcome}$noteSuffix"
        }
        if (state.expectedAction.isNotBlank()) {
            lines += "- Expected next action: ${state.expectedActor} — ${state.expectedAction}"
        }
        if (state.paused) {
            lines += "- Status: PAUSED — resume exactly here; do not re-explain earlier stages"
        }
        if (state.stage == TaskStage.CANCELLED) {
            val reasonSuffix = if (state.cancellationReason.isNotBlank()) " (Reason: ${state.cancellationReason})" else ""
            lines += "- Cancellation: Task was cancelled$reasonSuffix"
        }
        return "$HEADER\n${lines.joinToString("\n")}"
    }

    /**
     * A one-line behavioral rule for the current stage, or an empty string for [TaskState.NONE]
     * or [TaskState.paused] (a paused task expects *no* forward progress at all, so no
     * stage-specific "do this" rule applies — only [render]'s "PAUSED" line matters).
     */
    fun stageRules(state: TaskState): String {
        if (!state.isActive) return ""
        if (state.paused) {
            return "The task above is paused. Do not advance it or propose next steps; wait for the user to resume it."
        }
        return when (state.stage) {
            TaskStage.PLANNING ->
                "The task above is in the PLANNING stage: propose and refine the plan; do not start " +
                    "executing steps, generating workout programs, or skipping ahead to execution until the plan is approved."
            TaskStage.EXECUTION ->
                "The task above is in the EXECUTION stage: work on the current step only; do not " +
                    "re-explain or re-propose the already-approved plan; do not finalize or mark the task complete without validation."
            TaskStage.VALIDATION ->
                "The task above is in the VALIDATION stage: check the result against the goal; do not " +
                    "add new steps or new work here; do not finalize or complete the task until validation outcome is PASSED."
            TaskStage.DONE ->
                "The task above is DONE. Do not resume work on it unless the user explicitly starts a new task."
            TaskStage.CANCELLED ->
                "The task above was CANCELLED. Do not continue work on it unless the user explicitly starts a new task."
        }
    }
}
