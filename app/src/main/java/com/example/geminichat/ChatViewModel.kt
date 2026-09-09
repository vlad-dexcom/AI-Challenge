package com.example.geminichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.geminichat.agent.Agent
import com.example.geminichat.agent.AgentCatalog
import com.example.geminichat.agent.AgentConfig
import com.example.geminichat.agent.AgentMessage
import com.example.geminichat.agent.AgentRequest
import com.example.geminichat.agent.LlmAgent
import com.example.geminichat.agent.TokenUsage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class ChatMessage(
    val text: String,
    val isFromUser: Boolean,
    /** Token accounting for this turn; only set on agent replies (see [TokenUsage]). */
    val tokenUsage: TokenUsage? = null
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedModel: String = GeminiApiClient.DEFAULT_MODEL,
    val availableModels: List<String> = GeminiApiClient.AVAILABLE_MODELS,
    val agentName: String = AgentCatalog.DEFAULT.displayName,
    val agentDescription: String = AgentCatalog.DEFAULT.description,
    val selectedAgentId: String = AgentCatalog.DEFAULT.id,
    val availableAgents: List<AgentConfig> = AgentCatalog.ALL,
    /**
     * Running total of every [TokenUsage.totalTokens] in this dialog so far — shown in the UI
     * to make the token cost of a growing conversation visible as it happens (Day 8).
     */
    val dialogTokenTotal: Int = 0
)

/**
 * Holds chat UI state and talks to an [Agent] — never directly to Gemini or any HTTP client.
 * The agent owns its persona/system-instruction/generation config; this ViewModel only knows
 * "send the user's message to the current agent and show what comes back".
 */
class ChatViewModel(private val apiKey: String) : ViewModel() {

    private val geminiClient = GeminiApiClient(apiKey = apiKey)

    private var agent: Agent = LlmAgent(config = AgentCatalog.DEFAULT, client = geminiClient)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    fun onInputChange(newInput: String) {
        _uiState.value = _uiState.value.copy(input = newInput)
    }

    fun onModelSelected(model: String) {
        _uiState.value = _uiState.value.copy(selectedModel = model)
    }

    fun onAgentSelected(agentId: String) {
        val config = AgentCatalog.byId(agentId)
        agent = LlmAgent(config = config, client = geminiClient)
        _uiState.value = _uiState.value.copy(
            selectedAgentId = config.id,
            agentName = config.displayName,
            agentDescription = config.description
        )
    }

    fun sendMessage() {
        val prompt = _uiState.value.input.trim()
        if (prompt.isEmpty() || _uiState.value.isLoading) return
        val model = _uiState.value.selectedModel

        // Snapshot the conversation so far (before appending this new turn) as the history
        // mixed into the agent's context — kept in memory only for the lifetime of this
        // ViewModel/app process, not persisted across restarts.
        val history = _uiState.value.messages.map { message ->
            AgentMessage(
                role = if (message.isFromUser) AgentMessage.Role.USER else AgentMessage.Role.AGENT,
                text = message.text
            )
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
                // should never be stuck on the loading indicator forever.
                withTimeout(125_000) {
                    agent.handle(AgentRequest(userMessage = prompt, history = history, modelOverride = model))
                        .onSuccess { response ->
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + ChatMessage(
                                    text = response.text,
                                    isFromUser = false,
                                    tokenUsage = response.tokenUsage
                                ),
                                isLoading = false,
                                dialogTokenTotal = _uiState.value.dialogTokenTotal + response.tokenUsage.totalTokens
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
                // Safety net: Agent.handle should already catch everything and return a
                // Result, but guard here too so the user is never left without feedback.
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
