package com.example.geminichat.mcp

import com.example.geminichat.BuildConfig

/** Static configuration for the MCP client (Day 16: connection + tool discovery only). */
object McpConfig {
    /**
     * DeepWiki's public remote MCP server (Streamable HTTP, HTTPS, no API key) — used as the
     * Day 16 reference server per the week plan; editable in the UI so Day 17 can point at a
     * locally-hosted fitness MCP server instead. See https://docs.devin.ai/work-with-devin/deepwiki-mcp
     * for the tools it exposes (read_wiki_structure, read_wiki_contents, ask_question).
     */
    const val DEFAULT_SERVER_URL = "https://mcp.deepwiki.com/mcp"

    /**
     * Day 17: our own MCP server — a Firebase Cloud Function wrapping the public wger.de
     * fitness-exercise API, exposing `get_exercise_info` and `suggest_workout` tools (see
     * `mcp-server/functions/src/index.ts`). Used by [com.example.geminichat.agent.mcp.McpToolCallingAgent]
     * so the "Fitness Coach (MCP)" persona in [com.example.geminichat.agent.AgentCatalog] calls
     * real tools instead of just talking about exercises from the model's own knowledge.
     */
    const val FITNESS_SERVER_URL = "https://us-central1-ai-challenge-mcp.cloudfunctions.net/mcp"

    const val CLIENT_NAME = "personal-trainer-android"
    val CLIENT_VERSION: String = BuildConfig.VERSION_NAME

    const val CONNECT_TIMEOUT_MILLIS = 15_000L
    const val REQUEST_TIMEOUT_MILLIS = 30_000L
}
