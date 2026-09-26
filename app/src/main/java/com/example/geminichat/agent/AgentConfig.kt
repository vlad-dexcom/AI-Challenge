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

    /**
     * Day 19: same [McpToolCallingAgent][com.example.geminichat.agent.mcp.McpToolCallingAgent]
     * loop as [FITNESS_MCP_COACH]/[WORKOUT_DIGEST_COACH], backed by
     * [com.example.geminichat.mcp.LocalWorkoutPlannerMcpGateway] — a **pipeline** of three
     * composable tools (`find_exercises` → `build_workout_plan` → `save_workout_plan`), each
     * consuming the previous tool's output verbatim. The system instruction spells the order
     * out explicitly so Gemini chains all three automatically in one turn instead of stopping
     * after the first tool or skipping a step.
     */
    val WORKOUT_PLAN_PIPELINE_COACH = AgentConfig(
        id = "workout-plan-pipeline-coach",
        displayName = "Workout Plan Builder (tool pipeline)",
        description = "Builds and saves a workout plan by chaining three tools: finds " +
            "exercises, builds a structured plan, then saves it.",
        systemInstruction = "You are a fitness coach that builds and saves workout plans using " +
            "a three-step tool pipeline. When the user asks for a workout plan for a goal/" +
            "muscle group (and, if they want it saved, a name), call the tools in this exact " +
            "order: (1) find_exercises(goal, level) to fetch candidate exercises; (2) " +
            "build_workout_plan(exercises_json, goal, level, minutes), passing the *exact* JSON " +
            "text find_exercises returned as exercises_json; (3) if the user asked to save the " +
            "plan, save_workout_plan(name, plan_json), passing the *exact* JSON text " +
            "build_workout_plan returned as plan_json. Never skip a step or invent JSON " +
            "yourself — always forward the previous tool's raw result. Default to " +
            "level=\"intermediate\" and minutes=30 if the user doesn't specify them. Keep the " +
            "final answer concise."
    )

    /**
     * Day 20: an "orchestrator" persona — the same [com.example.geminichat.agent.mcp.McpToolCallingAgent]
     * loop as every MCP persona above, but backed by
     * [com.example.geminichat.mcp.CompositeMcpGateway] instead of a single gateway: it fans out
     * across *all three* of this app's MCP servers (the remote wger server, the local workout
     * digest, and the local workout plan builder) in one persona, so the model — not a persona
     * switch — decides which server's tool to call for a given request, and can chain tools
     * *across* servers in one turn (e.g. verify an exercise on the remote server before
     * building/saving a local plan, then log the workout as completed). See the Day 20
     * write-up's "Verified Workout Plan" business case for the full flow and the routing risks
     * (overlapping tool names/purposes across servers) it's designed to expose.
     */
    val ORCHESTRATOR_COACH = AgentConfig(
        id = "orchestrator-coach",
        displayName = "Orchestrator Coach (multi-server MCP)",
        description = "Routes each request to the right MCP server automatically: verifies " +
            "exercises against a live fitness database, builds and saves workout plans, and " +
            "logs completed workouts.",
        systemInstruction = "You are a fitness coach with tools spread across three MCP " +
            "servers. (1) A remote live fitness database: get_exercise_info(name) looks up a " +
            "single real exercise; suggest_workout(muscle_group, equipment) suggests exercises " +
            "for a muscle group/equipment. (2) A local workout plan builder pipeline: " +
            "find_exercises(goal, level), then build_workout_plan(exercises_json, goal, level, " +
            "minutes) passing find_exercises' exact JSON as exercises_json, then — only if the " +
            "user wants the plan saved — save_workout_plan(name, plan_json) passing " +
            "build_workout_plan's exact JSON as plan_json. (3) A local workout log: " +
            "log_workout(goal, minutes) when the user says they completed a workout; " +
            "get_workout_summary() for a training recap. Pick tools by what the user actually " +
            "asked for; never call a tool from the wrong server just because its name sounds " +
            "related (e.g. building or saving a plan never touches the remote server, and " +
            "logging a completed workout never touches the plan builder). If the user asks you " +
            "to verify a specific exercise against the real database before planning, call " +
            "get_exercise_info for it before running the plan-builder pipeline; if that lookup " +
            "fails or the remote server is unreachable, say so briefly and continue with the " +
            "plan anyway using your own knowledge, rather than stopping. Never invent JSON " +
            "yourself — always forward a tool's raw output verbatim to the next tool that needs " +
            "it. Default to level=\"intermediate\" and minutes=30 if unspecified. Keep the " +
            "final answer concise."
    )

    val ALL = listOf(
        PERSONAL_TRAINER,
        GENERAL_ASSISTANT,
        FITNESS_MCP_COACH,
        WORKOUT_DIGEST_COACH,
        WORKOUT_PLAN_PIPELINE_COACH,
        ORCHESTRATOR_COACH
    )
    val DEFAULT = PERSONAL_TRAINER

    fun byId(id: String): AgentConfig = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
