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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "MCP" screen: a plain log of every MCP call made anywhere in the app, backed by the app-wide
 * [McpCallLog] singleton — both calls made while chatting with the "Fitness Coach (MCP tools)"
 * persona ([com.example.geminichat.agent.mcp.McpToolCallingAgent]) and any other
 * [KotlinSdkMcpGateway] call, show up here. Reached from [com.example.geminichat.ChatScreen]'s
 * app bar.
 *
 * Day 16 originally had this screen own its own MCP connection (server-URL field,
 * Connect/Disconnect buttons, server identity + tool list) to demonstrate the connect/list-tools
 * flow in isolation. That connection was redundant with the one the chat's tool-calling agent
 * already makes when it needs a tool, so it was dropped: this screen is now display-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(onBack: () -> Unit) {
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
            McpCallLogContent()
        }
    }
}

@Composable
private fun McpCallLogContent() {
    val entries by McpCallLog.entries.collectAsState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Call log (${entries.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = McpCallLog::clear, enabled = entries.isNotEmpty()) {
            Text("Clear")
        }
    }
    Spacer(modifier = Modifier.height(8.dp))

    if (entries.isEmpty()) {
        Text(
            "No MCP calls yet. Chat with the \"Fitness Coach (MCP tools)\" agent to trigger one.",
            style = MaterialTheme.typography.bodyMedium
        )
    } else {
        entries.asReversed().forEach { entry ->
            Text(
                "${timeFormat.format(Date(entry.timestampMs))}  ${entry.summary}",
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}
