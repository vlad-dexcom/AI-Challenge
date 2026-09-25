package com.example.geminichat.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** UI-facing connection state for the "MCP" screen (see [com.example.geminichat.mcp.McpScreen]). */
sealed class McpStatus {
    data object Idle : McpStatus()
    data object Connecting : McpStatus()
    data class Connected(val snapshot: McpConnectionSnapshot) : McpStatus()
    data class Error(val message: String) : McpStatus()
}

data class McpUiState(
    val serverUrl: String = McpConfig.DEFAULT_SERVER_URL,
    val status: McpStatus = McpStatus.Idle
)

/**
 * Plain (non-Android) orchestration for the MCP "connect, then list tools" flow: owns the
 * [McpUiState] state machine (Idle -> Connecting -> Connected/Error) and drives [gateway].
 * Deliberately has no dependency on `android.util.Log`/`ViewModel`/`viewModelScope` so it can be
 * unit-tested on the plain JVM with a fake [McpGateway] and no Robolectric/Android test
 * dependency; [McpViewModel] is a thin Android wrapper around this that adds Logcat output and
 * `viewModelScope` coroutine launching.
 */
class McpConnectionController(
    private val gateway: McpGateway,
    private val onLog: (String) -> Unit = {},
    private val onLogError: (String, Throwable?) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(McpUiState())
    val state: StateFlow<McpUiState> = _state

    fun onUrlChange(url: String) {
        _state.value = _state.value.copy(serverUrl = url)
    }

    suspend fun connect() {
        val url = _state.value.serverUrl.trim()
        if (url.isBlank()) {
            _state.value = _state.value.copy(status = McpStatus.Error("Server URL cannot be empty."))
            return
        }

        _state.value = _state.value.copy(status = McpStatus.Connecting)
        onLog("Connecting to $url ...")

        val startedAtMs = System.currentTimeMillis()
        try {
            gateway.close() // drop any previous connection before starting a new one
            val server = gateway.connect(url)
            onLog(
                "Connected: ${server.name} ${server.version} " +
                    "(capabilities=${server.capabilities}, instructions=${server.instructions})"
            )

            val tools = gateway.listTools()
            onLog("${tools.size} tool(s) available:")
            tools.forEach { tool -> onLog(" - ${tool.name}: ${tool.description ?: "(no description)"}") }

            val snapshot = McpConnectionSnapshot(
                serverUrl = url,
                server = server,
                tools = tools,
                latencyMs = System.currentTimeMillis() - startedAtMs
            )
            _state.value = _state.value.copy(status = McpStatus.Connected(snapshot))
        } catch (e: McpConnectionException) {
            onLogError("Connection failed: ${e.message}", e)
            _state.value = _state.value.copy(status = McpStatus.Error(e.message ?: "Failed to connect to MCP server."))
        } catch (e: Exception) {
            onLogError("Unexpected error: ${e.message}", e)
            _state.value = _state.value.copy(status = McpStatus.Error(e.message ?: "Unexpected error while connecting."))
        }
    }

    suspend fun disconnect() {
        gateway.close()
        onLog("Disconnected.")
        _state.value = _state.value.copy(status = McpStatus.Idle)
    }
}
