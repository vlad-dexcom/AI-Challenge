package com.example.geminichat.mcp

/**
 * Transport-agnostic contract for talking to a single MCP server: connect (handshake) and
 * discover its tools. Kept separate from any specific SDK/transport so [McpViewModel] (and
 * later, Day 17+'s tool-invocation code in `agent/`) can be tested against a fake, and so a
 * future transport swap only touches the implementation, not callers.
 */
interface McpGateway {
    /**
     * Performs the MCP `initialize` handshake against [serverUrl].
     * @throws McpConnectionException on any network/protocol failure.
     */
    suspend fun connect(serverUrl: String): McpServerInfo

    /**
     * Lists every tool the connected server exposes (`tools/list`, following pagination via
     * `nextCursor` until exhausted). Must be called after a successful [connect].
     * @throws McpConnectionException on any network/protocol failure.
     */
    suspend fun listTools(): List<McpToolInfo>

    /**
     * Day 17: invokes tool [name] on the connected server (`tools/call`) with [arguments],
     * returning its result as plain text. Must be called after a successful [connect].
     * @throws McpToolCallException on any network/protocol failure.
     */
    suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolCallResult

    /** Closes the underlying connection, if any. Safe to call multiple times. */
    suspend fun close()
}
