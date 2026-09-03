package com.example.geminichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class ChatMessage(
    val text: String,
    val isFromUser: Boolean
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedModel: String = GeminiApiClient.DEFAULT_MODEL,
    val availableModels: List<String> = GeminiApiClient.AVAILABLE_MODELS,
    val restrictedModeEnabled: Boolean = false,
    val enabledLimitations: Set<Limitation> = Limitation.entries.toSet()
)

class ChatViewModel(private val apiKey: String) : ViewModel() {

    private val geminiClient = GeminiApiClient(apiKey = apiKey)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    fun onInputChange(newInput: String) {
        _uiState.value = _uiState.value.copy(input = newInput)
    }

    fun onModelSelected(model: String) {
        _uiState.value = _uiState.value.copy(selectedModel = model)
    }

    fun onRestrictedModeToggled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(restrictedModeEnabled = enabled)
    }

    fun onLimitationToggled(limitation: Limitation, enabled: Boolean) {
        val current = _uiState.value.enabledLimitations
        val updated = if (enabled) current + limitation else current - limitation
        _uiState.value = _uiState.value.copy(enabledLimitations = updated)
    }

    fun sendMessage() {
        val prompt = _uiState.value.input.trim()
        if (prompt.isEmpty() || _uiState.value.isLoading) return
        val model = _uiState.value.selectedModel
        val restrictedModeEnabled = _uiState.value.restrictedModeEnabled
        // The master switch gates everything: when it's off, no limitation is applied
        // regardless of which chips are individually selected.
        val activeLimitations = if (restrictedModeEnabled) {
            _uiState.value.enabledLimitations
        } else {
            emptySet()
        }

        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + ChatMessage(prompt, isFromUser = true),
            input = "",
            isLoading = true,
            errorMessage = null
        )

        viewModelScope.launch {
            try {
                // Hard safety net: no matter what the underlying HTTP client does, the user
                // should never be stuck on the loading indicator forever. Kept comfortably
                // above GeminiApiClient's own request timeout (120s) so that timeout — which
                // produces a clearer, more specific error message — has a chance to fire first.
                withTimeout(125_000) {
                    geminiClient.sendMessage(prompt, model, restrictedModeEnabled, activeLimitations)
                        .onSuccess { answer ->
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + ChatMessage(answer, isFromUser = false),
                                isLoading = false
                            )
                        }
                        .onFailure { error ->
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                errorMessage = error.message ?: "Something went wrong. Please try again."
                            )
                        }
                }
            } catch (e: TimeoutCancellationException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Request timed out. Please check your connection and try again."
                )
            } catch (e: Exception) {
                // Safety net: GeminiApiClient.sendMessage should already catch everything and
                // return a Result, but guard here too so the user is never left without feedback.
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Something went wrong. Please try again."
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        geminiClient.close()
    }
}
