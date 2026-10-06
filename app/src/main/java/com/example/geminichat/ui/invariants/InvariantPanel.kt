package com.example.geminichat.ui.invariants

import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.geminichat.agent.invariant.Invariant
import com.example.geminichat.agent.invariant.InvariantCategory
import com.example.geminichat.agent.invariant.InvariantPreset
import com.example.geminichat.agent.invariant.InvariantSet

/**
 * Day 14: shows the current [com.example.geminichat.agent.invariant.InvariantSet] — hard rules
 * the agent must never break, grouped by category. A [Invariant.locked] entry shows a 🔒 badge
 * instead of a switch (it can never be turned off) and has no delete action; every other
 * invariant can be toggled or deleted. Mirrors [ProfilePanel]'s collapsible-panel/preset-row
 * shape.
 */
@Composable
internal fun InvariantPanel(
    invariants: InvariantSet,
    enabled: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: (String, InvariantCategory, String, String, String, String) -> Unit,
    onApplyPreset: (InvariantPreset) -> Unit,
    onReset: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var newCategory by remember { mutableStateOf(InvariantCategory.SAFETY) }
    var newStatement by remember { mutableStateOf("") }
    var newRationale by remember { mutableStateOf("") }
    var newAlternative by remember { mutableStateOf("") }
    var newTriggers by remember { mutableStateOf("") }

    val enabledCount = invariants.enabledInvariants.size
    val totalCount = invariants.invariants.size

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Invariants: $enabledCount of $totalCount enabled",
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
            text = "Presets",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            InvariantSet.PRESETS.forEach { preset ->
                TextButton(onClick = { onApplyPreset(preset) }, enabled = enabled) {
                    Text(preset.label)
                }
            }
        }

        InvariantCategory.entries.forEach { category ->
            val invariantsInCategory = invariants.invariants.filter { it.category == category }
            if (invariantsInCategory.isEmpty()) return@forEach
            Text(
                text = category.name,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            invariantsInCategory.forEach { invariant ->
                InvariantRow(invariant = invariant, enabled = enabled, onToggle = onToggle, onDelete = onDelete)
            }
        }

        Text(
            text = "Add invariant",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        InvariantCategorySelector(
            selected = newCategory,
            enabled = enabled,
            onSelected = { newCategory = it }
        )
        OutlinedTextField(
            value = newStatement,
            onValueChange = { newStatement = it },
            label = { Text("Rule") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = newRationale,
            onValueChange = { newRationale = it },
            label = { Text("Why") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = newAlternative,
            onValueChange = { newAlternative = it },
            label = { Text("Alternative to offer instead") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = newTriggers,
            onValueChange = { newTriggers = it },
            label = { Text("Trigger patterns (comma-separated)") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        Row(modifier = Modifier.padding(top = 4.dp)) {
            TextButton(
                onClick = {
                    val id = newStatement.trim().lowercase()
                        .replace(Regex("[^a-z0-9]+"), "-").trim('-')
                    onAdd(id, newCategory, newStatement, newRationale, newAlternative, newTriggers)
                    if (id.isNotBlank()) {
                        newStatement = ""
                        newRationale = ""
                        newAlternative = ""
                        newTriggers = ""
                    }
                },
                enabled = enabled && newStatement.isNotBlank() && newRationale.isNotBlank()
            ) {
                Text("Add")
            }
            TextButton(onClick = onReset, enabled = enabled) {
                Text("Reset to defaults")
            }
        }
    }
}

@Composable
internal fun InvariantRow(
    invariant: Invariant,
    enabled: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = invariant.statement,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            if (invariant.locked) {
                Text(
                    text = "\uD83D\uDD12",
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            } else {
                Switch(
                    checked = invariant.enabled,
                    onCheckedChange = { onToggle(invariant.id, it) },
                    enabled = enabled
                )
                TextButton(onClick = { onDelete(invariant.id) }, enabled = enabled) {
                    Text("Delete")
                }
            }
        }
        Text(
            text = invariant.rationale,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun InvariantCategorySelector(
    selected: InvariantCategory,
    enabled: Boolean,
    onSelected: (InvariantCategory) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Text(
            text = "Category",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = { if (enabled) expanded = true }, enabled = enabled) {
                Text(selected.name.lowercase())
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select category")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                InvariantCategory.entries.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.name.lowercase()) },
                        onClick = {
                            onSelected(category)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
