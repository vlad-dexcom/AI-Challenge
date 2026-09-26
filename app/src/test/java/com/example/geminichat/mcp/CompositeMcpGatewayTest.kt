package com.example.geminichat.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A minimal fake [McpGateway] exposing [tools] and, optionally, failing to [connect]. */
private class FakeMcpGateway(
    private val tools: List<McpToolInfo>,
    private val resultsByTool: Map<String, String>,
    private val failConnect: Boolean = false
) : McpGateway {
    var connected = false
        private set
    val calledTools = mutableListOf<String>()

    override suspend fun connect(serverUrl: String): McpServerInfo {
        if (failConnect) throw McpConnectionException("simulated connection failure")
        connected = true
        return McpServerInfo(name = "fake", version = "1.0.0", capabilities = listOf("tools"), instructions = null)
    }

    override suspend fun listTools(): List<McpToolInfo> {
        if (!connected) throw McpConnectionException("not connected")
        return tools
    }

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolCallResult {
        calledTools += name
        val text = resultsByTool[name] ?: error("No scripted result for \"$name\".")
        return McpToolCallResult(text = text, isError = false)
    }

    override suspend fun close() {
        connected = false
    }
}

private fun toolInfo(name: String) = McpToolInfo(
    name = name,
    title = name,
    description = "test tool",
    parameters = emptyList(),
    rawInputSchema = buildJsonObject { put("type", JsonPrimitive("object")) }
)

/**
 * Day 20: proves [CompositeMcpGateway] routes each `callTool` to the member that declared the
 * tool, aggregates tools across every reachable member, and degrades gracefully when one member
 * cannot be reached instead of failing the whole connection.
 */
class CompositeMcpGatewayTest {

    @Test
    fun `aggregates tools from every member and routes calls to the declaring member`() = runTest {
        val remote = FakeMcpGateway(
            tools = listOf(toolInfo("get_exercise_info"), toolInfo("suggest_workout")),
            resultsByTool = mapOf("get_exercise_info" to "Squat: a compound leg exercise.")
        )
        val digest = FakeMcpGateway(
            tools = listOf(toolInfo("log_workout"), toolInfo("get_workout_summary")),
            resultsByTool = mapOf("log_workout" to "Logged a 30-minute \"legs\" workout.")
        )
        val planner = FakeMcpGateway(
            tools = listOf(toolInfo("find_exercises"), toolInfo("build_workout_plan"), toolInfo("save_workout_plan")),
            resultsByTool = mapOf("find_exercises" to "[{\"name\":\"Squat\"}]")
        )

        val composite = CompositeMcpGateway(
            listOf(
                NamedMcpGateway("wger", "https://wger.example/mcp", remote),
                NamedMcpGateway("workout-digest", "local://workout-digest", digest),
                NamedMcpGateway("workout-planner", "local://workout-plan-builder", planner)
            )
        )

        composite.connect("orchestrator://all")
        val tools = composite.listTools()

        assertEquals(
            setOf(
                "get_exercise_info", "suggest_workout", "log_workout", "get_workout_summary",
                "find_exercises", "build_workout_plan", "save_workout_plan"
            ),
            tools.map { it.name }.toSet()
        )

        // Each call is routed to the member that actually declared the tool, not a fixed one.
        assertEquals("Squat: a compound leg exercise.", composite.callTool("get_exercise_info", emptyMap()).text)
        assertEquals("[{\"name\":\"Squat\"}]", composite.callTool("find_exercises", emptyMap()).text)
        assertEquals("Logged a 30-minute \"legs\" workout.", composite.callTool("log_workout", emptyMap()).text)

        assertEquals(listOf("get_exercise_info"), remote.calledTools)
        assertEquals(listOf("find_exercises"), planner.calledTools)
        assertEquals(listOf("log_workout"), digest.calledTools)
    }

    @Test
    fun `an unreachable member is skipped without failing the whole connection`() = runTest {
        val remote = FakeMcpGateway(
            tools = listOf(toolInfo("get_exercise_info")),
            resultsByTool = emptyMap(),
            failConnect = true
        )
        val planner = FakeMcpGateway(
            tools = listOf(toolInfo("find_exercises")),
            resultsByTool = mapOf("find_exercises" to "[{\"name\":\"Squat\"}]")
        )

        val composite = CompositeMcpGateway(
            listOf(
                NamedMcpGateway("wger", "https://wger.example/mcp", remote),
                NamedMcpGateway("workout-planner", "local://workout-plan-builder", planner)
            )
        )

        // Connect succeeds overall even though the remote member fails.
        composite.connect("orchestrator://all")
        val tools = composite.listTools()

        assertEquals(listOf("find_exercises"), tools.map { it.name })
        assertFalse(remote.connected)

        // The still-reachable member's tool works normally.
        assertEquals("[{\"name\":\"Squat\"}]", composite.callTool("find_exercises", emptyMap()).text)
    }

    @Test
    fun `calling a tool no connected member declared fails with a clear error`() = runTest {
        val planner = FakeMcpGateway(
            tools = listOf(toolInfo("find_exercises")),
            resultsByTool = mapOf("find_exercises" to "[]")
        )
        val composite = CompositeMcpGateway(
            listOf(NamedMcpGateway("workout-planner", "local://workout-plan-builder", planner))
        )
        composite.connect("orchestrator://all")
        composite.listTools()

        val exception = try {
            composite.callTool("get_exercise_info", emptyMap())
            null
        } catch (e: McpToolCallException) {
            e
        }
        assertTrue(exception != null)
    }
}
