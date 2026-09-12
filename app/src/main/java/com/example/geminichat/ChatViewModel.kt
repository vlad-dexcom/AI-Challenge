package com.example.geminichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.geminichat.agent.Agent
import com.example.geminichat.agent.AgentCatalog
import com.example.geminichat.agent.AgentConfig
import com.example.geminichat.agent.AgentMessage
import com.example.geminichat.agent.AgentRequest
import com.example.geminichat.agent.HistoryCompressor
import com.example.geminichat.agent.LlmAgent
import com.example.geminichat.agent.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ChatMessage(
    val text: String,
    val isFromUser: Boolean,
    /**
     * Token accounting for this turn; only set (in-memory) on agent replies (see
     * [TokenUsage]). Marked [Transient] because [TokenUsage] isn't `@Serializable` and this is
     * ephemeral, recomputed-per-call data — it's simply dropped by [ChatHistoryStore] and
     * comes back `null` for messages restored from a previous app run.
     */
    @Transient
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
    val dialogTokenTotal: Int = 0,
    /**
     * Day 9 context compression: when enabled, only the most recent messages are sent
     * verbatim and older turns are replaced by [contextSummary] (see [HistoryCompressor]).
     * Exposed as a toggle so the same conversation's quality/token cost can be compared
     * with and without compression.
     */
    val compressionEnabled: Boolean = true,
    /** The running summary standing in for turns already folded out of [messages]. */
    val contextSummary: String = "",
    /** How many of the oldest [messages] are already represented by [contextSummary]. */
    val summarizedMessageCount: Int = 0,
    /**
     * Tokens spent on the summarization calls themselves (Day 9), tracked separately from
     * [dialogTokenTotal] so the "cost of compressing" isn't confused with the cost of actual
     * chat turns.
     */
    val compressionTokensTotal: Int = 0
)

/**
 * Holds chat UI state and talks to an [Agent] — never directly to Gemini or any HTTP client.
 * The agent owns its persona/system-instruction/generation config; this ViewModel only knows
 * "send the user's message to the current agent and show what comes back".
 */
class ChatViewModel(
    apiKey: String,
    private val historyStore: ChatHistoryStore? = null,
) : ViewModel() {

    private val geminiClient = GeminiApiClient(apiKey)
    private val historyCompressor = HistoryCompressor(client = geminiClient)

    // Restore whatever was last saved so a fresh process picks the conversation back up —
    // the ViewModel no longer starts every run from a blank slate.
    private val restored = historyStore?.load() ?: ChatHistorySnapshot()
    private val restoredAgentConfig = AgentCatalog.byId(restored.selectedAgentId)

    private var agent: Agent = LlmAgent(config = restoredAgentConfig, client = geminiClient)

    private val _uiState = MutableStateFlow(
        ChatUiState(
            messages = restored.messages,
            selectedModel = restored.selectedModel,
            agentName = restoredAgentConfig.displayName,
            agentDescription = restoredAgentConfig.description,
            selectedAgentId = restoredAgentConfig.id,
            compressionEnabled = restored.compressionEnabled,
            contextSummary = restored.summary,
            summarizedMessageCount = restored.summarizedMessageCount
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState

    fun onInputChange(newInput: String) {
        _uiState.value = _uiState.value.copy(input = newInput)
    }

    fun onModelSelected(model: String) {
        _uiState.value = _uiState.value.copy(selectedModel = model)
        persistHistory()
    }

    fun onAgentSelected(agentId: String) {
        val config = AgentCatalog.byId(agentId)
        agent = LlmAgent(config = config, client = geminiClient)
        _uiState.value = _uiState.value.copy(
            selectedAgentId = config.id,
            agentName = config.displayName,
            agentDescription = config.description
        )
        persistHistory()
    }

    /**
     * Toggles Day 9 context compression on/off, so the same conversation can be A/B compared
     * (quality, token cost) with and without it — the summary already accumulated is kept
     * around either way, ready to be reused if compression is turned back on.
     */
    fun onCompressionToggled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(compressionEnabled = enabled)
        persistHistory()
    }

    /** Writes the current transcript + selected agent/model so a restart can resume from it. */
    private fun persistHistory() {
        val store = historyStore ?: return
        val state = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            store.save(
                ChatHistorySnapshot(
                    messages = state.messages,
                    selectedAgentId = state.selectedAgentId,
                    selectedModel = state.selectedModel,
                    compressionEnabled = state.compressionEnabled,
                    summary = state.contextSummary,
                    summarizedMessageCount = state.summarizedMessageCount
                )
            )
        }
    }

    fun sendMessage() {
        val prompt = _uiState.value.input.trim()
        if (prompt.isEmpty() || _uiState.value.isLoading) return
        val model = _uiState.value.selectedModel

        // Snapshot the conversation so far (before appending this new turn) as the history
        // mixed into the agent's context. This is also what gets persisted (see
        // [ChatHistoryStore]) and reloaded as [restored] on the next app start, so — unlike
        // before — it now survives process death, not just the current ViewModel/app run.
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
        persistHistory()

        viewModelScope.launch {
            try {
                // Hard safety net: no matter what the underlying HTTP client does, the user
                // should never be stuck on the loading indicator forever.
                withTimeout(125_000) {
                    val (recentHistory, summary) = prepareRequestContext(history, model)
                    agent.handle(
                        AgentRequest(
                            userMessage = prompt,
                            history = recentHistory,
                            modelOverride = model,
                            summary = summary
                        )
                    )
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
                            persistHistory()
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

    /**
     * Decides what to actually send as this turn's history/summary (Day 9). When compression
     * is disabled, the full raw [history] is sent as-is (the pre-Day-9 behavior, kept around
     * for a direct quality/token comparison). When enabled:
     * - if enough newly aged-out messages have piled up beyond the recent window, folds them
     *   into the running summary first (a real LLM call — see [HistoryCompressor.fold]),
     *   updating [ChatUiState.contextSummary]/[ChatUiState.summarizedMessageCount] and
     *   accumulating [ChatUiState.compressionTokensTotal].
     * - either way, only the recent tail of [history] is sent verbatim, alongside whatever
     *   summary is currently stored.
     */
    private suspend fun prepareRequestContext(
        history: List<AgentMessage>,
        model: String
    ): Pair<List<AgentMessage>, String?> {
        val state = _uiState.value
        if (!state.compressionEnabled) {
            return history to null
        }

        val foldRange = historyCompressor.pendingFoldRange(history.size, state.summarizedMessageCount)
        if (foldRange != null) {
            val messagesToFold = history.subList(foldRange.first, foldRange.last + 1)
            historyCompressor.fold(
                previousSummary = state.contextSummary.ifBlank { null },
                messagesToFold = messagesToFold,
                model = model
            ).onSuccess { outcome ->
                _uiState.value = _uiState.value.copy(
                    contextSummary = outcome.summary,
                    summarizedMessageCount = foldRange.last + 1,
                    compressionTokensTotal = _uiState.value.compressionTokensTotal + outcome.tokensUsed
                )
                persistHistory()
            }
            // On failure, silently keep the previous summary/counts and fall through — the
            // conversation still works, just without folding in this new chunk yet; the next
            // turn will retry once another chunk's worth of messages has piled up.
        }

        val latestState = _uiState.value
        return historyCompressor.recentTail(history) to latestState.contextSummary.ifBlank { null }
    }

    override fun onCleared() {
        super.onCleared()
        geminiClient.close()
    }
}
