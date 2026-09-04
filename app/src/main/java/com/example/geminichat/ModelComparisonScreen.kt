package com.example.geminichat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlin.math.roundToInt

/**
 * Single screen: one prompt, sent concurrently to the weak/medium/strong [ModelTier]s, with
 * each tier's answer, latency, token usage, and estimated cost shown in its own card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelComparisonScreen(viewModel: ModelComparisonViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Model Comparison") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = uiState.input,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask all three models...") },
                    singleLine = false,
                    enabled = !uiState.isRunning
                )
                Spacer(modifier = Modifier.size(8.dp))
                Button(
                    onClick = { viewModel.runComparison() },
                    enabled = !uiState.isRunning && uiState.input.isNotBlank()
                ) {
                    Text("Compare")
                }
            }

            SummaryRow(uiState.results)

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ModelTier.entries) { tier ->
                    TierResultCard(tier = tier, result = uiState.results[tier] ?: TierResult.Idle)
                }
            }
        }
    }
}

/** Highlights the fastest and cheapest tier once at least one result has come back. */
@Composable
private fun SummaryRow(results: Map<ModelTier, TierResult>) {
    val successes = results.mapNotNull { (tier, result) ->
        if (result is TierResult.Success) tier to result else null
    }
    if (successes.isEmpty()) return

    val fastest = successes.minByOrNull { it.second.latencyMs }
    val cheapest = successes.mapNotNull { (tier, result) ->
        result.costUsd?.let { tier to it }
    }.minByOrNull { it.second }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        fastest?.let { (tier, result) ->
            Text(
                text = "⚡ Fastest: ${tier.label} (${result.latencyMs}ms)",
                style = MaterialTheme.typography.labelMedium
            )
        }
        cheapest?.let { (tier, cost) ->
            Text(
                text = "💰 Cheapest: ${tier.label} (${formatCost(cost)})",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun TierResultCard(tier: ModelTier, result: TierResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${tier.label} · ${tier.model}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.size(4.dp))

            when (result) {
                is TierResult.Idle -> {
                    Text(
                        text = "Waiting for a prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is TierResult.Loading -> {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Running...", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is TierResult.Error -> {
                    Text(
                        text = "Error: ${result.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is TierResult.Success -> {
                    Text(
                        text = metricsLine(result),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    MarkdownText(
                        markdown = result.answer,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        isTextSelectable = true
                    )
                }
            }
        }
    }
}

private fun metricsLine(result: TierResult.Success): String {
    val usage = result.usage
    val tokensPart = if (usage != null) {
        val input = usage.totalInputTokens ?: 0
        val output = usage.totalOutputTokens ?: 0
        val thought = usage.totalThoughtTokens ?: 0
        val total = usage.totalTokens ?: (input + output + thought)
        "$total tok (in $input / out $output / thought $thought)"
    } else {
        "tokens n/a"
    }
    val costPart = result.costUsd?.let { formatCost(it) } ?: "cost n/a"
    return "${result.latencyMs}ms · $tokensPart · ~$costPart est."
}

private fun formatCost(costUsd: Double): String {
    // Costs on small prompts are tiny fractions of a cent; show 4 decimal places for signal.
    val rounded = (costUsd * 10_000).roundToInt() / 10_000.0
    return "$${"%.4f".format(rounded)}"
}
