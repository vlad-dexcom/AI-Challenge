package com.example.geminichat.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.geminichat.agent.profile.PreferenceSuggestion
import com.example.geminichat.agent.profile.UserProfile
import com.example.geminichat.agent.task.TaskTransitionAction
import com.example.geminichat.agent.task.TaskTransitionSuggestion

/**
 * Day 12's hybrid update path made visible: a [com.example.geminichat.agent.profile.PreferenceAdvisor]
 * suggestion is never applied to [UserProfile] automatically — it's shown here with the
 * inferred reason, and only [onApply] (not the advisor call itself) ever changes the profile.
 */
@Composable
internal fun SuggestionBanner(
    suggestion: PreferenceSuggestion,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Assistant suggests updating your profile: ${suggestion.field.name.lowercase()} → " +
                    "\"${suggestion.value}\"",
                style = MaterialTheme.typography.bodySmall
            )
            if (suggestion.reason.isNotBlank()) {
                Text(
                    text = suggestion.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onApply) { Text("Apply") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/** Day 13/15: surfaces a pending [TaskTransitionSuggestion] for the user to approve or dismiss —
 * mirrors [SuggestionBanner]'s "never applied automatically" shape. An
 * [TaskTransitionAction.APPROVE_PLAN] suggestion is one-tap applicable only when the advisor
 * managed to extract the plan's step list from the assistant's last message (see
 * [TaskTransitionSuggestion.proposedSteps]); if it couldn't, "Apply" is hidden and the banner
 * is shown only as a hint that the user should approve the plan via [TaskPanel]'s own field. */
@Composable
internal fun TaskTransitionBanner(
    suggestion: TaskTransitionSuggestion,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val isApprovePlan = suggestion.action == TaskTransitionAction.APPROVE_PLAN
    val canApply = !isApprovePlan || suggestion.proposedSteps.isNotEmpty()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Assistant suggests advancing the task: ${suggestion.action.name.lowercase()}",
                style = MaterialTheme.typography.bodySmall
            )
            if (suggestion.reason.isNotBlank()) {
                Text(
                    text = suggestion.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (isApprovePlan && suggestion.proposedSteps.isNotEmpty()) {
                Text(
                    text = "Шаги: " + suggestion.proposedSteps.joinToString(" → "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(modifier = Modifier.padding(top = 4.dp)) {
                if (canApply) {
                    TextButton(onClick = onApply) { Text("Apply") }
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}
