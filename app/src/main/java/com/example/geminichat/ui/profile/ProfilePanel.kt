package com.example.geminichat.ui.profile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import com.example.geminichat.agent.profile.ExpertiseLevel
import com.example.geminichat.agent.profile.ProfileField
import com.example.geminichat.agent.profile.UserProfile

/**
 * Day 12 personalization panel: edits the single, global [UserProfile] in place — there is no
 * per-profile selector, since there is only ever one profile (see [UserProfile]). The preset
 * row at the top is a shortcut that overwrites every field at once with one of
 * [UserProfile.PRESETS], so maximally different profiles can be tried back to back; regular
 * text fields still commit on every keystroke via [onFieldChange] (mirrors [MemoryPanel]'s
 * immediate-apply style), and constraints are a separate add/remove list since they're a set,
 * not a single overwritable value.
 */
@Composable
internal fun ProfilePanel(
    profile: UserProfile,
    personalizationTokensTotal: Int,
    enabled: Boolean,
    onFieldChange: (ProfileField, String) -> Unit,
    onAddConstraint: (String) -> Unit,
    onRemoveConstraint: (String) -> Unit,
    onApplyPreset: (UserProfile) -> Unit,
    onReset: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var newConstraint by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (profile.isEmpty()) {
                    "Profile: not set up · personalization cost $personalizationTokensTotal tokens"
                } else {
                    "Profile: ${profile.displayName.ifBlank { "(unnamed)" }} · " +
                        "personalization cost $personalizationTokensTotal tokens"
                },
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
            text = "Presets (test with very different profiles)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            UserProfile.PRESETS.forEach { preset ->
                TextButton(onClick = { onApplyPreset(preset.profile) }, enabled = enabled) {
                    Text(preset.label)
                }
            }
        }

        OutlinedTextField(
            value = profile.displayName,
            onValueChange = { onFieldChange(ProfileField.DISPLAY_NAME, it) },
            label = { Text("Name") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.about,
            onValueChange = { onFieldChange(ProfileField.ABOUT, it) },
            label = { Text("About") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.language,
            onValueChange = { onFieldChange(ProfileField.LANGUAGE, it) },
            label = { Text("Language") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        ExpertiseSelector(
            selected = profile.expertise,
            enabled = enabled,
            onSelected = { level -> onFieldChange(ProfileField.EXPERTISE, level.name) }
        )
        OutlinedTextField(
            value = profile.tone,
            onValueChange = { onFieldChange(ProfileField.TONE, it) },
            label = { Text("Tone") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.format,
            onValueChange = { onFieldChange(ProfileField.FORMAT, it) },
            label = { Text("Preferred format") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.maxAnswerSentences?.toString() ?: "",
            onValueChange = { onFieldChange(ProfileField.MAX_ANSWER_SENTENCES, it) },
            label = { Text("Max answer sentences") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.notes,
            onValueChange = { onFieldChange(ProfileField.NOTES, it) },
            label = { Text("Notes") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )

        Text(
            text = "Constraints",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (profile.constraints.isEmpty()) {
            Text(
                "(none)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        profile.constraints.forEach { constraint ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = constraint,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onRemoveConstraint(constraint) }, enabled = enabled) {
                    Text("Delete")
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            OutlinedTextField(
                value = newConstraint,
                onValueChange = { newConstraint = it },
                placeholder = { Text("e.g. no jumping") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    onAddConstraint(newConstraint)
                    newConstraint = ""
                },
                enabled = enabled && newConstraint.isNotBlank()
            ) {
                Text("Add")
            }
        }

        Row(modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = onReset, enabled = enabled) {
                Text("Reset profile")
            }
        }
    }
}

@Composable
internal fun ExpertiseSelector(
    selected: ExpertiseLevel?,
    enabled: Boolean,
    onSelected: (ExpertiseLevel) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Text(
            text = "Expertise",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = { if (enabled) expanded = true }, enabled = enabled) {
                Text(selected?.name?.lowercase() ?: "not set")
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select expertise")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ExpertiseLevel.entries.forEach { level ->
                    DropdownMenuItem(
                        text = { Text(level.name.lowercase()) },
                        onClick = {
                            onSelected(level)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
