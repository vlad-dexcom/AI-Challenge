package com.example.geminichat.mcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end test of [KotlinSdkMcpGateway] against a *real* MCP server (kotlin-sdk-server +
 * an embedded Ktor CIO engine) running in-process on a random local port, instead of the
 * public DeepWiki endpoint — deterministic and network-independent, while still exercising the
 * real `initialize` handshake and `tools/list` request/response over Streamable HTTP.
 */
class KotlinSdkMcpGatewayIntegrationTest {

    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private var serverUrl: String = ""

    @Before
    fun startTestServer() {
        val mcpServer = Server(
            serverInfo = Implementation(name = "test-fitness-server", version = "0.1.0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()))
        )

        mcpServer.addTool(
            name = "health_check",
            description = "Diagnostic tool with no arguments.",
        ) { CallToolResult(content = listOf(TextContent("ok"))) }

        mcpServer.addTool(
            name = "get_exercise_info",
            description = "Look up a fitness exercise by name.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Exercise name, e.g. 'bench press'.")
                    })
                },
                required = listOf("name")
            )
        ) { request ->
            val name = request.arguments?.get("name")?.toString() ?: "unknown"
            CallToolResult(content = listOf(TextContent("Info about $name")))
        }

        val embedded = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            mcpStreamableHttp(enableDnsRebindingProtection = false) { mcpServer }
        }
        embedded.start(wait = false)
        server = embedded

        val port = runBlocking { embedded.engine.resolvedConnectors().first().port }
        serverUrl = "http://127.0.0.1:$port/mcp"
    }

    @After
    fun stopTestServer() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    @Test
    fun `connect performs the handshake and reports server identity`() = runBlocking {
        val gateway = KotlinSdkMcpGateway()

        val info = gateway.connect(serverUrl)

        assertEquals("test-fitness-server", info.name)
        assertEquals("0.1.0", info.version)
        assertTrue(info.capabilities.contains("tools"))

        gateway.close()
    }

    @Test
    fun `listTools returns exactly the tools registered on the server`() = runBlocking {
        val gateway = KotlinSdkMcpGateway()
        gateway.connect(serverUrl)

        val tools = gateway.listTools()

        assertEquals(setOf("health_check", "get_exercise_info"), tools.map { it.name }.toSet())
        val exerciseTool = tools.first { it.name == "get_exercise_info" }
        assertEquals(1, exerciseTool.parameters.size)
        assertEquals("name", exerciseTool.parameters.single().name)
        assertTrue(exerciseTool.parameters.single().required)

        gateway.close()
    }
}
