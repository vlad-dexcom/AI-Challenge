package com.example.geminichat.agent.invariant

import kotlinx.serialization.Serializable

/**
 * Day 14: a rough grouping for invariants, shown in the UI and used only for display/grouping —
 * never interpreted by [InvariantGuard] or [InvariantRenderer]. [TECH] exists to prove the
 * mechanism isn't fitness-specific (see [InvariantSet.PRESETS]'s "Tech stack" preset).
 */
enum class InvariantCategory {
    SAFETY, MEDICAL, EQUIPMENT, SCHEDULE, NUTRITION, METHODOLOGY, SCOPE, TECH
}

/**
 * Day 14: a hard rule the agent is never allowed to break, as opposed to
 * [com.example.geminichat.agent.profile.UserProfile.constraints] (a preference the model is
 * merely asked to respect) or a [com.example.geminichat.agent.task.TaskState] transition (which
 * the state machine can reject but which is never phrased as a refusal to the user).
 *
 * An invariant is enforced two ways at once (the "medium" depth chosen for Day 14):
 * - [statement]/[rationale]/[alternative] are rendered into the agent's system instruction (see
 *   [InvariantRenderer]) so the model itself reasons about them on every turn;
 * - [triggers] let [InvariantGuard] catch an obviously conflicting request *before* the model is
 *   ever called, refusing deterministically instead of hoping the model self-polices.
 *
 * @property id stable identifier, shown to the user in a refusal (e.g. "home-equipment-only").
 * @property category display-only grouping, never interpreted by guard/renderer logic.
 * @property statement the rule itself, in plain language ("Only bodyweight/home equipment").
 * @property rationale why the rule exists — shown as the "why" in a refusal.
 * @property alternative what the agent should offer instead of just refusing; empty means none.
 * @property triggers regex patterns (matched case-insensitively against the user's newest
 *   message only) that make this invariant deterministically enforceable by [InvariantGuard];
 *   an invariant with no triggers still renders into the prompt but can only be caught by the
 *   model's own reasoning, not by the guard.
 * @property enabled whether this invariant is currently active; ignored (always effectively
 *   `true`) when [locked].
 * @property locked whether this is core, non-negotiable state: [InvariantRules] refuses to
 *   remove it or turn it off, from the UI or from any suggested change.
 */
@Serializable
data class Invariant(
    val id: String,
    val category: InvariantCategory,
    val statement: String,
    val rationale: String,
    val alternative: String = "",
    val triggers: List<String> = emptyList(),
    val enabled: Boolean = true,
    val locked: Boolean = false
) {
    /**
     * Compiled [triggers] as case-insensitive [Regex]es, skipping any pattern that fails to
     * compile (a hand-edited invariants.json with a broken regex degrades that one trigger
     * instead of crashing the app or disabling the whole invariant).
     */
    fun matchers(): List<Regex> = triggers.mapNotNull { pattern ->
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
    }
}
