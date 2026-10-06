package com.example.geminichat.ui.memory

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.geminichat.agent.memory.MemoryRoutingDecision
import com.example.geminichat.agent.memory.MemorySnapshot

/**
 * Day 11 memory inspector. Lists every item in
 * [com.example.geminichat.agent.memory.MemoryLayer.WORKING] and
 * [com.example.geminichat.agent.memory.MemoryLayer.LONG_TERM] *separately* — so "what data
 * landed in which layer" is directly checkable, not just inferred from the model's answers —
 * plus manual overrides (promote/delete/add — see [com.example.geminichat.ChatController]) and "End task"/"Clear
 * dialog", which demonstrate the three layers are independent by only ever clearing one of
 * them at a time.
 */
@Composable
internal fun MemoryPanel(
    longTermMemory: MemorySnapshot,
    workingMemory: MemorySnapshot,
    memoryRoutingTokensTotal: Int,
    lastMemoryDecisions: List<MemoryRoutingDecision>,
    enabled: Boolean,
    onPromote: (String) -> Unit,
    onDeleteWorking: (String) -> Unit,
    onDeleteLongTerm: (String) -> Unit,
    onAddLongTerm: (String, String) -> Unit,
    onEndTask: () -> Unit,
    onClearDialog: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Memory: ${workingMemory.items.size} working · " +
                    "${longTermMemory.items.size} long-term · routing cost " +
                    "$memoryRoutingTokensTotal tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (!expanded) return@Column

        Text(
            text = "Long-term (profile, decisions, knowledge)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (longTermMemory.items.isEmpty()) {
            Text(
                "(empty)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        longTermMemory.items.values.sortedBy { it.key }.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${item.key}: ${item.value}" + if (item.pinned) " (pinned)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onDeleteLongTerm(item.key) }, enabled = enabled) {
                    Text("Delete")
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                placeholder = { Text("key") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.size(4.dp))
            OutlinedTextField(
                value = newValue,
                onValueChange = { newValue = it },
                placeholder = { Text("value") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    onAddLongTerm(newKey, newValue)
                    newKey = ""
                    newValue = ""
                },
                enabled = enabled && newKey.isNotBlank() && newValue.isNotBlank()
            ) {
                Text("Add")
            }
        }

        Text(
            text = "Working (current task)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (workingMemory.items.isEmpty()) {
            Text(
                "(empty)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        workingMemory.items.values.sortedBy { it.key }.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${item.key}: ${item.value}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onPromote(item.key) }, enabled = enabled) {
                    Text("Promote")
                }
                TextButton(onClick = { onDeleteWorking(item.key) }, enabled = enabled) {
                    Text("Delete")
                }
            }
        }

        if (lastMemoryDecisions.isNotEmpty()) {
            Text(
                text = "Last turn routed: " + lastMemoryDecisions.joinToString("; ") { decision ->
                    "${decision.layer} ${decision.key} (${decision.reason})"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Row(modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = onEndTask, enabled = enabled) {
                Text("End task")
            }
            TextButton(onClick = onClearDialog, enabled = enabled) {
                Text("Clear dialog")
            }
        }
    }
}
