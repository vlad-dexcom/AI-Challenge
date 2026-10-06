package com.example.geminichat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.example.geminichat.R
import com.example.geminichat.agent.task.TaskState
import com.example.geminichat.agent.task.ValidationOutcome

/** Where the chat screen can send the user; wired to the navigation back stack by the caller. */
class ChatNavigation(
    val onOpenSettings: () -> Unit,
    val onOpenMcp: () -> Unit,
    val onOpenMemory: () -> Unit,
    val onOpenTask: () -> Unit,
)

/**
 * The conversation itself: message list, input row, branch switcher (bottom sheet) and the
 * pending-suggestion banners. Everything else (settings, profile, invariants, memory, task, MCP)
 * is its own screen reached through [navigation].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, navigation: ChatNavigation) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    var showBranchSheet by remember { mutableStateOf(false) }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    if (showBranchSheet) {
        BranchBottomSheet(
            branches = state.branches,
            currentBranchId = state.currentBranchId,
            hasCheckpoint = state.hasCheckpoint,
            enabled = !state.isLoading,
            onBranchSelected = viewModel::onBranchSelected,
            onSaveCheckpoint = viewModel::onSaveCheckpoint,
            onCreateBranch = viewModel::onCreateBranchFromCheckpoint,
            onDismiss = { showBranchSheet = false }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(state.agentName) },
                    actions = {
                        IconButton(onClick = { showBranchSheet = true }) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_branch),
                                contentDescription = "Branch"
                            )
                        }
                        IconButton(onClick = navigation.onOpenMcp) {
                            Icon(Icons.Filled.Build, contentDescription = "MCP")
                        }
                        IconButton(onClick = navigation.onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                )
                if (state.dialogTokenTotal > 0) {
                    Text(
                        text = "Tokens in dialog: ${state.dialogTokenTotal} (estimated)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                StatusLine(
                    text = "Memory: ${state.workingMemoryCount} working · " +
                        "${state.longTermMemoryCount} long-term · routing cost " +
                        "${state.memoryRoutingTokensTotal} tokens",
                    onClick = navigation.onOpenMemory
                )
                StatusLine(text = taskSummary(state.taskState), onClick = navigation.onOpenTask)
                state.pendingPreferenceSuggestion?.let { suggestion ->
                    SuggestionBanner(
                        suggestion = suggestion,
                        onApply = viewModel::onApplySuggestion,
                        onDismiss = viewModel::onDismissSuggestion
                    )
                }
                state.pendingTaskTransitionSuggestion?.let { suggestion ->
                    TaskTransitionBanner(
                        suggestion = suggestion,
                        onApply = viewModel::onApplyTaskTransitionSuggestion,
                        onDismiss = viewModel::onDismissTaskTransitionSuggestion
                    )
                }
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
                items(state.messages) { message ->
                    MessageBubble(message)
                }
                if (state.isLoading) {
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

            state.errorMessage?.let { error ->
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
                    value = state.input,
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

@Composable
private fun StatusLine(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

private fun taskSummary(taskState: TaskState): String =
    if (!taskState.isActive) {
        "Задача: не начата"
    } else {
        "Задача: ${taskState.title} · ${taskState.stage}" +
            (taskState.progressLabel?.let { " ($it)" } ?: "") +
            (if (taskState.validationOutcome != ValidationOutcome.NOT_RUN) " · Валидация: ${taskState.validationOutcome}" else "") +
            if (taskState.paused) " · НА ПАУЗЕ" else ""
    }
