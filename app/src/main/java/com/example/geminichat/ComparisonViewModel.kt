package com.example.geminichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class ModeStatus { Idle, Loading, Success, Error }

data class ModeResult(
    val status: ModeStatus = ModeStatus.Idle,
    val answer: String? = null,
    val generatedPrompt: String? = null,
    val error: String? = null,
    val durationMs: Long? = null
)

data class ComparisonUiState(
    val question: String = "",
    val isRunning: Boolean = false,
    val selectedModel: String = GeminiApiClient.DEFAULT_MODEL,
    val availableModels: List<String> = GeminiApiClient.AVAILABLE_MODELS,
    val results: Map<ThinkingMode, ModeResult> = ThinkingMode.entries.associateWith { ModeResult() }
)

/**
 * Drives the Comparison screen: a single user task is fanned out to all four [ThinkingMode]s
 * as independent, concurrent API calls. Each mode updates only its own slot in
 * [ComparisonUiState.results], so a slow or failing mode never blocks or corrupts the others.
 */
class ComparisonViewModel(private val apiKey: String) : ViewModel() {

    private val geminiClient = GeminiApiClient(apiKey = apiKey)

    private val _uiState = MutableStateFlow(ComparisonUiState())
    val uiState: StateFlow<ComparisonUiState> = _uiState

    private var runJob: Job? = null

    // Each mode gets its own timeout budget; Meta-prompt makes two sequential calls, so it
    // needs one such budget per call rather than a single shared deadline for both.
    private val perCallTimeoutMs = 35_000L

    fun onQuestionChange(newQuestion: String) {
        _uiState.value = _uiState.value.copy(question = newQuestion)
    }

    fun onModelSelected(model: String) {
        _uiState.value = _uiState.value.copy(selectedModel = model)
    }

    fun clearResults() {
        _uiState.value = _uiState.value.copy(
            results = ThinkingMode.entries.associateWith { ModeResult() }
        )
    }

    fun cancelAll() {
        runJob?.cancel()
        runJob = null
        _uiState.update { state ->
            state.copy(
                isRunning = false,
                results = state.results.mapValues { (_, result) ->
                    if (result.status == ModeStatus.Loading) {
                        result.copy(status = ModeStatus.Idle)
                    } else {
                        result
                    }
                }
            )
        }
    }

    fun runAll() {
        val question = _uiState.value.question.trim()
        if (question.isEmpty() || _uiState.value.isRunning) return
        val model = _uiState.value.selectedModel

        _uiState.value = _uiState.value.copy(
            isRunning = true,
            results = ThinkingMode.entries.associateWith { ModeResult(status = ModeStatus.Loading) }
        )

        runJob = viewModelScope.launch {
            val jobs = ThinkingMode.entries.map { mode ->
                launch { runMode(mode, question, model) }
            }
            jobs.forEach { it.join() }
            _uiState.update { it.copy(isRunning = false) }
            runJob = null
        }
    }

    private suspend fun runMode(mode: ThinkingMode, question: String, model: String) {
        val startedAt = System.currentTimeMillis()

        val result = try {
            when (mode) {
                ThinkingMode.MetaPrompt -> runMetaPrompt(question, model)
                else -> {
                    val prompt = mode.buildPrompt(question)
                    val answer = withTimeout(perCallTimeoutMs) {
                        geminiClient.sendMessage(prompt, model)
                    }.getOrThrow()
                    ModeResult(status = ModeStatus.Success, answer = answer)
                }
            }
        } catch (e: TimeoutCancellationException) {
            ModeResult(status = ModeStatus.Error, error = "Request timed out. Please try again.")
        } catch (e: Exception) {
            ModeResult(status = ModeStatus.Error, error = e.message ?: "Something went wrong. Please try again.")
        }

        val durationMs = System.currentTimeMillis() - startedAt
        updateResult(mode, result.copy(durationMs = durationMs))
    }

    private suspend fun runMetaPrompt(question: String, model: String): ModeResult {
        val metaRequest = ThinkingMode.buildMetaRequest(question)

        val generatedPrompt = withTimeout(perCallTimeoutMs) {
            geminiClient.sendMessage(metaRequest, model)
        }.getOrElse { error ->
            return ModeResult(
                status = ModeStatus.Error,
                error = error.message ?: "Failed to generate a prompt. Please try again."
            )
        }

        // Surface the generated prompt immediately so the user sees progress while the
        // second call is in flight.
        updateResult(
            ThinkingMode.MetaPrompt,
            ModeResult(status = ModeStatus.Loading, generatedPrompt = generatedPrompt)
        )

        val answer = withTimeout(perCallTimeoutMs) {
            geminiClient.sendMessage(generatedPrompt, model)
        }.getOrElse { error ->
            return ModeResult(
                status = ModeStatus.Error,
                generatedPrompt = generatedPrompt,
                error = error.message ?: "Something went wrong. Please try again."
            )
        }

        return ModeResult(status = ModeStatus.Success, answer = answer, generatedPrompt = generatedPrompt)
    }

    private fun updateResult(mode: ThinkingMode, result: ModeResult) {
        _uiState.update { state ->
            state.copy(results = state.results + (mode to result))
        }
    }

    override fun onCleared() {
        super.onCleared()
        geminiClient.close()
    }
}
