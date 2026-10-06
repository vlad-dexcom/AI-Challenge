package com.example.geminichat

import com.example.geminichat.agent.AgentConfig
import com.example.geminichat.agent.invariant.InvariantSet
import com.example.geminichat.agent.memory.MemoryRoutingDecision
import com.example.geminichat.agent.memory.MemorySnapshot
import com.example.geminichat.agent.profile.PreferenceSuggestion
import com.example.geminichat.agent.profile.UserProfile
import com.example.geminichat.agent.rag.RagAgentMode
import com.example.geminichat.agent.task.TaskEvent
import com.example.geminichat.agent.task.TaskState
import com.example.geminichat.agent.task.TaskTransitionRecord
import com.example.geminichat.agent.task.TaskTransitionSuggestion

/**
 * Per-screen projections of [ChatUiState]. Each feature screen observes only its own slice, so a
 * screen recomposes only when data it actually shows changes, and screens never see (or depend
 * on) state that belongs to another feature.
 */
data class ConversationState(
    val messages: List<ChatMessage>,
    val input: String,
    val isLoading: Boolean,
    val errorMessage: String?,
    val agentName: String,
    val dialogTokenTotal: Int,
    val branches: List<BranchOption>,
    val currentBranchId: String,
    val hasCheckpoint: Boolean,
    val pendingPreferenceSuggestion: PreferenceSuggestion?,
    val pendingTaskTransitionSuggestion: TaskTransitionSuggestion?,
    val workingMemoryCount: Int,
    val longTermMemoryCount: Int,
    val memoryRoutingTokensTotal: Int,
    val taskState: TaskState,
)

data class SettingsState(
    val isLoading: Boolean,
    val selectedAgentId: String,
    val agentDescription: String,
    val availableAgents: List<AgentConfig>,
    val selectedModel: String,
    val availableModels: List<String>,
    val ragMode: RagAgentMode,
)

data class ProfileState(
    val isLoading: Boolean,
    val profile: UserProfile,
    val personalizationTokensTotal: Int,
)

data class InvariantsState(
    val isLoading: Boolean,
    val invariants: InvariantSet,
    val errorMessage: String?,
)

data class MemoryState(
    val isLoading: Boolean,
    val longTermMemory: MemorySnapshot,
    val workingMemory: MemorySnapshot,
    val memoryRoutingTokensTotal: Int,
    val lastMemoryDecisions: List<MemoryRoutingDecision>,
)

data class TaskScreenState(
    val isLoading: Boolean,
    val taskState: TaskState,
    val allowedEvents: Set<TaskEvent>,
    val transitionHistory: List<TaskTransitionRecord>,
    val errorMessage: String?,
)

fun ChatUiState.toConversation() = ConversationState(
    messages, input, isLoading, errorMessage, agentName, dialogTokenTotal, branches, currentBranchId,
    hasCheckpoint, pendingPreferenceSuggestion, pendingTaskTransitionSuggestion,
    workingMemory.items.size, longTermMemory.items.size, memoryRoutingTokensTotal, taskState,
)

fun ChatUiState.toSettings() = SettingsState(
    isLoading, selectedAgentId, agentDescription, availableAgents, selectedModel, availableModels, ragMode,
)

fun ChatUiState.toProfile() = ProfileState(isLoading, userProfile, personalizationTokensTotal)

fun ChatUiState.toInvariants() = InvariantsState(isLoading, invariants, errorMessage)

fun ChatUiState.toMemory() = MemoryState(isLoading, longTermMemory, workingMemory, memoryRoutingTokensTotal, lastMemoryDecisions)

fun ChatUiState.toTaskScreen() = TaskScreenState(isLoading, taskState, allowedTaskEvents, taskTransitionHistory, errorMessage)
