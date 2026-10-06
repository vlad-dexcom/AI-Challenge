package com.example.geminichat.ui.memory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.geminichat.ui.DetailScaffold

@Composable
fun MemoryScreen(viewModel: MemoryViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    DetailScaffold(title = "Memory", onBack = onBack) {
        MemoryPanel(
            longTermMemory = state.longTermMemory,
            workingMemory = state.workingMemory,
            memoryRoutingTokensTotal = state.memoryRoutingTokensTotal,
            lastMemoryDecisions = state.lastMemoryDecisions,
            enabled = !state.isLoading,
            onPromote = viewModel::onPromote,
            onDeleteWorking = viewModel::onDeleteWorking,
            onDeleteLongTerm = viewModel::onDeleteLongTerm,
            onAddLongTerm = viewModel::onAddLongTerm,
            onEndTask = viewModel::onEndTask,
            onClearDialog = viewModel::onClearDialog
        )
    }
}
