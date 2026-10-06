package com.example.geminichat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.geminichat.agent.AgentCatalog
import com.example.geminichat.agent.rag.RagAgentMode
import com.example.geminichat.ui.DetailScaffold

/** Agent / model / RAG-mode choice; profile and invariants live on their own screens. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenInvariants: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    DetailScaffold(title = "Settings", onBack = onBack) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Agent",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            AgentSelector(
                selectedAgentId = state.selectedAgentId,
                availableAgents = state.availableAgents,
                enabled = !state.isLoading,
                onAgentSelected = viewModel::onAgentSelected
            )
        }
        Text(
            text = state.agentDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        if (state.selectedAgentId == AgentCatalog.RAG_KNOWLEDGE_COACH.id) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RagAgentMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.ragMode == mode,
                        onClick = { viewModel.onRagModeSelected(mode) },
                        label = { Text(mode.label) },
                        enabled = !state.isLoading
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Model",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            ModelSelector(
                selectedModel = state.selectedModel,
                availableModels = state.availableModels,
                enabled = !state.isLoading,
                onModelSelected = viewModel::onModelSelected
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        OutlinedButton(
            onClick = onOpenProfile,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        ) { Text("Profile") }
        OutlinedButton(
            onClick = onOpenInvariants,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        ) { Text("Invariants") }
    }
}
