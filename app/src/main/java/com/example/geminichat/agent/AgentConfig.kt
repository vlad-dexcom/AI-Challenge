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

    /**
     * Day 17: same persona as [PERSONAL_TRAINER], but handled by
     * [com.example.geminichat.agent.mcp.McpToolCallingAgent] instead of [LlmAgent] — it has
     * real function-calling tools (`get_exercise_info`, `suggest_workout`) backed by our own
     * MCP server (a Firebase Cloud Function wrapping the wger.de fitness API, see
     * `mcp-server/functions`), so it can look up real exercises/build real workout plans
     * instead of relying only on the model's own knowledge. See [ChatViewModel]'s agent
     * construction for where the [id] is used to pick [McpToolCallingAgent] over [LlmAgent].
     */
    val FITNESS_MCP_COACH = AgentConfig(
        id = "fitness-mcp-coach",
        displayName = "Fitness Coach (MCP tools)",
        description = "Personal Trainer with live tool access: looks up real exercises and " +
            "builds real workout plans via our MCP fitness server.",
        systemInstruction = "You are a Personal Trainer with access to tools that look up real " +
            "exercises and build workout plans from a live fitness database. Prefer calling " +
            "get_exercise_info or suggest_workout over guessing when the user asks about a " +
            "specific exercise or wants a workout plan. Keep answers concise and actionable."
    )

    /**
     * Day 18: same [McpToolCallingAgent][com.example.geminichat.agent.mcp.McpToolCallingAgent]
     * loop as [FITNESS_MCP_COACH], but backed by
     * [com.example.geminichat.mcp.LocalWorkoutMcpGateway] instead of a remote server — its
     * `log_workout`/`get_workout_summary` tools read/write on-device JSON storage, and the
     * summary itself is refreshed periodically in the background by
     * [com.example.geminichat.agent.workout.WorkoutDigestWorker] (scheduled via WorkManager, see
     * [com.example.geminichat.agent.workout.WorkoutDigestScheduler]), not recomputed per request.
     */
    val WORKOUT_DIGEST_COACH = AgentConfig(
        id = "workout-digest-coach",
        displayName = "Workout Digest (scheduled)",
        description = "Logs your workouts and reports a periodic summary, aggregated in the " +
            "background on a schedule rather than computed on the spot.",
        systemInstruction = "You are a fitness coach that tracks the user's logged workouts. " +
            "Call log_workout when the user mentions completing a workout (goal/muscle group " +
            "and minutes spent). Call get_workout_summary when they ask how their training is " +
            "going, for a recap, or for a weekly summary — it returns a periodically " +
            "aggregated summary, not a live recalculation, so mention it may be a little behind " +
            "the very latest logged workout. Keep answers concise and actionable."
    )

    val ALL = listOf(PERSONAL_TRAINER, GENERAL_ASSISTANT, FITNESS_MCP_COACH, WORKOUT_DIGEST_COACH)
    val DEFAULT = PERSONAL_TRAINER

    fun byId(id: String): AgentConfig = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
