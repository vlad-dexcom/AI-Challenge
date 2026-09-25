package com.example.geminichat.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Day 17: a single, brief, human-readable record of one MCP protocol call (`initialize`,
 * `tools/list`, or `tools/call`), for display on [com.example.geminichat.mcp.McpScreen]'s new
 * "Call log" section — complements the existing Logcat output (tag "MCP") with something
 * visible directly in the app, without digging through `adb logcat`.
 */
data class McpCallLogEntry(
    val timestampMs: Long,
    val summary: String,
    val isError: Boolean
)

/**
 * App-wide, in-memory log of every MCP call made by any [KotlinSdkMcpGateway] instance —
 * both the one owned by [McpViewModel]/[McpScreen] (manual connect/browse) and the one owned by
 * [com.example.geminichat.ChatViewModel] (the real tool calls made while chatting with the
 * "Fitness Coach (MCP tools)" persona — see [com.example.geminichat.agent.mcp.McpToolCallingAgent]).
 * A single shared, capped-size log (rather than one per gateway) so [McpScreen] shows tool calls
 * made from the chat screen too, which is exactly what needs to be visible to verify Day 17's
 * "agent calls the MCP tool" behavior end-to-end.
 */
object McpCallLog {
    private const val MAX_ENTRIES = 50

    private val _entries = MutableStateFlow<List<McpCallLogEntry>>(emptyList())
    val entries: StateFlow<List<McpCallLogEntry>> = _entries

    fun record(summary: String, isError: Boolean = false) {
        _entries.update { current ->
            (current + McpCallLogEntry(System.currentTimeMillis(), summary, isError)).takeLast(MAX_ENTRIES)
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
