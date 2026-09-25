package com.example.geminichat.mcp

import com.example.geminichat.BuildConfig

/** Static configuration for the MCP client. */
object McpConfig {
    /**
     * Our own MCP server — a Firebase Cloud Function wrapping the public wger.de
     * fitness-exercise API, exposing `get_exercise_info` and `suggest_workout` tools (see
     * `mcp-server/functions/src/index.ts`). Used both as the default, editable server URL on the
     * "MCP" screen ([McpScreen]) and by [com.example.geminichat.agent.mcp.McpToolCallingAgent] so
     * the "Fitness Coach (MCP)" persona in [com.example.geminichat.agent.AgentCatalog] calls real
     * tools instead of just talking about exercises from the model's own knowledge.
     *
     * Day 16 originally defaulted this screen to the public DeepWiki MCP server
     * (`https://mcp.deepwiki.com/mcp`) purely to learn the protocol against a third-party
     * example; now that Day 17 ships a real, working MCP server of our own, there is no reason
     * to keep pointing the default connection at someone else's demo server.
     */
    const val FITNESS_SERVER_URL = "https://us-central1-ai-challenge-mcp.cloudfunctions.net/mcp"

    const val CLIENT_NAME = "personal-trainer-android"
    val CLIENT_VERSION: String = BuildConfig.VERSION_NAME

    const val CONNECT_TIMEOUT_MILLIS = 15_000L
    const val REQUEST_TIMEOUT_MILLIS = 30_000L
}
