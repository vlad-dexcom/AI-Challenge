package com.example.geminichat.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException

/**
 * [McpGateway] implementation on top of the official MCP Kotlin SDK
 * (`io.modelcontextprotocol:kotlin-sdk-client`), using the Streamable HTTP transport (the SDK's
 * recommended network transport — stdio only makes sense for a locally-spawned server process,
 * which an Android app cannot do for a remote MCP server like DeepWiki).
 *
 * One instance owns exactly one connection: call [connect] once, then [listTools] any number of
 * times, and [close] when done (or before reconnecting to a different URL).
 */
class KotlinSdkMcpGateway : McpGateway {

    private val httpClient = HttpClient(OkHttp) {
        install(SSE)
        install(HttpTimeout) {
            requestTimeoutMillis = McpConfig.REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = McpConfig.CONNECT_TIMEOUT_MILLIS
        }
    }

    private var client: Client? = null

    override suspend fun connect(serverUrl: String): McpServerInfo {
        close()

        val mcpClient = Client(
            clientInfo = Implementation(name = McpConfig.CLIENT_NAME, version = McpConfig.CLIENT_VERSION)
        )

        try {
            mcpClient.connect(StreamableHttpClientTransport(client = httpClient, url = serverUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            McpCallLog.record("connect($serverUrl) → FAILED: ${e.message}", isError = true)
            throw McpConnectionException("Failed to connect to MCP server at $serverUrl: ${e.message}", e)
        }

        client = mcpClient

        val serverInfo = mcpClient.serverVersion
            ?: run {
                McpCallLog.record("connect($serverUrl) → FAILED: server did not report its identity", isError = true)
                throw McpConnectionException("MCP server at $serverUrl did not report its identity.")
            }
        val capabilities = mcpClient.serverCapabilities

        McpCallLog.record("connect($serverUrl) → ${serverInfo.name} ${serverInfo.version}")

        return McpServerInfo(
            name = serverInfo.name,
            version = serverInfo.version,
            capabilities = buildList {
                if (capabilities?.tools != null) add("tools")
                if (capabilities?.resources != null) add("resources")
                if (capabilities?.prompts != null) add("prompts")
                if (capabilities?.logging != null) add("logging")
                if (capabilities?.completions != null) add("completions")
            },
            instructions = mcpClient.serverInstructions
        )
    }

    override suspend fun listTools(): List<McpToolInfo> {
        val mcpClient = client ?: throw McpConnectionException("Not connected: call connect() first.")

        val tools = mutableListOf<McpToolInfo>()
        var cursor: String? = null
        try {
            do {
                val page = mcpClient.listTools(
                    ListToolsRequest(params = cursor?.let { PaginatedRequestParams(cursor = it) })
                )
                tools += page.tools.map(McpToolMapper::toDomain)
                cursor = page.nextCursor
            } while (cursor != null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            McpCallLog.record("listTools() → FAILED: ${e.message}", isError = true)
            throw McpConnectionException("Failed to list tools: ${e.message}", e)
        }
        McpCallLog.record("listTools() → ${tools.size} tool(s): ${tools.joinToString(", ") { it.name }}")
        return tools
    }

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolCallResult {
        val mcpClient = client ?: throw McpConnectionException("Not connected: call connect() first.")

        val result = try {
            mcpClient.callTool(name = name, arguments = arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            McpCallLog.record("callTool($name, $arguments) → FAILED: ${e.message}", isError = true)
            throw McpToolCallException("Failed to call tool '$name': ${e.message}", e)
        }

        val text = result.content
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text }
        val isError = result.isError ?: false

        McpCallLog.record(
            "callTool($name, $arguments) → ${if (isError) "ERROR" else "ok"}: ${text.take(120)}${if (text.length > 120) "…" else ""}",
            isError = isError
        )

        return McpToolCallResult(text = text, isError = isError)
    }

    override suspend fun close() {
        try {
            client?.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort: the connection may already be dropped; nothing more to do.
        } finally {
            client = null
        }
    }
}
