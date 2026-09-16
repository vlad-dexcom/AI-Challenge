package com.example.geminichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.geminichat.agent.Agent
import com.example.geminichat.agent.AgentCatalog
import com.example.geminichat.agent.AgentConfig
import com.example.geminichat.agent.AgentMessage
import com.example.geminichat.agent.AgentRequest
import com.example.geminichat.agent.ContextStrategy
import com.example.geminichat.agent.FactsExtractor
import com.example.geminichat.agent.HistoryCompressor
import com.example.geminichat.agent.LlmAgent
import com.example.geminichat.agent.TokenUsage
import java.util.UUID
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

/** One entry in the Day 10 branch selector — see [ChatViewModel.onBranchSelected]. */
data class BranchOption(val id: String, val name: String)

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
     * to make the token cost of a growing conversation visible as it happens (Day 8). This is
     * per-branch (Day 10): switching branches swaps this total for the target branch's own.
     */
    val dialogTokenTotal: Int = 0,
    /**
     * Day 10: which context-management strategy is active — see [ContextStrategy]. Replaces
     * Day 9's boolean `compressionEnabled` toggle now that there are more than two options
     * (full history / sliding window / facts / summary), all selectable on the same
     * conversation so their token cost and behavior can be compared directly.
     */
    val contextStrategy: ContextStrategy = ContextStrategy.DEFAULT,
    /** The running summary standing in for turns already folded out of [messages] (Summary strategy). */
    val contextSummary: String = "",
    /** How many of the oldest [messages] are already represented by [contextSummary]. */
    val summarizedMessageCount: Int = 0,
    /**
     * Tokens spent on the summarization calls themselves (Day 9), tracked separately from
     * [dialogTokenTotal] so the "cost of compressing" isn't confused with the cost of actual
     * chat turns.
     */
    val compressionTokensTotal: Int = 0,
    /** Day 10 sticky facts key-value memory (Facts strategy) — see [FactsExtractor]. */
    val facts: Map<String, String> = emptyMap(),
    /** Tokens spent extracting/updating [facts] so far, tracked separately (mirrors [compressionTokensTotal]). */
    val factsTokensTotal: Int = 0,
    /** Day 10 branching: every branch available to switch to, including the active one. */
    val branches: List<BranchOption> = listOf(BranchOption(MAIN_BRANCH_ID, MAIN_BRANCH_ID)),
    val currentBranchId: String = MAIN_BRANCH_ID,
    /** Whether a checkpoint has been saved and is ready to be forked into a new branch. */
    val hasCheckpoint: Boolean = false
) {
    companion object {
        const val MAIN_BRANCH_ID = "main"
    }
}

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
    private val factsExtractor = FactsExtractor(client = geminiClient)

    // Restore whatever was last saved so a fresh process picks the conversation back up —
    // the ViewModel no longer starts every run from a blank slate.
    private val restored = historyStore?.load() ?: ChatHistorySnapshot()
    private val restoredAgentConfig = AgentCatalog.byId(restored.selectedAgentId)

    private var agent: Agent = LlmAgent(config = restoredAgentConfig, client = geminiClient)

    // Day 10 branching bookkeeping: branches *other than* the currently active one (whose
    // state lives unpacked in [_uiState]), a pending checkpoint ready to be forked, and enough
    // naming state to keep "Branch N" labels increasing across app restarts.
    private val otherBranches: MutableMap<String, BranchSnapshot> =
        restored.otherBranches.associateBy { it.id }.toMutableMap()
    private var currentBranchId: String = restored.currentBranchId
    private var currentBranchName: String = restored.currentBranchName
    private var checkpoint: BranchSnapshot? = restored.checkpoint
    private var branchCounter: Int = computeInitialBranchCounter(
        otherBranches.values.map { it.name } + currentBranchName + (checkpoint?.name ?: "")
    )

    private val _uiState = MutableStateFlow(
        ChatUiState(
            messages = restored.messages,
            selectedModel = restored.selectedModel,
            agentName = restoredAgentConfig.displayName,
            agentDescription = restoredAgentConfig.description,
            selectedAgentId = restoredAgentConfig.id,
            dialogTokenTotal = restored.dialogTokenTotal,
            contextStrategy = ContextStrategy.byName(restored.contextStrategy),
            contextSummary = restored.summary,
            summarizedMessageCount = restored.summarizedMessageCount,
            compressionTokensTotal = 0,
            facts = restored.facts,
            factsTokensTotal = restored.factsTokensTotal,
            branches = branchOptions(),
            currentBranchId = currentBranchId,
            hasCheckpoint = checkpoint != null
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
     * Switches the active context-management strategy (Day 10) so the same conversation can
     * be A/B compared across full history, sliding window, facts, and summary — everything
     * already accumulated for each mode (summary text, facts map) is kept around either way,
     * ready to be reused if that strategy is selected again.
     */
    fun onContextStrategySelected(strategy: ContextStrategy) {
        _uiState.value = _uiState.value.copy(contextStrategy = strategy)
        persistHistory()
    }

    /**
     * Day 10 branching: snapshots the *active* branch's current state as a checkpoint. Doesn't
     * create a branch by itself — call [onCreateBranchFromCheckpoint] (once or more) afterwards
     * to fork one or more independent branches from this exact point.
     */
    fun onSaveCheckpoint() {
        checkpoint = currentBranchSnapshot()
        _uiState.value = _uiState.value.copy(hasCheckpoint = true)
        persistHistory()
    }

    /**
     * Creates a brand-new branch that starts as an exact copy of the saved [checkpoint] and
     * switches to it. Calling this twice from the same checkpoint (without saving a new one in
     * between) produces two independent siblings sharing the same history up to that point —
     * each then continues on its own from there.
     */
    fun onCreateBranchFromCheckpoint() {
        val source = checkpoint ?: return
        branchCounter += 1
        val newBranch = source.copy(
            id = UUID.randomUUID().toString(),
            name = "Branch $branchCounter"
        )
        otherBranches[currentBranchId] = currentBranchSnapshot()
        loadBranch(newBranch)
        persistHistory()
    }

    /** Day 10 branching: switches the active branch, saving the current one's state first. */
    fun onBranchSelected(branchId: String) {
        if (branchId == currentBranchId) return
        val target = otherBranches[branchId] ?: return
        otherBranches[currentBranchId] = currentBranchSnapshot()
        otherBranches.remove(target.id)
        loadBranch(target)
        persistHistory()
    }

    private fun currentBranchSnapshot(): BranchSnapshot {
        val state = _uiState.value
        return BranchSnapshot(
            id = currentBranchId,
            name = currentBranchName,
            messages = state.messages,
            contextSummary = state.contextSummary,
            summarizedMessageCount = state.summarizedMessageCount,
            facts = state.facts,
            dialogTokenTotal = state.dialogTokenTotal,
            compressionTokensTotal = state.compressionTokensTotal,
            factsTokensTotal = state.factsTokensTotal
        )
    }

    private fun loadBranch(snapshot: BranchSnapshot) {
        currentBranchId = snapshot.id
        currentBranchName = snapshot.name
        _uiState.value = _uiState.value.copy(
            messages = snapshot.messages,
            contextSummary = snapshot.contextSummary,
            summarizedMessageCount = snapshot.summarizedMessageCount,
            facts = snapshot.facts,
            dialogTokenTotal = snapshot.dialogTokenTotal,
            compressionTokensTotal = snapshot.compressionTokensTotal,
            factsTokensTotal = snapshot.factsTokensTotal,
            currentBranchId = snapshot.id,
            branches = branchOptions(snapshot.id, snapshot.name),
            errorMessage = null
        )
    }

    private fun branchOptions(
        activeId: String = currentBranchId,
        activeName: String = currentBranchName
    ): List<BranchOption> {
        val options = otherBranches.values.map { BranchOption(it.id, it.name) } +
            BranchOption(activeId, activeName)
        return options.sortedBy { it.name }
    }

    private fun computeInitialBranchCounter(names: List<String>): Int {
        val pattern = Regex("^Branch (\\d+)$")
        return names.mapNotNull { name -> pattern.find(name)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
    }

    /** Writes the current transcript + selected agent/model so a restart can resume from it. */
    private fun persistHistory() {
        val store = historyStore ?: return
        val state = _uiState.value
        val activeSnapshot = currentBranchSnapshot()
        viewModelScope.launch(Dispatchers.IO) {
            store.save(
                ChatHistorySnapshot(
                    messages = state.messages,
                    selectedAgentId = state.selectedAgentId,
                    selectedModel = state.selectedModel,
                    contextStrategy = state.contextStrategy.name,
                    summary = state.contextSummary,
                    summarizedMessageCount = state.summarizedMessageCount,
                    facts = state.facts,
                    factsTokensTotal = state.factsTokensTotal,
                    dialogTokenTotal = state.dialogTokenTotal,
                    otherBranches = otherBranches.values.toList(),
                    currentBranchId = activeSnapshot.id,
                    currentBranchName = activeSnapshot.name,
                    checkpoint = checkpoint
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
                    val context = prepareRequestContext(history, model, prompt)
                    agent.handle(
                        AgentRequest(
                            userMessage = prompt,
                            history = context.history,
                            modelOverride = model,
                            summary = context.summary,
                            facts = context.facts
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

    /** What actually gets sent to the agent for one turn (Day 10: strategy-dependent). */
    private data class RequestContext(
        val history: List<AgentMessage>,
        val summary: String?,
        val facts: String?
    )

    /**
     * Decides what to actually send as this turn's history/summary/facts, per the currently
     * selected [ContextStrategy]:
     * - [ContextStrategy.FULL_HISTORY]: the full raw [history], nothing else (pre-Day-9
     *   baseline, kept for direct comparison).
     * - [ContextStrategy.SLIDING_WINDOW]: only the last [ContextStrategy.SLIDING_WINDOW_SIZE]
     *   messages, no summary/facts, no extra LLM calls.
     * - [ContextStrategy.SUMMARY]: Day 9's compression — if enough newly aged-out messages
     *   have piled up, folds them into the running summary first (a real LLM call — see
     *   [HistoryCompressor.fold]), then sends the recent tail + summary.
     * - [ContextStrategy.FACTS]: Day 10's sticky facts — merges [prompt] into the facts map via
     *   [FactsExtractor.extract] (a real LLM call), then sends the recent tail + rendered facts
     *   instead of the aged-out history.
     */
    private suspend fun prepareRequestContext(
        history: List<AgentMessage>,
        model: String,
        prompt: String
    ): RequestContext {
        return when (_uiState.value.contextStrategy) {
            ContextStrategy.FULL_HISTORY -> RequestContext(history, null, null)

            ContextStrategy.SLIDING_WINDOW -> RequestContext(
                history.takeLast(ContextStrategy.SLIDING_WINDOW_SIZE),
                null,
                null
            )

            ContextStrategy.SUMMARY -> {
                val state = _uiState.value
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
                    // On failure, silently keep the previous summary/counts and fall through —
                    // the conversation still works, just without folding in this new chunk yet;
                    // the next turn will retry once another chunk's worth has piled up.
                }
                val latestState = _uiState.value
                RequestContext(
                    historyCompressor.recentTail(history),
                    latestState.contextSummary.ifBlank { null },
                    null
                )
            }

            ContextStrategy.FACTS -> {
                val recentContext = history.takeLast(ContextStrategy.SLIDING_WINDOW_SIZE)
                val state = _uiState.value
                factsExtractor.extract(
                    previousFacts = state.facts,
                    recentContext = recentContext,
                    newUserMessage = prompt,
                    model = model
                ).onSuccess { outcome ->
                    _uiState.value = _uiState.value.copy(
                        facts = outcome.facts,
                        factsTokensTotal = _uiState.value.factsTokensTotal + outcome.tokensUsed
                    )
                    persistHistory()
                }
                // On failure, keep the previous facts map and fall through — the conversation
                // still works with whatever facts were already known.
                val latestState = _uiState.value
                RequestContext(
                    recentContext,
                    null,
                    factsExtractor.render(latestState.facts).ifBlank { null }
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        geminiClient.close()
    }
}
