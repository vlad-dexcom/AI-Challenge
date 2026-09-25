package com.example.geminichat.mcp

import kotlinx.serialization.json.JsonObject

/**
 * Domain models for the MCP (Model Context Protocol) client layer, deliberately independent
 * of the `io.modelcontextprotocol:kotlin-sdk-client` types: only [KotlinSdkMcpGateway] (and
 * [McpToolMapper]) know about the SDK's `Tool`/`Implementation`/`ServerCapabilities` classes.
 * The rest of the app (UI, agent, tests) depends only on these plain data classes, so a future
 * transport swap (e.g. stdio for a locally-spawned server) or SDK API change stays contained.
 */

/** A single named/typed input parameter of an [McpToolInfo], parsed from its JSON Schema. */
data class McpToolParam(
    val name: String,
    val type: String?,
    val description: String?,
    val required: Boolean
)

/** One tool exposed by an MCP server, as returned by `tools/list`. */
data class McpToolInfo(
    val name: String,
    val title: String?,
    val description: String?,
    val parameters: List<McpToolParam>,
    /**
     * Day 17: the tool's raw JSON-Schema `inputSchema` (properties/required/enum/etc.),
     * unparsed. [McpToolParam] flattens this into a name/type/description/required list for
     * display, which loses information (e.g. `enum` values) an LLM needs to call the tool
     * accurately. Callers that hand tools to Gemini's function-calling (see
     * `agent/mcp/McpToolCallingAgent`) should use this instead of reconstructing a schema from
     * [parameters]. Null for tools whose server didn't declare an input schema.
     */
    val rawInputSchema: JsonObject? = null
)

/** Identity/capabilities of the MCP server reported during the `initialize` handshake. */
data class McpServerInfo(
    val name: String,
    val version: String,
    /** e.g. "tools", "resources", "prompts" — whichever capability blocks the server declared. */
    val capabilities: List<String>,
    val instructions: String?
)

/** Successful outcome of [McpGateway.connect] + [McpGateway.listTools]: what the UI renders. */
data class McpConnectionSnapshot(
    val serverUrl: String,
    val server: McpServerInfo,
    val tools: List<McpToolInfo>,
    val latencyMs: Long
)

/** Thrown by [McpGateway] implementations for any connection/handshake/listing failure. */
class McpConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Day 17: outcome of [McpGateway.callTool] — the tool's textual output (an MCP tool's
 * `content` blocks are usually one or more text parts; this is those parts joined together)
 * plus whether the server flagged the call as failed (`isError`), so callers can surface a
 * tool failure to the model/user instead of treating it as a normal result.
 */
data class McpToolCallResult(
    val text: String,
    val isError: Boolean
)

/** Thrown by [McpGateway.callTool] for any network/protocol failure invoking a tool. */
class McpToolCallException(message: String, cause: Throwable? = null) : Exception(message, cause)
