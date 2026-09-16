package com.example.geminichat.agent.profile

/**
 * Day 12: renders [UserProfile] into a single labeled prompt block — "personalization
 * directives", distinct from the Day 11 memory blocks rendered by
 * [com.example.geminichat.agent.memory.MemoryAssembler]. [com.example.geminichat.agent.LlmAgent]
 * appends this block to the agent's [com.example.geminichat.agent.AgentConfig.systemInstruction]
 * (not the user-turn prompt) so it carries the strongest instruction-following weight and is
 * never mistaken for a fact the model itself observed.
 *
 * Deliberately deterministic (no LLM call): a profile is just data the user already committed
 * to, so rendering it needs no interpretation step.
 */
object ProfileRenderer {

    private const val HEADER = "User profile (personalization directives — follow these when answering):"

    /** Renders [profile] as a block, or an empty string when [profile] has nothing set (see
     * [UserProfile.isEmpty]) — in which case the agent's prompt is unchanged from before Day 12. */
    fun render(profile: UserProfile): String {
        if (profile.isEmpty()) return ""

        val lines = mutableListOf<String>()
        if (profile.displayName.isNotBlank()) lines += "- Address the user as: ${profile.displayName}"
        if (profile.about.isNotBlank()) lines += "- About the user: ${profile.about}"
        if (profile.language.isNotBlank()) lines += "- Answer in this language: ${profile.language}"
        profile.expertise?.let { lines += "- User's expertise level: ${it.name.lowercase()}" }
        if (profile.tone.isNotBlank()) lines += "- Tone: ${profile.tone}"
        if (profile.format.isNotBlank()) lines += "- Preferred answer format: ${profile.format}"
        profile.maxAnswerSentences?.let {
            lines += "- Keep answers to about $it sentences or fewer unless the user asks for more detail"
        }
        if (profile.constraints.isNotEmpty()) {
            lines += "- Hard constraints, always respect these:"
            profile.constraints.forEach { constraint -> lines += "  - $constraint" }
        }
        if (profile.notes.isNotBlank()) lines += "- Additional notes: ${profile.notes}"

        if (lines.isEmpty()) return ""
        return "$HEADER\n${lines.joinToString("\n")}"
    }
}
