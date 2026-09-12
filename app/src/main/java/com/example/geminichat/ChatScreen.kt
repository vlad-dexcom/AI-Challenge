package com.example.geminichat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.geminichat.agent.AgentConfig
import dev.jeziellago.compose.markdowntext.MarkdownText

/**
 * The single screen of the app: a scrollable message list plus a text input row that sends
 * the user's message to the current [com.example.geminichat.agent.Agent] and appends its
 * reply. The top bar shows which agent is active (name + one-line description) and lets the
 * user switch both the agent persona and the underlying model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AgentSelector(
                                availableAgents = uiState.availableAgents,
                                agentName = uiState.agentName,
                                enabled = !uiState.isLoading,
                                onAgentSelected = viewModel::onAgentSelected
                            )
                            ModelSelector(
                                selectedModel = uiState.selectedModel,
                                availableModels = uiState.availableModels,
                                enabled = !uiState.isLoading,
                                onModelSelected = viewModel::onModelSelected
                            )
                        }
                    }
                )
                Text(
                    text = uiState.agentDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                if (uiState.dialogTokenTotal > 0) {
                    Text(
                        text = "Tokens in dialog: ${uiState.dialogTokenTotal} (estimated)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                CompressionBar(
                    enabled = uiState.compressionEnabled,
                    summarizedMessageCount = uiState.summarizedMessageCount,
                    contextSummary = uiState.contextSummary,
                    compressionTokensTotal = uiState.compressionTokensTotal,
                    onToggle = viewModel::onCompressionToggled
                )
            }
        }
    ) { padding ->
        // imePadding() lets this column shrink above the keyboard instead of letting
        // messages scroll underneath it; combined with windowSoftInputMode="adjustResize"
        // (see AndroidManifest) the whole layout resizes cleanly when the keyboard opens.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.messages) { message ->
                    MessageBubble(message)
                }
                if (uiState.isLoading) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            uiState.errorMessage?.let { error ->
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = uiState.input,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask Gemini...") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.size(8.dp))
                IconButton(onClick = { viewModel.sendMessage() }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/**
 * Day 9 context-compression controls: a switch to A/B compare "full history" vs. "compressed
 * history" for the same conversation, plus a compact readout of the current summary state and
 * what compressing it has cost in (separately tracked) tokens — see
 * [ChatViewModel.onCompressionToggled] and [ChatUiState.compressionTokensTotal].
 */
@Composable
private fun CompressionBar(
    enabled: Boolean,
    summarizedMessageCount: Int,
    contextSummary: String,
    compressionTokensTotal: Int,
    onToggle: (Boolean) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Compress history",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        if (enabled && summarizedMessageCount > 0) {
            Text(
                text = "Summary covers $summarizedMessageCount older messages " +
                    "(~${contextSummary.length} chars) · compression cost: " +
                    "$compressionTokensTotal tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun AgentSelector(
    availableAgents: List<AgentConfig>,
    agentName: String,
    enabled: Boolean,
    onAgentSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { if (enabled) expanded = true }, enabled = enabled) {
            Text(agentName)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select agent")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            availableAgents.forEach { agentConfig ->
                DropdownMenuItem(
                    text = { Text(agentConfig.displayName) },
                    onClick = {
                        onAgentSelected(agentConfig.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelSelector(
    selectedModel: String,
    availableModels: List<String>,
    enabled: Boolean,
    onModelSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { if (enabled) expanded = true }, enabled = enabled) {
            Text(selectedModel)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select model")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            availableModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        onModelSelected(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isFromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.isFromUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            if (message.isFromUser) {
                // The user's own input is shown as-is; only the model's replies are
                // rendered as Markdown (bold, lists, code blocks, links, etc.).
                Text(
                    text = message.text,
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                Column(modifier = Modifier.padding(12.dp)) {
                    MarkdownText(
                        markdown = message.text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        isTextSelectable = true
                    )
                    message.tokenUsage?.let { usage ->
                        Text(
                            text = "prompt ${usage.promptTokens} (history ${usage.historyTokens}, " +
                                "summary ${usage.summaryTokens}) · reply ${usage.completionTokens} · " +
                                "total ${usage.totalTokens}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
