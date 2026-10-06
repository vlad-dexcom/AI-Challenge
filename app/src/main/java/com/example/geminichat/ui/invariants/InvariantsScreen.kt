package com.example.geminichat.ui.invariants

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.geminichat.ui.DetailScaffold
import com.example.geminichat.ui.ErrorText

@Composable
fun InvariantsScreen(viewModel: InvariantsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    DetailScaffold(title = "Invariants", onBack = onBack) {
        InvariantPanel(
            invariants = state.invariants,
            enabled = !state.isLoading,
            onToggle = viewModel::onToggle,
            onDelete = viewModel::onDelete,
            onAdd = viewModel::onAdd,
            onApplyPreset = viewModel::onApplyPreset,
            onReset = viewModel::onReset
        )
        ErrorText(state.errorMessage)
    }
}
