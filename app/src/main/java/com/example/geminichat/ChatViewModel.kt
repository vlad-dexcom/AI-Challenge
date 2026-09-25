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
import com.example.geminichat.agent.invariant.Invariant
import com.example.geminichat.agent.invariant.InvariantCategory
import com.example.geminichat.agent.invariant.InvariantChangeResult
import com.example.geminichat.agent.invariant.InvariantGuard
import com.example.geminichat.agent.invariant.InvariantPreset
import com.example.geminichat.agent.invariant.InvariantRenderer
import com.example.geminichat.agent.invariant.InvariantRules
import com.example.geminichat.agent.invariant.InvariantSet
import com.example.geminichat.agent.invariant.InvariantStore
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
import com.example.geminichat.agent.task.TaskEvent
import com.example.geminichat.agent.task.TaskStage
import com.example.geminichat.agent.task.TaskStageGuard
import com.example.geminichat.agent.task.TaskState
import com.example.geminichat.agent.task.TaskStateAdvisor
import com.example.geminichat.agent.task.TaskStateMachine
import com.example.geminichat.agent.task.TaskStateRenderer
import com.example.geminichat.agent.task.TaskStateStore
import com.example.geminichat.agent.task.TaskTransitionAction
import com.example.geminichat.agent.task.TaskTransitionLogStore
import com.example.geminichat.agent.task.TaskTransitionRecord
import com.example.geminichat.agent.task.TaskTransitionSuggestion
import com.example.geminichat.agent.task.TaskTransitionTable
import com.example.geminichat.agent.task.TransitionResult
import com.example.geminichat.agent.task.ValidationOutcome
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val tokenUsage: TokenUsage? = null,
    /**
     * Day 14: non-empty when this reply is a deterministic refusal produced by
     * [com.example.geminichat.agent.invariant.InvariantGuard] (see
     * [com.example.geminichat.agent.AgentResponse.refusedByInvariantIds]). Unlike [tokenUsage]
     * this *is* serialized, so the refusal badge survives a restore from
     * [ChatHistoryStore] just like the message text does.
     */
    val refusedByInvariantIds: List<String> = emptyList(),
    /**
     * Day 15: non-null when this reply is a deterministic refusal produced by
     * [com.example.geminichat.agent.task.TaskStageGuard]. Serialized so it survives a restore.
     */
    val blockedByStageName: String? = null
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
    val personalizationTokensTotal: Int = 0,
    /**
     * Day 13: the formalized state of the current task (stage, current step, expected next
     * action, pause flag) — see [com.example.geminichat.agent.task.TaskState]. Per branch, like
     * [workingMemory], and [TaskState.NONE] when no task has been started on this branch.
     */
    val taskState: TaskState = TaskState.NONE,
    /** A pending [TaskStateAdvisor] suggestion awaiting the user's "Apply"/"Dismiss" — never
     * applied to [taskState] automatically (see [ChatViewModel.onApplyTaskTransitionSuggestion]). */
    val pendingTaskTransitionSuggestion: TaskTransitionSuggestion? = null,
    /** Tokens spent on [TaskStateAdvisor] calls so far, tracked separately from the other
     * per-mechanism token totals above. */
    val taskStateAdvisorTokensTotal: Int = 0,
    /**
     * Day 14: hard, non-negotiable rules the agent enforces on every turn (see
     * [com.example.geminichat.agent.invariant.Invariant]) — global across branches/agents, like
     * [userProfile], and stored in its own file (see
     * [com.example.geminichat.agent.invariant.InvariantStore]) completely outside the dialog.
     */
    val invariants: InvariantSet = InvariantSet.DEFAULTS,
    /**
     * Day 15: transition journal entries for the current branch.
     */
    val taskTransitionHistory: List<TaskTransitionRecord> = emptyList(),
    /**
     * Day 15: set of valid actions allowed by the state machine given [taskState].
     */
    val allowedTaskEvents: Set<TaskEvent> = emptySet()
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
    private val taskStateStore: TaskStateStore? = null,
    private val invariantStore: InvariantStore? = null,
    private val taskTransitionLogStore: TaskTransitionLogStore? = null,
    debugContextWindowOverrideTokens: Int? = null,
) : ViewModel() {

    private val geminiClient = GeminiApiClient(apiKey, debugContextWindowOverrideTokens)
    private val memoryRouter = MemoryRouter(client = geminiClient)
    private val preferenceAdvisor = PreferenceAdvisor(client = geminiClient)
    private val taskStateAdvisor = TaskStateAdvisor(client = geminiClient)

    // Day 17: one long-lived gateway to our own fitness MCP server, reused across turns/agent
    // switches (see [McpToolCallingAgent], which connects it lazily and caches the tool list).
    private val fitnessMcpGateway = com.example.geminichat.mcp.KotlinSdkMcpGateway()

    /**
     * Builds the [Agent] behavior for [config]: every persona uses the plain [LlmAgent] except
     * [AgentCatalog.FITNESS_MCP_COACH] (Day 17), which needs [McpToolCallingAgent] instead so it
     * can call real tools on [fitnessMcpGateway]. Centralized here so every construction site
     * below (initial state, [onAgentSelected], [rebuildAgent]) picks the right behavior.
     */
    private fun buildAgent(config: AgentConfig): Agent =
        if (config.id == AgentCatalog.FITNESS_MCP_COACH.id) {
            com.example.geminichat.agent.mcp.McpToolCallingAgent(
                config = config,
                client = geminiClient,
                mcpGateway = fitnessMcpGateway,
                serverUrl = com.example.geminichat.mcp.McpConfig.FITNESS_SERVER_URL
            )
        } else {
            LlmAgent(config = config, client = geminiClient, invariants = invariants)
        }

    // Restore whatever was last saved so a fresh process picks the conversation back up —
    // the ViewModel no longer starts every run from a blank slate.
    private val restored = historyStore?.load() ?: ChatHistorySnapshot()
    private val restoredAgentConfig = AgentCatalog.byId(restored.selectedAgentId)

    // Day 14: the single global set of invariants, loaded once — like [userProfile], but the
    // agent needs it *at construction time* (see [rebuildAgent]) since [InvariantGuard] runs
    // inside [LlmAgent.handle], not in the ViewModel.
    private var invariants: InvariantSet = invariantStore?.load() ?: InvariantSet.DEFAULTS

    private var agent: Agent = buildAgent(restoredAgentConfig)

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

    // Day 13: the checkpoint's own task state, kept alongside [checkpointWorkingMemory] so a
    // branch forked from a checkpoint starts at the exact same stage/step, not a reset task.
    private var checkpointTaskState: TaskState =
        if (checkpoint != null) {
            taskStateStore?.load(CHECKPOINT_WORKING_MEMORY_KEY) ?: TaskState.NONE
        } else {
            TaskState.NONE
        }

    // Day 12: the single global profile, loaded once (not scoped to any branch, mirroring
    // [longTermMemory]'s "one global value" shape).
    private var userProfile: UserProfile = userProfileStore?.load() ?: UserProfile.EMPTY

    private val initialTaskState = taskStateStore?.load(currentBranchId) ?: TaskState.NONE

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
            personalizationTokensTotal = 0,
            taskState = initialTaskState,
            taskStateAdvisorTokensTotal = 0,
            invariants = invariants,
            taskTransitionHistory = taskTransitionLogStore?.load(currentBranchId) ?: emptyList(),
            allowedTaskEvents = TaskTransitionTable.allowedEvents(initialTaskState)
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
        agent = buildAgent(config)
        _uiState.value = _uiState.value.copy(
            selectedAgentId = config.id,
            agentName = config.displayName,
            agentDescription = config.description
        )
        persistHistory()
    }

    /** Day 14: rebuilds [agent] with the current [invariants] set, keeping its current persona —
     * called after every change to the set (toggle/add/delete/preset/reset) since
     * [com.example.geminichat.agent.invariant.InvariantGuard] runs *inside*
     * [com.example.geminichat.agent.LlmAgent.handle], not in this ViewModel. Enforcement lives
     * in the agent, per the Day 14 requirement, not in the UI layer. */
    private fun rebuildAgent() {
        agent = buildAgent(agent.config)
    }

    /** Persists the current global [invariants] set to its own file, independent of every other
     * store (see [InvariantStore]). */
    private fun persistInvariants() {
        invariantStore?.save(invariants)
    }

    /** Day 14: turns [id] on or off, unless it's [Invariant.locked] — routed through
     * [InvariantRules] so a rejection (e.g. trying to disable a locked invariant) always
     * explains why instead of silently doing nothing. */
    fun onToggleInvariant(id: String, enabled: Boolean) {
        applyInvariantChange(InvariantRules.setEnabled(invariants, id, enabled))
    }

    /** Day 14: adds a brand-new invariant defined in the UI. Rejected (with a reason) if [id]
     * is blank/duplicate or [statement] is blank — see [InvariantRules.add]. */
    fun onAddInvariant(
        id: String,
        category: InvariantCategory,
        statement: String,
        rationale: String,
        alternative: String,
        triggersCsv: String
    ) {
        val triggers = triggersCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        applyInvariantChange(
            InvariantRules.add(
                invariants,
                Invariant(
                    id = id.trim(),
                    category = category,
                    statement = statement.trim(),
                    rationale = rationale.trim(),
                    alternative = alternative.trim(),
                    triggers = triggers
                )
            )
        )
    }

    /** Day 14: deletes an invariant by id, unless it's [Invariant.locked] — see
     * [InvariantRules.remove]. */
    fun onDeleteInvariant(id: String) {
        applyInvariantChange(InvariantRules.remove(invariants, id))
    }

    /** Day 14: applies one of [InvariantSet.PRESETS] on top of the current set — never removes
     * or disables an existing [Invariant.locked] entry (see [InvariantRules.applyPreset]). */
    fun onApplyInvariantPreset(preset: InvariantPreset) {
        applyInvariantChange(InvariantRules.applyPreset(invariants, preset))
    }

    /** Day 14: resets the whole set back to [InvariantSet.DEFAULTS], discarding any user-added
     * invariants and any enabled/disabled overrides. */
    fun onResetInvariants() {
        applyInvariantChange(InvariantRules.reset())
    }

    /** Applies an [InvariantChangeResult]: [InvariantChangeResult.Applied] updates and persists
     * [invariants] and rebuilds [agent]; [InvariantChangeResult.Rejected] surfaces its reason as
     * [ChatUiState.errorMessage] instead of silently doing nothing — mirrors
     * [applyTransition]'s Applied/Rejected handling for [TaskState]. */
    private fun applyInvariantChange(result: InvariantChangeResult) {
        when (result) {
            is InvariantChangeResult.Applied -> {
                invariants = result.set
                rebuildAgent()
                persistInvariants()
                _uiState.value = _uiState.value.copy(invariants = invariants, errorMessage = null)
            }
            is InvariantChangeResult.Rejected -> {
                _uiState.value = _uiState.value.copy(errorMessage = result.reason)
            }
        }
    }

    /**
     * Day 10 branching: snapshots the *active* branch's current state as a checkpoint. Doesn't
     * create a branch by itself — call [onCreateBranchFromCheckpoint] (once or more) afterwards
     * to fork one or more independent branches from this exact point. Day 11: the active
     * branch's working memory is captured alongside it, so a forked branch starts with the
     * same task-in-progress context, not an empty working layer. Day 13: same for its task
     * state — a forked branch resumes at the exact same stage/step, not a reset task.
     */
    fun onSaveCheckpoint() {
        checkpoint = currentBranchSnapshot()
        checkpointWorkingMemory = _uiState.value.workingMemory
        checkpointTaskState = _uiState.value.taskState
        workingMemoryStore?.save(CHECKPOINT_WORKING_MEMORY_KEY, checkpointWorkingMemory)
        taskStateStore?.save(CHECKPOINT_WORKING_MEMORY_KEY, checkpointTaskState)
        val currentHistory = taskTransitionLogStore?.load(currentBranchId) ?: emptyList()
        taskTransitionLogStore?.save(CHECKPOINT_WORKING_MEMORY_KEY, currentHistory)
        _uiState.value = _uiState.value.copy(hasCheckpoint = true)
        persistHistory()
    }

    /**
     * Creates a brand-new branch that starts as an exact copy of the saved [checkpoint] and
     * switches to it. Calling this twice from the same checkpoint (without saving a new one in
     * between) produces two independent siblings sharing the same history up to that point —
     * each then continues on its own from there. Day 11: both siblings also start from the
     * same [checkpointWorkingMemory], then diverge independently as each branch's own task
     * progresses. Day 13: likewise for [checkpointTaskState].
     */
    fun onCreateBranchFromCheckpoint() {
        val source = checkpoint ?: return
        branchCounter += 1
        val newBranch = source.copy(
            id = UUID.randomUUID().toString(),
            name = "Branch $branchCounter"
        )
        persistWorkingMemory(currentBranchId, _uiState.value.workingMemory)
        persistTaskState(currentBranchId, _uiState.value.taskState)
        otherBranches[currentBranchId] = currentBranchSnapshot()
        persistWorkingMemory(newBranch.id, checkpointWorkingMemory)
        persistTaskState(newBranch.id, checkpointTaskState)
        val checkpointHistory = taskTransitionLogStore?.load(CHECKPOINT_WORKING_MEMORY_KEY) ?: emptyList()
        taskTransitionLogStore?.save(newBranch.id, checkpointHistory)
        loadBranch(newBranch, workingMemoryOverride = checkpointWorkingMemory, taskStateOverride = checkpointTaskState)
        persistHistory()
    }

    /** Day 10 branching: switches the active branch, saving the current one's state first. */
    fun onBranchSelected(branchId: String) {
        if (branchId == currentBranchId) return
        val target = otherBranches[branchId] ?: return
        persistWorkingMemory(currentBranchId, _uiState.value.workingMemory)
        persistTaskState(currentBranchId, _uiState.value.taskState)
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
     * branch id (switching to an existing branch). Day 13's task state follows the same
     * pattern via [taskStateOverride]/[taskStateStore].
     */
    private fun loadBranch(
        snapshot: BranchSnapshot,
        workingMemoryOverride: MemorySnapshot? = null,
        taskStateOverride: TaskState? = null
    ) {
        currentBranchId = snapshot.id
        currentBranchName = snapshot.name
        val working = workingMemoryOverride
            ?: workingMemoryStore?.load(snapshot.id)
            ?: MemorySnapshot.EMPTY
        val task = taskStateOverride
            ?: taskStateStore?.load(snapshot.id)
            ?: TaskState.NONE
        val transitionHistory = taskTransitionLogStore?.load(snapshot.id) ?: emptyList()
        _uiState.value = _uiState.value.copy(
            messages = snapshot.messages,
            dialogTokenTotal = snapshot.dialogTokenTotal,
            memoryRoutingTokensTotal = snapshot.memoryRoutingTokensTotal,
            workingMemory = working,
            lastMemoryDecisions = emptyList(),
            currentBranchId = snapshot.id,
            branches = branchOptions(snapshot.id, snapshot.name),
            errorMessage = null,
            taskState = task,
            pendingTaskTransitionSuggestion = null,
            taskTransitionHistory = transitionHistory,
            allowedTaskEvents = TaskTransitionTable.allowedEvents(task)
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

    /** Persists [state] as [branchId]'s task state (Day 13), independently of both
     * [ChatHistoryStore] and the memory layers — see [TaskStateStore]. */
    private fun persistTaskState(branchId: String, state: TaskState) {
        taskStateStore?.save(branchId, state)
    }

    /** Persists a [record] into [branchId]'s transition journal (Day 15). */
    private fun persistTransitionLog(branchId: String, record: TaskTransitionRecord) {
        taskTransitionLogStore?.append(branchId, record)
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
     * Day 11 "End task": clears [MemoryLayer.WORKING] — [MemoryLayer.LONG_TERM] and the
     * dialog itself ([MemoryLayer.SHORT_TERM]) are left untouched, demonstrating that the three
     * layers really are independent. Day 13: also resets [TaskState] back to [TaskState.NONE]
     * for this branch — "the task" this whole state machine tracks really is over, not just
     * paused, so there is nothing left to resume.
     */
    fun onEndTask() {
        val fromStage = _uiState.value.taskState.stage
        val record = TaskTransitionRecord(
            timestamp = System.currentTimeMillis(),
            event = TaskEvent.RESET,
            fromStage = fromStage,
            toStage = TaskStage.PLANNING,
            applied = true,
            note = "End task"
        )
        val newHistory = _uiState.value.taskTransitionHistory + record
        _uiState.value = _uiState.value.copy(
            workingMemory = MemorySnapshot.EMPTY,
            lastMemoryDecisions = emptyList(),
            taskState = TaskState.NONE,
            pendingTaskTransitionSuggestion = null,
            taskTransitionHistory = newHistory,
            allowedTaskEvents = TaskTransitionTable.allowedEvents(TaskState.NONE)
        )
        persistMemoryLayers()
        persistTaskState(currentBranchId, TaskState.NONE)
        persistTransitionLog(currentBranchId, record)
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

    /**
     * Day 13: starts a brand new task on the active branch, in [TaskStage.PLANNING], replacing
     * whatever task was active before. [rawTitle] must not be blank.
     */
    fun onStartTask(rawTitle: String) {
        applyTransition(TaskStateMachine.start(rawTitle), TaskEvent.START)
    }

    /**
     * Day 13 [TaskStage.PLANNING] -> [TaskStage.EXECUTION]: fixes [rawSteps] (one step per
     * non-blank line) as the approved plan and moves to its first step.
     */
    fun onApprovePlan(rawSteps: String) {
        val steps = rawSteps.lines()
        applyTransition(TaskStateMachine.approvePlan(_uiState.value.taskState, steps), TaskEvent.APPROVE_PLAN)
    }

    /** Day 13: moves to the next step within [TaskStage.EXECUTION]. */
    fun onNextStep() {
        applyTransition(TaskStateMachine.nextStep(_uiState.value.taskState), TaskEvent.NEXT_STEP)
    }

    /** Day 13: moves back to the previous step within [TaskStage.EXECUTION]. */
    fun onPreviousStep() {
        applyTransition(TaskStateMachine.previousStep(_uiState.value.taskState), TaskEvent.PREVIOUS_STEP)
    }

    /** Day 13 [TaskStage.EXECUTION] -> [TaskStage.VALIDATION]: all steps are done, check the result. */
    fun onRequestValidation() {
        applyTransition(TaskStateMachine.requestValidation(_uiState.value.taskState), TaskEvent.REQUEST_VALIDATION)
    }

    /** Day 15: records the outcome of validation check (PASSED / FAILED). */
    fun onRecordValidation(outcome: ValidationOutcome, note: String = "") {
        applyTransition(
            TaskStateMachine.recordValidation(_uiState.value.taskState, outcome, note),
            TaskEvent.RECORD_VALIDATION
        )
    }

    /** Day 13 [TaskStage.VALIDATION] -> [TaskStage.EXECUTION]: send the task back for rework. */
    fun onSendBackToExecution(reason: String) {
        applyTransition(TaskStateMachine.sendBackToExecution(_uiState.value.taskState, reason), TaskEvent.SEND_BACK_TO_EXECUTION)
    }

    /** Day 13 & 15: marks the task [TaskStage.DONE] — from [TaskStage.VALIDATION] after passing validation. */
    fun onCompleteTask() {
        applyTransition(TaskStateMachine.complete(_uiState.value.taskState), TaskEvent.COMPLETE)
    }

    /** Day 15: cancels an active task from any non-terminal stage. */
    fun onCancelTask(reason: String = "") {
        applyTransition(TaskStateMachine.cancel(_uiState.value.taskState, reason), TaskEvent.CANCEL)
    }

    /** Day 13: freezes the task exactly where it is, so resuming continues without
     * re-explaining anything (see [TaskState.paused]). */
    fun onPauseTask() {
        applyTransition(TaskStateMachine.pause(_uiState.value.taskState), TaskEvent.PAUSE)
    }

    /** Day 13: lifts a pause, returning to exactly the stage/step that was paused. */
    fun onResumeTask() {
        applyTransition(TaskStateMachine.resume(_uiState.value.taskState), TaskEvent.RESUME)
    }

    /** Day 13: drops the task entirely, back to [TaskState.NONE]. */
    fun onResetTask() {
        applyTransition(TaskStateMachine.reset(), TaskEvent.RESET)
    }

    /**
     * Day 13: applies a pending [TaskStateAdvisor] suggestion — the *only* place a suggestion
     * ever changes [TaskState]; it is never applied automatically (see [prepareRequestContext]).
     * Routes through the exact same [TaskStateMachine] operations a button press would use, so
     * an invalid suggestion (stale by the time it's approved) is rejected the same way.
     */
    fun onApplyTaskTransitionSuggestion() {
        val suggestion = _uiState.value.pendingTaskTransitionSuggestion ?: return
        val state = _uiState.value.taskState
        val (result, event) = when (suggestion.action) {
            TaskTransitionAction.APPROVE_PLAN -> {
                // Day 15: one-tap applicable only when the advisor actually extracted a step
                // list from the assistant's last message; otherwise there is nothing to
                // approve yet, so the suggestion stays a hint (the banner already hides "Apply"
                // in that case — see [ChatScreen]'s TaskTransitionBanner).
                if (suggestion.proposedSteps.isEmpty()) return
                TaskStateMachine.approvePlan(state, suggestion.proposedSteps) to TaskEvent.APPROVE_PLAN
            }
            TaskTransitionAction.NEXT_STEP -> TaskStateMachine.nextStep(state) to TaskEvent.NEXT_STEP
            TaskTransitionAction.REQUEST_VALIDATION -> TaskStateMachine.requestValidation(state) to TaskEvent.REQUEST_VALIDATION
            TaskTransitionAction.SEND_BACK_TO_EXECUTION -> TaskStateMachine.sendBackToExecution(state, suggestion.reason) to TaskEvent.SEND_BACK_TO_EXECUTION
            TaskTransitionAction.COMPLETE -> TaskStateMachine.complete(state) to TaskEvent.COMPLETE
        }
        _uiState.value = _uiState.value.copy(pendingTaskTransitionSuggestion = null)
        applyTransition(result, event)
    }

    /** Day 13: discards the pending transition suggestion without changing the task. */
    fun onDismissTaskTransitionSuggestion() {
        _uiState.value = _uiState.value.copy(pendingTaskTransitionSuggestion = null)
    }

    /** Applies a [TransitionResult] from [TaskStateMachine]: an [TransitionResult.Applied]
     * result updates and persists [TaskState]; a [TransitionResult.Rejected] one surfaces its
     * reason as [ChatUiState.errorMessage] instead of silently doing nothing. */
    private fun applyTransition(result: TransitionResult, attemptedEvent: TaskEvent? = null) {
        val fromStage = _uiState.value.taskState.stage
        when (result) {
            is TransitionResult.Applied -> {
                val event = result.event ?: attemptedEvent ?: TaskEvent.RESET
                val record = TaskTransitionRecord(
                    timestamp = System.currentTimeMillis(),
                    event = event,
                    fromStage = fromStage,
                    toStage = result.state.stage,
                    applied = true,
                    note = when (result.state.stage) {
                        TaskStage.CANCELLED -> result.state.cancellationReason
                        TaskStage.VALIDATION -> if (result.state.validationOutcome != ValidationOutcome.NOT_RUN) result.state.validationOutcome.name else ""
                        else -> ""
                    }
                )
                val newHistory = _uiState.value.taskTransitionHistory + record
                _uiState.value = _uiState.value.copy(
                    taskState = result.state,
                    errorMessage = null,
                    taskTransitionHistory = newHistory,
                    allowedTaskEvents = TaskTransitionTable.allowedEvents(result.state)
                )
                persistTaskState(currentBranchId, result.state)
                persistTransitionLog(currentBranchId, record)
            }
            is TransitionResult.Rejected -> {
                val event = attemptedEvent ?: result.rejection?.event ?: TaskEvent.RESET
                val record = TaskTransitionRecord(
                    timestamp = System.currentTimeMillis(),
                    event = event,
                    fromStage = fromStage,
                    toStage = null,
                    applied = false,
                    note = result.reason
                )
                val newHistory = _uiState.value.taskTransitionHistory + record
                _uiState.value = _uiState.value.copy(
                    errorMessage = result.reason,
                    taskTransitionHistory = newHistory
                )
                persistTransitionLog(currentBranchId, record)
            }
        }
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
                    // Day 14: check invariants *before* spending three sequential LLM calls
                    // (memory routing, preference advisor, task advisor) on context that would
                    // just be thrown away by a refusal anyway.
                    // Day 15: also check task stage guard *before* spending three sequential LLM calls.
                    val isInvariantConflict = InvariantGuard.check(invariants, prompt).isNotEmpty()
                    val stageViolation = TaskStageGuard.check(_uiState.value.taskState, prompt)
                    val request = if (isInvariantConflict) {
                        AgentRequest(userMessage = prompt)
                    } else if (stageViolation != null) {
                        AgentRequest(
                            userMessage = prompt,
                            taskStateSnapshot = _uiState.value.taskState
                        )
                    } else {
                        val context = prepareRequestContext(history, model, prompt)
                        AgentRequest(
                            userMessage = prompt,
                            history = context.history,
                            modelOverride = model,
                            longTermMemory = context.longTermMemory,
                            workingMemory = context.workingMemory,
                            userProfile = context.userProfile,
                            taskState = context.taskState,
                            taskStageRules = context.taskStageRules,
                            invariants = context.invariants,
                            taskStateSnapshot = _uiState.value.taskState
                        )
                    }
                    agent.handle(request)
                        .onSuccess { response ->
                            val stageBlocked = response.blockedByStage
                            val newHistory = if (stageBlocked != null) {
                                val record = TaskTransitionRecord(
                                    timestamp = System.currentTimeMillis(),
                                    event = TaskEvent.APPROVE_PLAN,
                                    fromStage = stageBlocked.currentStage,
                                    toStage = null,
                                    applied = false,
                                    note = stageBlocked.reason
                                )
                                persistTransitionLog(currentBranchId, record)
                                _uiState.value.taskTransitionHistory + record
                            } else {
                                _uiState.value.taskTransitionHistory
                            }
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + ChatMessage(
                                    text = response.text,
                                    isFromUser = false,
                                    tokenUsage = response.tokenUsage,
                                    refusedByInvariantIds = response.refusedByInvariantIds,
                                    blockedByStageName = stageBlocked?.currentStage?.name
                                ),
                                taskTransitionHistory = newHistory,
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
     * layers, the Day 12 profile block, the Day 13 task state block/rules, and the Day 14
     * invariants block. */
    private data class RequestContext(
        val history: List<AgentMessage>,
        val longTermMemory: String? = null,
        val workingMemory: String? = null,
        val userProfile: String? = null,
        val taskState: String? = null,
        val taskStageRules: String? = null,
        val invariants: String? = null
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
     *
     * Day 13: renders the current [ChatUiState.taskState] (unconditionally, on every turn — see
     * [TaskStateRenderer]) and separately calls [TaskStateAdvisor] to check whether [prompt]
     * indicates the task's expected next action just happened; any suggestion is surfaced as
     * [ChatUiState.pendingTaskTransitionSuggestion], never applied here.
     */
    private suspend fun prepareRequestContext(
        history: List<AgentMessage>,
        model: String,
        prompt: String
    ): RequestContext = coroutineScope {
        val agentHistoryTail = history.takeLast(MemoryRouter.RECENT_CONTEXT_SIZE)
        val routerContext = history.takeLast(MemoryRouter.ROUTER_CONTEXT_SIZE)
        val state = _uiState.value
        val turn = history.count { it.role == AgentMessage.Role.USER } + 1

        // Perf: these three calls read disjoint slices of state (memory routing reads
        // workingMemory/longTermMemory; the preference advisor reads userProfile; the task
        // advisor reads taskState) and none of them depends on another's *result* — so they
        // are independent LLM calls and can run concurrently instead of paying their latency
        // three times in a row (~5s each, sequentially ~15s worst case).
        val routeDeferred = async {
            memoryRouter.route(
                previousWorking = state.workingMemory,
                previousLongTerm = state.longTermMemory,
                recentContext = routerContext,
                newUserMessage = prompt,
                turn = turn,
                model = model
            )
        }
        // Day 12: a separate LLM call, independent of memory routing, checks whether this turn
        // states a personalization preference. On failure or "no preference stated" it simply
        // leaves [ChatUiState.pendingPreferenceSuggestion] as-is — never blocks the chat turn.
        val preferenceDeferred = async {
            preferenceAdvisor.suggest(profile = state.userProfile, newUserMessage = prompt, model = model)
        }
        // Day 13: another separate LLM call checks whether this turn indicates the task's
        // expected next action just happened. Same fail-safe shape as the advisor above: on
        // failure or "no transition indicated" it simply leaves
        // [ChatUiState.pendingTaskTransitionSuggestion] as-is. Day 15: also passes the last
        // assistant turn so an approve_plan suggestion can extract the proposed step list for
        // a one-tap Apply (see [TaskTransitionSuggestion.proposedSteps]).
        val lastAssistantMessage = history.lastOrNull { it.role == AgentMessage.Role.AGENT }?.text
        val taskAdvisorDeferred = async {
            taskStateAdvisor.suggest(
                state = state.taskState,
                newUserMessage = prompt,
                model = model,
                lastAssistantMessage = lastAssistantMessage
            )
        }

        routeDeferred.await().onSuccess { outcome ->
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

        preferenceDeferred.await().onSuccess { outcome ->
            _uiState.value = _uiState.value.copy(
                personalizationTokensTotal = _uiState.value.personalizationTokensTotal + outcome.tokensUsed,
                pendingPreferenceSuggestion = outcome.suggestion ?: _uiState.value.pendingPreferenceSuggestion
            )
        }

        taskAdvisorDeferred.await().onSuccess { outcome ->
            _uiState.value = _uiState.value.copy(
                taskStateAdvisorTokensTotal = _uiState.value.taskStateAdvisorTokensTotal + outcome.tokensUsed,
                pendingTaskTransitionSuggestion = outcome.suggestion ?: _uiState.value.pendingTaskTransitionSuggestion
            )
        }

        val profileBlock = ProfileRenderer.render(latestMemoryState.userProfile)
        val taskStateBlock = TaskStateRenderer.render(latestMemoryState.taskState)
        val taskStageRulesText = TaskStateRenderer.stageRules(latestMemoryState.taskState)
        val invariantsBlock = InvariantRenderer.render(latestMemoryState.invariants)
        RequestContext(
            history = agentHistoryTail,
            longTermMemory = assembled.longTermBlock.ifEmpty { null },
            workingMemory = assembled.workingBlock.ifEmpty { null },
            userProfile = profileBlock.ifEmpty { null },
            taskState = taskStateBlock.ifEmpty { null },
            taskStageRules = taskStageRulesText.ifEmpty { null },
            invariants = invariantsBlock.ifEmpty { null }
        )
    }

    override fun onCleared() {
        super.onCleared()
        geminiClient.close()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { fitnessMcpGateway.close() }
    }
}
