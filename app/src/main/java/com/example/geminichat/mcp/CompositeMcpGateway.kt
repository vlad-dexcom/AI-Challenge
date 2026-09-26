package com.example.geminichat.mcp

import kotlinx.coroutines.CancellationException

/**
 * One MCP server the [CompositeMcpGateway] can route to: a stable [name] used only for logging
 * and error messages, the [serverUrl] passed to [gateway]'s own [McpGateway.connect] (a real
 * HTTP URL for a remote server, or one of the local gateways' `local://...` marker), and the
 * [gateway] itself.
 */
data class NamedMcpGateway(
    val name: String,
    val serverUrl: String,
    val gateway: McpGateway
)

/**
 * Day 20: an [McpGateway] that fans a *single* function-calling loop
 * ([com.example.geminichat.agent.mcp.McpToolCallingAgent]) out across several real MCP
 * servers/gateways ([members]) instead of one. This is the "orchestration" exercise: the model
 * — not a persona switch — decides which server's tool to call for a given request, and can
 * chain tools *across* servers within one turn, while [McpToolCallingAgent]'s loop stays
 * completely unchanged (same trick as Day 19's [LocalWorkoutPlannerMcpGateway]: a new
 * contract-compatible gateway, not a new loop).
 *
 * [connect] hands off to every member's own [McpGateway.connect] using *that member's own*
 * [NamedMcpGateway.serverUrl] — the [serverUrl] this method receives is only used for the
 * top-level [McpCallLog] entry; routing is fully determined by [members] at construction time.
 * A member that fails to connect (e.g. the remote wger server is unreachable) does not fail the
 * whole composite: it's recorded as unavailable and simply contributes zero tools, so the
 * remaining members' tools stay usable (graceful degradation — see the Day 20 "Verified
 * Workout Plan" business case).
 *
 * [listTools] aggregates every *reachable* member's tools and remembers which member declared
 * each tool name, so [callTool] can dispatch to the right one. Tool names are assumed unique
 * across members (true for this app's servers); a duplicate simply shadows the earlier member's
 * tool of the same name and is logged as a warning.
 */
class CompositeMcpGateway(private val members: List<NamedMcpGateway>) : McpGateway {

    /** Names of [members] whose [McpGateway.connect]/[McpGateway.listTools] most recently failed. */
    private val unavailable = mutableSetOf<String>()

    /** tool name -> the member that declared it, as of the last [listTools] call. */
    private val toolOwners = mutableMapOf<String, NamedMcpGateway>()

    override suspend fun connect(serverUrl: String): McpServerInfo {
        unavailable.clear()
        toolOwners.clear()

        val connectedNames = mutableListOf<String>()
        for (member in members) {
            try {
                member.gateway.connect(member.serverUrl)
                connectedNames += member.name
            } catch (e: CancellationException) {
                throw e
            } catch (e: McpConnectionException) {
                unavailable += member.name
                McpCallLog.record(
                    "connect(${member.serverUrl}) → FAILED, \"${member.name}\" unavailable: ${e.message}",
                    isError = true
                )
            }
        }

        if (connectedNames.isEmpty()) {
            throw McpConnectionException(
                "None of the orchestrated MCP servers could be reached: " +
                    members.joinToString(", ") { it.name }
            )
        }

        val info = McpServerInfo(
            name = "orchestrator(${connectedNames.joinToString(", ")})",
            version = "1.0.0",
            capabilities = listOf("tools"),
            instructions = null
        )
        McpCallLog.record("connect($serverUrl) → ${info.name}")
        return info
    }

    override suspend fun listTools(): List<McpToolInfo> {
        val tools = mutableListOf<McpToolInfo>()
        for (member in members) {
            if (member.name in unavailable) continue
            val memberTools = try {
                member.gateway.listTools()
            } catch (e: CancellationException) {
                throw e
            } catch (e: McpConnectionException) {
                unavailable += member.name
                McpCallLog.record(
                    "listTools() → FAILED, \"${member.name}\" unavailable: ${e.message}",
                    isError = true
                )
                continue
            }
            memberTools.forEach { tool ->
                if (toolOwners.containsKey(tool.name)) {
                    McpCallLog.record(
                        "listTools() → \"${tool.name}\" from \"${member.name}\" shadows an " +
                            "earlier server's tool of the same name",
                        isError = true
                    )
                }
                toolOwners[tool.name] = member
            }
            tools += memberTools
        }
        McpCallLog.record(
            "listTools() → ${tools.size} tool(s) across ${members.size - unavailable.size} " +
                "server(s): ${tools.joinToString(", ") { it.name }}"
        )
        return tools
    }

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolCallResult {
        val owner = toolOwners[name]
            ?: throw McpToolCallException(
                "Unknown tool \"$name\" — not declared by any connected server."
            )
        return owner.gateway.callTool(name, arguments)
    }

    override suspend fun close() {
        members.forEach { it.gateway.close() }
    }
}
