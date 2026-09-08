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
        systemInstruction = "You are a Personal Trainer. You ONLY discuss physical training " +
            "topics: workouts, exercises, training programs, technique, warm-up/cool-down, " +
            "recovery, and general fitness safety. If the user asks about anything else, " +
            "politely decline and steer the conversation back to physical training instead " +
            "of answering the unrelated request. Keep answers concise and actionable."
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
