package com.example.geminichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Result state of one tier's response within a single comparison run. */
sealed interface TierResult {
    data object Idle : TierResult
    data object Loading : TierResult
    data class Success(
        val answer: String,
        val usage: Usage?,
        val latencyMs: Long,
        val costUsd: Double?
    ) : TierResult
    data class Error(val message: String) : TierResult
}

data class ModelComparisonUiState(
    val input: String = "",
    val isRunning: Boolean = false,
    val results: Map<ModelTier, TierResult> = ModelTier.entries.associateWith { TierResult.Idle }
)

class ModelComparisonViewModel(private val apiKey: String) : ViewModel() {

    private val geminiClient = GeminiApiClient(apiKey = apiKey)

    private val _uiState = MutableStateFlow(ModelComparisonUiState())
    val uiState: StateFlow<ModelComparisonUiState> = _uiState

    fun onInputChange(newInput: String) {
        _uiState.value = _uiState.value.copy(input = newInput)
    }

    /**
     * Sends the current prompt to all three model tiers concurrently. Each tier is timed out
     * independently so a single slow/stuck model can't hold up the others' results.
     */
    fun runComparison() {
        val prompt = _uiState.value.input.trim()
        if (prompt.isEmpty() || _uiState.value.isRunning) return

        _uiState.value = _uiState.value.copy(
            isRunning = true,
            results = ModelTier.entries.associateWith { TierResult.Loading }
        )

        viewModelScope.launch {
            val jobs = ModelTier.entries.map { tier ->
                async { tier to runTier(tier, prompt) }
            }
            jobs.awaitAll().forEach { (tier, result) ->
                _uiState.update { it.copy(results = it.results + (tier to result)) }
            }
            _uiState.update { it.copy(isRunning = false) }
        }
    }

    private suspend fun runTier(tier: ModelTier, prompt: String): TierResult {
        return try {
            // Hard safety net per tier: no matter what the underlying HTTP client does, a
            // single stuck tier should never leave that card spinning forever.
            withTimeout(125_000) {
                geminiClient.sendMessage(prompt, tier.model).fold(
                    onSuccess = { run ->
                        TierResult.Success(
                            answer = run.answer,
                            usage = run.usage,
                            latencyMs = run.latencyMs,
                            costUsd = tier.estimateCostUsd(run.usage)
                        )
                    },
                    onFailure = { error ->
                        TierResult.Error(error.message ?: "Something went wrong. Please try again.")
                    }
                )
            }
        } catch (e: TimeoutCancellationException) {
            TierResult.Error("Request timed out. Please check your connection and try again.")
        } catch (e: Exception) {
            TierResult.Error(e.message ?: "Something went wrong. Please try again.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        geminiClient.close()
    }
}
