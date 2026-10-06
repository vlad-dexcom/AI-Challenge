package com.example.geminichat.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.geminichat.ChatMessage
import dev.jeziellago.compose.markdowntext.MarkdownText

@Composable
internal fun MessageBubble(message: ChatMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isFromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.isFromUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else if (message.refusedByInvariantIds.isNotEmpty() || message.blockedByStageName != null) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            )
        ) {
            if (message.isFromUser) {
                // The user's own input is shown as-is; only the model's replies are
                // rendered as Markdown (bold, lists, code blocks, links, etc.).
                Text(
                    text = message.text,
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (message.refusedByInvariantIds.isNotEmpty()) {
                        // Day 14: this reply is a deterministic refusal — the model was never
                        // called (see LlmAgent.handle) — so it's badged instead of looking like
                        // an ordinary answer.
                        Text(
                            text = "⛔ Инвариант: ${message.refusedByInvariantIds.joinToString(", ")}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    if (message.blockedByStageName != null) {
                        // Day 15: deterministic refusal when user requests an action out of stage lifecycle
                        Text(
                            text = "⛔ Этап задачи: ${message.blockedByStageName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    MarkdownText(
                        markdown = message.text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        isTextSelectable = true
                    )
                    message.tokenUsage?.let { usage ->
                        Text(
                            text = "prompt ${usage.promptTokens} (history ${usage.historyTokens}, " +
                                "lt ${usage.longTermMemoryTokens}, wm ${usage.workingMemoryTokens}, " +
                                "profile ${usage.profileTokens}, inv ${usage.invariantTokens}) · " +
                                "reply ${usage.completionTokens} · total ${usage.totalTokens}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
