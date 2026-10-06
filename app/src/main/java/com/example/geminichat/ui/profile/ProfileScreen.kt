package com.example.geminichat.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.geminichat.ui.DetailScaffold

@Composable
fun ProfileScreen(viewModel: ProfileViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    DetailScaffold(title = "Profile", onBack = onBack) {
        ProfilePanel(
            profile = state.profile,
            personalizationTokensTotal = state.personalizationTokensTotal,
            enabled = !state.isLoading,
            onFieldChange = viewModel::onFieldChange,
            onAddConstraint = viewModel::onAddConstraint,
            onRemoveConstraint = viewModel::onRemoveConstraint,
            onApplyPreset = viewModel::onApplyPreset,
            onReset = viewModel::onReset
        )
    }
}
