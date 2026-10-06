package com.example.geminichat.agent.invariant

/**
 * Day 14: one [invariant] whose [matchedText] was found in the user's newest message by
 * [InvariantGuard.check].
 */
data class InvariantConflict(val invariant: Invariant, val matchedText: String)

/**
 * Day 14's deterministic pre-check: catches an obviously conflicting request **before** the
 * model is ever called (see [com.example.geminichat.agent.LlmAgent.handle]), so an invariant
 * violation is refused by code, not by hoping the model self-polices the prompt block rendered
 * by [InvariantRenderer]. This is the "medium" enforcement depth chosen for Day 14: no
 * post-hoc LLM check of the model's answer, only a pre-check of the user's request.
 *
 * Deliberately checks only the new user message, not the history or any prior reply — an
 * invariant restricts what the agent is willing to do *now*, not what was said earlier in the
 * conversation.
 */
object InvariantGuard {

    /** Normalizes [text] so trigger matching isn't thrown off by case or the ё/е spelling
     * variant common in Russian informal text (triggers in [InvariantSet.DEFAULTS] are written
     * using "е"). */
    private fun normalize(text: String): String = text.lowercase().replace('ё', 'е')

    /** Every enabled invariant in [set] whose [Invariant.triggers] match somewhere in
     * [userMessage], in [set] order. A request can conflict with more than one invariant at
     * once (e.g. a barbell request that also asks for 6 sessions a week) — all conflicts are
     * returned so the refusal can name every one of them. */
    fun check(set: InvariantSet, userMessage: String): List<InvariantConflict> {
        val normalized = normalize(userMessage)
        return set.enabledInvariants.mapNotNull { invariant ->
            invariant.matchers().firstNotNullOfOrNull { regex ->
                regex.find(normalized)?.value
            }?.let { matched -> InvariantConflict(invariant, matched) }
        }
    }

    /** Builds the deterministic, user-facing refusal text for [conflicts] (must be non-empty).
     * Names every conflicting invariant's id/rule/rationale/alternative, whether it's locked
     * (and therefore can never be turned off) or editable (and where to turn it off), and
     * closes with a reminder that a misfire can just be rephrased — a guard against a
     * regex-trigger false positive leaving the user stuck. */
    fun refusalText(conflicts: List<InvariantConflict>): String {
        require(conflicts.isNotEmpty()) { "refusalText requires at least one conflict" }

        val blocks = conflicts.joinToString("\n\n") { conflict ->
            val invariant = conflict.invariant
            buildString {
                append("⛔ Не могу это предложить: запрос нарушает инвариант **`${invariant.id}`** " +
                    "(${invariant.category.name}).\n\n")
                append("**Правило:** ${invariant.statement}\n")
                append("**Почему:** ${invariant.rationale}")
                if (invariant.alternative.isNotBlank()) {
                    append("\n**Что можно вместо этого:** ${invariant.alternative}")
                }
                append(
                    if (invariant.locked) {
                        "\n\nПравило закреплено (🔒) и не отключается."
                    } else {
                        "\n\nПравило можно выключить в Settings → Инварианты."
                    }
                )
            }
        }
        return "$blocks\n\nЕсли я неправильно понял запрос — переформулируй его."
    }
}
