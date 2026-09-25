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
 */
class McpViewModel(gateway: McpGateway) : ViewModel() {

    private val controller = McpConnectionController(
        gateway = gateway,
        onLog = { message -> Log.i(LOG_TAG, message) },
        onLogError = { message, cause -> Log.e(LOG_TAG, message, cause) }
    )

    val uiState: StateFlow<McpUiState> = controller.state

    fun onUrlChange(url: String) = controller.onUrlChange(url)

    fun connect() {
        viewModelScope.launch { controller.connect() }
    }

    fun disconnect() {
        viewModelScope.launch { controller.disconnect() }
    }

    // Not closing the gateway in onCleared(): viewModelScope is already cancelled by the time
    // ViewModel.onCleared() runs, so a coroutine launched here would never execute. connect()
    // always closes any prior connection first, and disconnect() closes explicitly, which
    // covers Day 16's scope; a more thorough teardown (e.g. Activity-scoped cleanup) is a
    // later-day concern once callTool()/background work make an open connection more expensive.
}
