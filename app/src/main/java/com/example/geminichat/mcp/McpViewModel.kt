package com.example.geminichat.mcp

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val LOG_TAG = "MCP"

/**
 * Thin Android wrapper around [McpConnectionController]: forwards its state as [uiState] and
 * runs its suspend functions on [viewModelScope], logging every step to Logcat (tag "MCP") so
 * the connection/tool-list can be verified without the UI (`adb logcat -s MCP`). All actual
 * state-machine logic lives in [McpConnectionController], which is unit-tested directly.
 *
 * Auto-connects to [McpConfig.FITNESS_SERVER_URL] as soon as the ViewModel is created — the "MCP"
 * screen has no server-URL field or Connect/Disconnect buttons (Day 17: we only ever talk to our
 * own fitness MCP server now), so [retry] is the only way left to re-attempt a failed connection.
 */
class McpViewModel(gateway: McpGateway) : ViewModel() {

    private val controller = McpConnectionController(
        gateway = gateway,
        onLog = { message -> Log.i(LOG_TAG, message) },
        onLogError = { message, cause -> Log.e(LOG_TAG, message, cause) }
    )

    val uiState: StateFlow<McpUiState> = controller.state

    init {
        connect()
    }

    /** Re-attempts the connection, e.g. after [McpStatus.Error]. */
    fun retry() = connect()

    private fun connect() {
        viewModelScope.launch { controller.connect() }
    }

    // Not closing the gateway in onCleared(): viewModelScope is already cancelled by the time
    // ViewModel.onCleared() runs, so a coroutine launched here would never execute. connect()
    // always closes any prior connection first, which covers Day 16/17's scope; a more thorough
    // teardown (e.g. Activity-scoped cleanup) is a later-day concern once background work makes
    // an open connection more expensive.
}
