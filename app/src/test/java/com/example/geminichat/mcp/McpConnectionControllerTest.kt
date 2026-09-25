package com.example.geminichat.mcp

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake [McpGateway] so [McpConnectionController] can be tested with no network/SDK. */
private class FakeMcpGateway(
    private val serverInfo: McpServerInfo? = null,
    private val tools: List<McpToolInfo> = emptyList(),
    private val failure: McpConnectionException? = null
) : McpGateway {
    var connectCallCount = 0
        private set
    var closeCallCount = 0
        private set
    var lastConnectedUrl: String? = null
        private set

    override suspend fun connect(serverUrl: String): McpServerInfo {
        connectCallCount++
        lastConnectedUrl = serverUrl
        failure?.let { throw it }
        return serverInfo ?: error("no serverInfo configured for this fake")
    }

    override suspend fun listTools(): List<McpToolInfo> = tools

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolCallResult =
        error("callTool not used by these tests")

    override suspend fun close() {
        closeCallCount++
    }
}

class McpConnectionControllerTest {

    private val fakeServer = McpServerInfo(
        name = "fitness-mcp",
        version = "1.0.0",
        capabilities = listOf("tools"),
        instructions = "Ask about exercises and workouts."
    )
    private val fakeTools = listOf(
        McpToolInfo("get_exercise_info", title = null, description = "Look up an exercise", parameters = emptyList()),
        McpToolInfo("suggest_workout", title = "Suggest a workout", description = "Suggest a workout plan", parameters = emptyList())
    )

    @Test
    fun `initial state is idle with the default server url`() {
        val controller = McpConnectionController(FakeMcpGateway())

        val state = controller.state.value

        assertEquals(McpConfig.FITNESS_SERVER_URL, state.serverUrl)
        assertEquals(McpStatus.Idle, state.status)
    }

    @Test
    fun `connect transitions to Connected with server info and tools on success`() = runTest {
        val gateway = FakeMcpGateway(serverInfo = fakeServer, tools = fakeTools)
        val controller = McpConnectionController(gateway)
        controller.onUrlChange("https://example.com/mcp")

        controller.connect()

        val status = controller.state.value.status
        assertTrue(status is McpStatus.Connected)
        val connected = status as McpStatus.Connected
        assertEquals(fakeServer, connected.snapshot.server)
        assertEquals(fakeTools, connected.snapshot.tools)
        assertEquals("https://example.com/mcp", gateway.lastConnectedUrl)
    }

    @Test
    fun `connect transitions to Error when the gateway throws`() = runTest {
        val gateway = FakeMcpGateway(failure = McpConnectionException("boom"))
        val controller = McpConnectionController(gateway)

        controller.connect()

        val status = controller.state.value.status
        assertTrue(status is McpStatus.Error)
        assertEquals("boom", (status as McpStatus.Error).message)
    }

    @Test
    fun `connect rejects a blank url without calling the gateway`() = runTest {
        val gateway = FakeMcpGateway(serverInfo = fakeServer)
        val controller = McpConnectionController(gateway)
        controller.onUrlChange("   ")

        controller.connect()

        assertEquals(0, gateway.connectCallCount)
        assertTrue(controller.state.value.status is McpStatus.Error)
    }

    @Test
    fun `reconnecting closes the previous connection first`() = runTest {
        val gateway = FakeMcpGateway(serverInfo = fakeServer, tools = fakeTools)
        val controller = McpConnectionController(gateway)

        controller.connect()
        controller.connect()

        assertEquals(2, gateway.connectCallCount)
        assertEquals(2, gateway.closeCallCount) // once per connect(), before each (re)connect
    }

    @Test
    fun `disconnect closes the gateway and returns to Idle`() = runTest {
        val gateway = FakeMcpGateway(serverInfo = fakeServer, tools = fakeTools)
        val controller = McpConnectionController(gateway)
        controller.connect()

        controller.disconnect()

        assertEquals(McpStatus.Idle, controller.state.value.status)
        assertEquals(2, gateway.closeCallCount) // once from connect(), once from disconnect()
    }
}
