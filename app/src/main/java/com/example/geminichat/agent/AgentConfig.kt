package com.example.geminichat.agent

import com.example.geminichat.GeminiApiClient

/**
 * Everything that defines *what an agent is*: its persona (system instruction), the model
 * it talks to, and its generation parameters. An [Agent] is this configuration plus the
 * behavior that uses it — separating the two lets the same behavior ([com.example.geminichat.agent.LlmAgent])
 * back many different personas without duplicating logic.
 */
data class AgentConfig(
    val id: String,
    val displayName: String,
    val description: String,
    val systemInstruction: String,
    val model: String = GeminiApiClient.DEFAULT_MODEL,
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null
)

/**
 * Curated set of ready-to-use agent configurations. Adding a new agent persona is just
 * adding an entry here — no other code needs to change.
 */
object AgentCatalog {
    val PERSONAL_TRAINER = AgentConfig(
        id = "personal-trainer",
        displayName = "Personal Trainer",
        description = "Your fitness coach — workouts, technique, recovery, and training plans.",
        // Day 14: topic scope used to be hardcoded here ("ONLY discuss physical training
        // topics... decline anything else") — a pre-Day-14 remnant that both duplicated and
        // contradicted the "fitness-scope" invariant (which correctly includes nutrition) and
        // made every other scope invariant (e.g. the "Tech stack" preset) untestable, since the
        // model refused any non-fitness topic unconditionally, invariants notwithstanding.
        // Scope is now owned entirely by the invariant layer (see InvariantSet.DEFAULTS'
        // "fitness-scope") so it's user-editable/toggleable like everything else in Day 14.
        systemInstruction = "You are a Personal Trainer: a friendly, knowledgeable fitness " +
            "coach who helps with workouts, technique, recovery, and nutrition. Keep answers " +
            "concise and actionable."
    )

    val GENERAL_ASSISTANT = AgentConfig(
        id = "general-assistant",
        displayName = "General Assistant",
        description = "A helpful, general-purpose assistant with no topic restrictions.",
        systemInstruction = "You are a helpful, concise general-purpose assistant."
    )

    val ALL = listOf(PERSONAL_TRAINER, GENERAL_ASSISTANT)
    val DEFAULT = PERSONAL_TRAINER

    fun byId(id: String): AgentConfig = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
