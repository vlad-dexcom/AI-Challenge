package com.example.geminichat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jeziellago.compose.markdowntext.MarkdownText

/**
 * The app's single screen: the user enters one task, taps "Run all", and the four
 * [ThinkingMode]s are executed concurrently, each rendered as its own tile so the
 * approaches can be compared side by side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparisonScreen(viewModel: ComparisonViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Thinking Variations")
                        ModelSelector(
                            selectedModel = uiState.selectedModel,
                            availableModels = uiState.availableModels,
                            enabled = !uiState.isRunning,
                            onModelSelected = viewModel::onModelSelected
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = uiState.question,
                    onValueChange = viewModel::onQuestionChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Enter a logical, algorithmic, or analytical task...") },
                    enabled = !uiState.isRunning
                )
                Spacer(modifier = Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.runAll() },
                        enabled = !uiState.isRunning && uiState.question.isNotBlank()
                    ) {
                        Text("Run all")
                    }
                    OutlinedButton(
                        onClick = { viewModel.cancelAll() },
                        enabled = uiState.isRunning
                    ) {
                        Text("Stop")
                    }
                    OutlinedButton(
                        onClick = { viewModel.clearResults() },
                        enabled = !uiState.isRunning
                    ) {
                        Text("Clear")
                    }
                }
            }

            ResultTiles(
                results = uiState.results,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ResultTiles(
    results: Map<ThinkingMode, ModeResult>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val modes = ThinkingMode.entries
        val twoColumns = maxWidth >= 600.dp

        if (twoColumns) {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(modes.chunked(2)) { rowModes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowModes.forEach { mode ->
                            ResultTile(
                                mode = mode,
                                result = results[mode] ?: ModeResult(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowModes.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(modes) { mode ->
                    ResultTile(
                        mode = mode,
                        result = results[mode] ?: ModeResult(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultTile(
    mode: ThinkingMode,
    result: ModeResult,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }
    var promptExpanded by remember { mutableStateOf(false) }

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(mode.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                result.durationMs?.let { ms ->
                    Text(
                        text = "%.1fs".format(ms / 1000.0),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = if (expanded) "Collapse" else "Expand")
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.size(8.dp))

                when (result.status) {
                    ModeStatus.Idle -> Text(
                        "Waiting to run.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ModeStatus.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Thinking...", style = MaterialTheme.typography.bodyMedium)
                    }

                    ModeStatus.Error -> Text(
                        "Error: ${result.error}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    ModeStatus.Success -> Unit
                }

                result.generatedPrompt?.let { prompt ->
                    Spacer(modifier = Modifier.size(8.dp))
                    TextButton(onClick = { promptExpanded = !promptExpanded }) {
                        Text(if (promptExpanded) "Hide generated prompt" else "Show generated prompt")
                    }
                    if (promptExpanded) {
                        Text(
                            prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                result.answer?.let { answer ->
                    Spacer(modifier = Modifier.size(8.dp))
                    MarkdownText(
                        markdown = answer,
                        style = MaterialTheme.typography.bodyMedium,
                        isTextSelectable = true
                    )
                }
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
