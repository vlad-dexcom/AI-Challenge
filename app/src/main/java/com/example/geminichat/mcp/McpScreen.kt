package com.example.geminichat.mcp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Day 16 "MCP" screen: lets the user pick a server URL, connect, and see the resulting server
 * identity and tool list (or the connection error). Reached from [com.example.geminichat.ChatScreen]'s
 * app bar, following the same full-screen pattern as its Settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(viewModel: McpViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val status = uiState.status

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = uiState.serverUrl,
                onValueChange = viewModel::onUrlChange,
                label = { Text("MCP server URL") },
                enabled = status !is McpStatus.Connecting,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = viewModel::connect,
                    enabled = status !is McpStatus.Connecting
                ) {
                    Text(if (status is McpStatus.Connected) "Reconnect" else "Connect")
                }
                if (status is McpStatus.Connected) {
                    OutlinedButton(
                        onClick = viewModel::disconnect,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text("Disconnect")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            when (status) {
                is McpStatus.Idle -> Text(
                    "Not connected.",
                    style = MaterialTheme.typography.bodyMedium
                )

                is McpStatus.Connecting -> Row {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Text(
                        "  Connecting...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                is McpStatus.Error -> Text(
                    "❌ ${status.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )

                is McpStatus.Connected -> McpConnectedContent(status.snapshot)
            }
        }
    }
}

@Composable
private fun McpConnectedContent(snapshot: McpConnectionSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("✅ Connected", color = Color(0xFF2E7D32), style = MaterialTheme.typography.titleMedium)
            Text("Server: ${snapshot.server.name} ${snapshot.server.version}")
            Text("Capabilities: ${snapshot.server.capabilities.ifEmpty { listOf("(none)") }.joinToString()}")
            snapshot.server.instructions?.takeIf { it.isNotBlank() }?.let {
                Text("Instructions: $it", style = MaterialTheme.typography.bodySmall)
            }
            Text("Latency: ${snapshot.latencyMs} ms", style = MaterialTheme.typography.bodySmall)
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Tools (${snapshot.tools.size})",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (snapshot.tools.isEmpty()) {
        Text("Server exposes no tools.", style = MaterialTheme.typography.bodyMedium)
    } else {
        snapshot.tools.forEach { tool ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(tool.title ?: tool.name, style = MaterialTheme.typography.titleSmall)
                    if (tool.title != null) {
                        Text(tool.name, style = MaterialTheme.typography.labelSmall)
                    }
                    tool.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    if (tool.parameters.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        tool.parameters.forEach { param ->
                            val marker = if (param.required) "*" else ""
                            Text(
                                "• ${param.name}$marker: ${param.type ?: "any"}" +
                                    (param.description?.let { " — $it" } ?: ""),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}
