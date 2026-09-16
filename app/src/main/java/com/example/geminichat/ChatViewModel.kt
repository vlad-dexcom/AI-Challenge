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
import com.example.geminichat.agent.memory.LongTermMemoryStore
import com.example.geminichat.agent.memory.MemoryAssembler
import com.example.geminichat.agent.memory.MemoryItem
import com.example.geminichat.agent.memory.MemoryRouter
import com.example.geminichat.agent.memory.MemoryRoutingDecision
import com.example.geminichat.agent.memory.MemorySnapshot
import com.example.geminichat.agent.memory.MemorySource
import com.example.geminichat.agent.memory.WorkingMemoryStore
import com.example.geminichat.agent.profile.ExpertiseLevel
import com.example.geminichat.agent.profile.PreferenceAdvisor
import com.example.geminichat.agent.profile.PreferenceSuggestion
import com.example.geminichat.agent.profile.ProfileField
import com.example.geminichat.agent.profile.ProfileRenderer
import com.example.geminichat.agent.profile.UserProfile
import com.example.geminichat.agent.profile.UserProfileStore
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
    /** Day 10 branching: every branch available to switch to, including the active one. */
    val branches: List<BranchOption> = listOf(BranchOption(MAIN_BRANCH_ID, MAIN_BRANCH_ID)),
    val currentBranchId: String = MAIN_BRANCH_ID,
    /** Whether a checkpoint has been saved and is ready to be forked into a new branch. */
    val hasCheckpoint: Boolean = false,
    /**
     * Day 11 [com.example.geminichat.agent.memory.MemoryLayer.LONG_TERM] layer: durable facts
     * about the *user* (profile, standing decisions, knowledge), stored separately from
     * [workingMemory] and global across branches/agents — see
     * [com.example.geminichat.agent.memory.LongTermMemoryStore].
     */
    val longTermMemory: MemorySnapshot = MemorySnapshot.EMPTY,
    /**
     * Day 11 [com.example.geminichat.agent.memory.MemoryLayer.WORKING] layer: facts about the
     * *current task* only — per branch, cleared independently of [longTermMemory] by "End
     * task" (see [com.example.geminichat.agent.memory.WorkingMemoryStore]).
     */
    val workingMemory: MemorySnapshot = MemorySnapshot.EMPTY,
    /** Tokens spent on [com.example.geminichat.agent.memory.MemoryRouter] calls so far
     * (active branch), tracked separately from [dialogTokenTotal] so the "cost of routing"
     * isn't confused with the cost of actual chat turns. */
    val memoryRoutingTokensTotal: Int = 0,
    /** What the last [com.example.geminichat.agent.memory.MemoryRouter.route] call decided and
     * why — shown in the memory inspector so "what data landed in which layer" is checkable
     * turn by turn, not just inferred from the final state. */
    val lastMemoryDecisions: List<MemoryRoutingDecision> = emptyList(),
    /**
     * Day 12: the single, global, user-editable personalization profile (see
     * [com.example.geminichat.agent.profile.UserProfile]) — distinct from [longTermMemory],
     * which holds facts the agent *learned* rather than preferences the user *declared*.
     * Unlike [longTermMemory]/[workingMemory] there is no catalog to switch between; this is
     * the one profile, edited in place.
     */
    val userProfile: UserProfile = UserProfile.EMPTY,
    /** A pending [PreferenceAdvisor] suggestion awaiting the user's "Apply"/"Dismiss" — never
     * applied to [userProfile] automatically (see [ChatViewModel.onApplySuggestion]). */
    val pendingPreferenceSuggestion: PreferenceSuggestion? = null,
    /** Tokens spent on [PreferenceAdvisor] calls so far, tracked separately from
     * [dialogTokenTotal] and [memoryRoutingTokensTotal] so the cost of each mechanism is
     * visible on its own. */
    val personalizationTokensTotal: Int = 0
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
    private val longTermMemoryStore: LongTermMemoryStore? = null,
    private val workingMemoryStore: WorkingMemoryStore? = null,
    private val userProfileStore: UserProfileStore? = null,
    debugContextWindowOverrideTokens: Int? = null,
) : ViewModel() {

    private val geminiClient = GeminiApiClient(apiKey, debugContextWindowOverrideTokens)
    private val memoryRouter = MemoryRouter(client = geminiClient)
    private val preferenceAdvisor = PreferenceAdvisor(client = geminiClient)

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

    // Day 11 memory layers: long-term is global (loaded once, not scoped to any branch);
    // working is per-branch (loaded for whichever branch is currently active); the checkpoint's
    // own working memory is kept alongside [checkpoint] so forking a new branch from it carries
    // the right working memory over (see [onCreateBranchFromCheckpoint]).
    private var longTermMemory: MemorySnapshot = longTermMemoryStore?.load() ?: MemorySnapshot.EMPTY
    private var checkpointWorkingMemory: MemorySnapshot =
        if (checkpoint != null) {
            workingMemoryStore?.load(CHECKPOINT_WORKING_MEMORY_KEY) ?: MemorySnapshot.EMPTY
        } else {
            MemorySnapshot.EMPTY
        }

    // Day 12: the single global profile, loaded once (not scoped to any branch, mirroring
    // [longTermMemory]'s "one global value" shape).
    private var userProfile: UserProfile = userProfileStore?.load() ?: UserProfile.EMPTY

    private val _uiState = MutableStateFlow(
        ChatUiState(
            messages = restored.messages,
            selectedModel = restored.selectedModel,
            agentName = restoredAgentConfig.displayName,
            agentDescription = restoredAgentConfig.description,
            selectedAgentId = restoredAgentConfig.id,
            dialogTokenTotal = restored.dialogTokenTotal,
            branches = branchOptions(),
            currentBranchId = currentBranchId,
            hasCheckpoint = checkpoint != null,
            longTermMemory = longTermMemory,
            workingMemory = workingMemoryStore?.load(currentBranchId) ?: MemorySnapshot.EMPTY,
            memoryRoutingTokensTotal = 0,
            userProfile = userProfile,
            personalizationTokensTotal = 0
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState

    companion object {
        /** Reserved [WorkingMemoryStore] key for the pending checkpoint's own working memory —
         * it isn't a real branch, so it can't collide with a [UUID]-based branch id. */
        private const val CHECKPOINT_WORKING_MEMORY_KEY = "__checkpoint__"
    }

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
     * Day 10 branching: snapshots the *active* branch's current state as a checkpoint. Doesn't
     * create a branch by itself — call [onCreateBranchFromCheckpoint] (once or more) afterwards
     * to fork one or more independent branches from this exact point. Day 11: the active
     * branch's working memory is captured alongside it, so a forked branch starts with the
     * same task-in-progress context, not an empty working layer.
     */
    fun onSaveCheckpoint() {
        checkpoint = currentBranchSnapshot()
        checkpointWorkingMemory = _uiState.value.workingMemory
        workingMemoryStore?.save(CHECKPOINT_WORKING_MEMORY_KEY, checkpointWorkingMemory)
        _uiState.value = _uiState.value.copy(hasCheckpoint = true)
        persistHistory()
    }

    /**
     * Creates a brand-new branch that starts as an exact copy of the saved [checkpoint] and
     * switches to it. Calling this twice from the same checkpoint (without saving a new one in
     * between) produces two independent siblings sharing the same history up to that point —
     * each then continues on its own from there. Day 11: both siblings also start from the
     * same [checkpointWorkingMemory], then diverge independently as each branch's own task
     * progresses.
     */
    fun onCreateBranchFromCheckpoint() {
        val source = checkpoint ?: return
        branchCounter += 1
        val newBranch = source.copy(
            id = UUID.randomUUID().toString(),
            name = "Branch $branchCounter"
        )
        persistWorkingMemory(currentBranchId, _uiState.value.workingMemory)
        otherBranches[currentBranchId] = currentBranchSnapshot()
        persistWorkingMemory(newBranch.id, checkpointWorkingMemory)
        loadBranch(newBranch, workingMemoryOverride = checkpointWorkingMemory)
        persistHistory()
    }

    /** Day 10 branching: switches the active branch, saving the current one's state first. */
    fun onBranchSelected(branchId: String) {
        if (branchId == currentBranchId) return
        val target = otherBranches[branchId] ?: return
        persistWorkingMemory(currentBranchId, _uiState.value.workingMemory)
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
            dialogTokenTotal = state.dialogTokenTotal,
            memoryRoutingTokensTotal = state.memoryRoutingTokensTotal
        )
    }

    /**
     * Loads [snapshot] as the active branch. Day 11's working memory isn't part of
     * [BranchSnapshot] (it lives in its own [WorkingMemoryStore] file — see [MemoryLayer]), so
     * it's loaded separately here: from [workingMemoryOverride] when the caller already has it
     * on hand (forking a new branch from a checkpoint), otherwise from [workingMemoryStore] by
     * branch id (switching to an existing branch).
     */
    private fun loadBranch(snapshot: BranchSnapshot, workingMemoryOverride: MemorySnapshot? = null) {
        currentBranchId = snapshot.id
        currentBranchName = snapshot.name
        val working = workingMemoryOverride
            ?: workingMemoryStore?.load(snapshot.id)
            ?: MemorySnapshot.EMPTY
        _uiState.value = _uiState.value.copy(
            messages = snapshot.messages,
            dialogTokenTotal = snapshot.dialogTokenTotal,
            memoryRoutingTokensTotal = snapshot.memoryRoutingTokensTotal,
            workingMemory = working,
            lastMemoryDecisions = emptyList(),
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

    /** Persists [snapshot] as [branchId]'s working memory (Day 11), independently of
     * [ChatHistoryStore] — see [WorkingMemoryStore]. */
    private fun persistWorkingMemory(branchId: String, snapshot: MemorySnapshot) {
        workingMemoryStore?.save(branchId, snapshot)
    }

    /** Persists both Day 11 memory layers for the *active* branch: long-term globally (see
     * [LongTermMemoryStore]) and working memory keyed by [currentBranchId] (see
     * [WorkingMemoryStore]) — always in their own files, never mixed into [ChatHistoryStore]. */
    private fun persistMemoryLayers() {
        val state = _uiState.value
        longTermMemoryStore?.save(state.longTermMemory)
        persistWorkingMemory(currentBranchId, state.workingMemory)
    }

    /**
     * Day 11: manually promotes a [MemoryLayer.WORKING] item to [MemoryLayer.LONG_TERM] — the
     * "явный выбор" escape hatch when the user wants something to outlive the current task.
     * The promoted item is marked [MemorySource.USER] and pinned so [MemoryRouter] can never
     * later silently overwrite or drop it.
     */
    fun onPromoteToLongTerm(key: String) {
        val state = _uiState.value
        val item = state.workingMemory.items[key] ?: return
        val pinnedItem = item.copy(source = MemorySource.USER, pinned = true)
        _uiState.value = state.copy(
            workingMemory = MemorySnapshot(state.workingMemory.items - key),
            longTermMemory = MemorySnapshot(state.longTermMemory.items + (key to pinnedItem))
        )
        persistMemoryLayers()
    }

    /** Day 11: manually adds/overwrites a long-term item (e.g. correcting the router, or
     * recording something it never had a chance to see). Always pinned. */
    fun onAddLongTermItem(rawKey: String, rawValue: String) {
        val key = rawKey.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val value = rawValue.trim()
        if (key.isEmpty() || value.isEmpty()) return
        val item = MemoryItem(key = key, value = value, source = MemorySource.USER, pinned = true)
        val state = _uiState.value
        _uiState.value = state.copy(
            longTermMemory = MemorySnapshot(state.longTermMemory.items + (key to item))
        )
        persistMemoryLayers()
    }

    /** Day 11: manually deletes one long-term item. */
    fun onDeleteLongTermItem(key: String) {
        val state = _uiState.value
        _uiState.value = state.copy(longTermMemory = MemorySnapshot(state.longTermMemory.items - key))
        persistMemoryLayers()
    }

    /** Day 11: manually deletes one working-memory item. */
    fun onDeleteWorkingItem(key: String) {
        val state = _uiState.value
        _uiState.value = state.copy(workingMemory = MemorySnapshot(state.workingMemory.items - key))
        persistMemoryLayers()
    }

    /**
     * Day 11 "End task": clears [MemoryLayer.WORKING] only — [MemoryLayer.LONG_TERM] and the
     * dialog itself ([MemoryLayer.SHORT_TERM]) are left untouched, demonstrating that the three
     * layers really are independent.
     */
    fun onEndTask() {
        _uiState.value = _uiState.value.copy(
            workingMemory = MemorySnapshot.EMPTY,
            lastMemoryDecisions = emptyList()
        )
        persistMemoryLayers()
    }

    /**
     * Day 11 "Clear dialog": clears [MemoryLayer.SHORT_TERM] (transcript + Day 9 summary state)
     * only — [MemoryLayer.WORKING] and [MemoryLayer.LONG_TERM] are left untouched, so the agent
     * still remembers the current task and the user's profile in a brand new conversation.
     */
    fun onClearDialog() {
        _uiState.value = _uiState.value.copy(
            messages = emptyList(),
            dialogTokenTotal = 0,
            errorMessage = null
        )
        persistHistory()
    }

    /**
     * Day 12: overwrites a single scalar field of the profile (everything except
     * [UserProfile.constraints], which is a set — see [onAddConstraint]/[onRemoveConstraint]).
     * Applied immediately and persisted; there is no separate "save" step in the UI.
     */
    fun onProfileFieldChange(field: ProfileField, rawValue: String) {
        val current = _uiState.value.userProfile
        val updated = applyFieldValue(current, field, rawValue) ?: return
        _uiState.value = _uiState.value.copy(userProfile = updated)
        persistProfile()
    }

    /** Day 12: adds one entry to [UserProfile.constraints] (a set, not overwritten like other
     * fields); no-op for a blank or already-present constraint. */
    fun onAddConstraint(rawConstraint: String) {
        val constraint = rawConstraint.trim()
        if (constraint.isEmpty()) return
        val current = _uiState.value.userProfile
        if (current.constraints.any { it.equals(constraint, ignoreCase = true) }) return
        _uiState.value = _uiState.value.copy(
            userProfile = current.copy(constraints = current.constraints + constraint)
        )
        persistProfile()
    }

    /** Day 12: removes one entry from [UserProfile.constraints] by exact text match. */
    fun onRemoveConstraint(constraint: String) {
        val current = _uiState.value.userProfile
        _uiState.value = _uiState.value.copy(
            userProfile = current.copy(constraints = current.constraints - constraint)
        )
        persistProfile()
    }

    /** Day 12: overwrites the whole profile with one of [UserProfile.PRESETS] in one step —
     * lets the same conversation be re-run under maximally different profiles to check that
     * personalization is actually picked up (see the Settings screen's preset row). */
    fun onApplyPreset(preset: UserProfile) {
        _uiState.value = _uiState.value.copy(userProfile = preset, pendingPreferenceSuggestion = null)
        persistProfile()
    }

    /** Day 12: resets the profile back to [UserProfile.EMPTY] — the agent's prompt then
     * matches its pre-Day-12 behavior exactly (see [ProfileRenderer.render]). */
    fun onResetProfile() {
        _uiState.value = _uiState.value.copy(
            userProfile = UserProfile.EMPTY,
            pendingPreferenceSuggestion = null
        )
        persistProfile()
    }

    /**
     * Day 12: applies the pending [PreferenceAdvisor] suggestion the user approved, then
     * clears it — this is the *only* place a [PreferenceAdvisor] suggestion ever changes
     * [UserProfile]; it is never applied automatically (see [prepareRequestContext]).
     */
    fun onApplySuggestion() {
        val suggestion = _uiState.value.pendingPreferenceSuggestion ?: return
        val current = _uiState.value.userProfile
        val updated = when (suggestion.field) {
            ProfileField.ADD_CONSTRAINT -> current.copy(constraints = current.constraints + suggestion.value)
            ProfileField.REMOVE_CONSTRAINT -> current.copy(constraints = current.constraints - suggestion.value)
            else -> applyFieldValue(current, suggestion.field, suggestion.value) ?: current
        }
        _uiState.value = _uiState.value.copy(userProfile = updated, pendingPreferenceSuggestion = null)
        persistProfile()
    }

    /** Day 12: discards the pending suggestion without changing the profile. */
    fun onDismissSuggestion() {
        _uiState.value = _uiState.value.copy(pendingPreferenceSuggestion = null)
    }

    /** Applies [rawValue] to [field] on [profile], returning the updated copy, or `null` for
     * the two constraint pseudo-fields (handled separately — see [onApplySuggestion]). */
    private fun applyFieldValue(profile: UserProfile, field: ProfileField, rawValue: String): UserProfile? {
        val value = rawValue.trim()
        return when (field) {
            ProfileField.DISPLAY_NAME -> profile.copy(displayName = value)
            ProfileField.ABOUT -> profile.copy(about = value)
            ProfileField.LANGUAGE -> profile.copy(language = value)
            ProfileField.EXPERTISE -> profile.copy(
                expertise = ExpertiseLevel.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            )
            ProfileField.TONE -> profile.copy(tone = value)
            ProfileField.FORMAT -> profile.copy(format = value)
            ProfileField.MAX_ANSWER_SENTENCES -> profile.copy(maxAnswerSentences = value.toIntOrNull())
            ProfileField.NOTES -> profile.copy(notes = value)
            ProfileField.ADD_CONSTRAINT, ProfileField.REMOVE_CONSTRAINT -> null
        }
    }

    /** Persists the single global profile (Day 12), independently of every memory layer and
     * of [persistHistory] — see [UserProfileStore]. */
    private fun persistProfile() {
        userProfileStore?.save(_uiState.value.userProfile)
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
                            longTermMemory = context.longTermMemory,
                            workingMemory = context.workingMemory,
                            userProfile = context.userProfile
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

    /** What actually gets sent to the agent for one turn: a recent raw tail plus both memory
     * layers and the Day 12 profile block. */
    private data class RequestContext(
        val history: List<AgentMessage>,
        val longTermMemory: String? = null,
        val workingMemory: String? = null,
        val userProfile: String? = null
    )

    /**
     * Day 11's explicit memory model: [MemoryRouter.route] classifies [prompt] into
     * working/long-term memory (a real LLM call, grounded on just
     * [MemoryRouter.ROUTER_CONTEXT_SIZE] recent messages — enough to disambiguate the newest
     * message without repaying the cost of a larger window), then [MemoryAssembler] renders
     * both layers as separate blocks, sent alongside a recent raw tail of [history]
     * ([MemoryRouter.RECENT_CONTEXT_SIZE] messages) instead of the full, ever-growing
     * transcript.
     *
     * Day 12: also renders the current [ChatUiState.userProfile] (unconditionally, on every
     * turn — see [ProfileRenderer.render]) and separately calls [PreferenceAdvisor] to check
     * whether [prompt] states a new personalization preference; any suggestion is surfaced as
     * [ChatUiState.pendingPreferenceSuggestion] for the user to approve, never applied here.
     */
    private suspend fun prepareRequestContext(
        history: List<AgentMessage>,
        model: String,
        prompt: String
    ): RequestContext {
        val agentHistoryTail = history.takeLast(MemoryRouter.RECENT_CONTEXT_SIZE)
        val routerContext = history.takeLast(MemoryRouter.ROUTER_CONTEXT_SIZE)
        val state = _uiState.value
        val turn = history.count { it.role == AgentMessage.Role.USER } + 1
        memoryRouter.route(
            previousWorking = state.workingMemory,
            previousLongTerm = state.longTermMemory,
            recentContext = routerContext,
            newUserMessage = prompt,
            turn = turn,
            model = model
        ).onSuccess { outcome ->
            _uiState.value = _uiState.value.copy(
                workingMemory = outcome.working,
                longTermMemory = outcome.longTerm,
                memoryRoutingTokensTotal = _uiState.value.memoryRoutingTokensTotal + outcome.tokensUsed,
                lastMemoryDecisions = outcome.decisions
            )
            persistMemoryLayers()
        }
        // On failure, keep the previous layers unchanged and fall through — the conversation
        // still works with whatever memory was already known.
        val latestMemoryState = _uiState.value
        val assembled = MemoryAssembler.assemble(
            longTerm = latestMemoryState.longTermMemory,
            working = latestMemoryState.workingMemory
        )

        // Day 12: a separate LLM call, independent of memory routing, checks whether this turn
        // states a personalization preference. On failure or "no preference stated" it simply
        // leaves [ChatUiState.pendingPreferenceSuggestion] as-is — never blocks the chat turn.
        preferenceAdvisor.suggest(profile = latestMemoryState.userProfile, newUserMessage = prompt, model = model)
            .onSuccess { outcome ->
                _uiState.value = _uiState.value.copy(
                    personalizationTokensTotal = _uiState.value.personalizationTokensTotal + outcome.tokensUsed,
                    pendingPreferenceSuggestion = outcome.suggestion ?: _uiState.value.pendingPreferenceSuggestion
                )
            }

        val profileBlock = ProfileRenderer.render(latestMemoryState.userProfile)
        return RequestContext(
            history = agentHistoryTail,
            longTermMemory = assembled.longTermBlock.ifEmpty { null },
            workingMemory = assembled.workingBlock.ifEmpty { null },
            userProfile = profileBlock.ifEmpty { null }
        )
    }

    override fun onCleared() {
        super.onCleared()
        geminiClient.close()
    }
}
