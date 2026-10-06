package com.example.geminichat.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.example.geminichat.agent.AgentConfig

@Composable
internal fun AgentSelector(
    selectedAgentId: String,
    availableAgents: List<AgentConfig>,
    enabled: Boolean,
    onAgentSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = availableAgents.firstOrNull { it.id == selectedAgentId }?.displayName
        ?: selectedAgentId

    Box {
        TextButton(onClick = { if (enabled) expanded = true }, enabled = enabled) {
            Text(selectedName)
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
internal fun ModelSelector(
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
