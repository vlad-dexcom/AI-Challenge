package com.example.geminichat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.example.geminichat.agent.invariant.Invariant
import com.example.geminichat.agent.invariant.InvariantCategory
import com.example.geminichat.agent.invariant.InvariantPreset
import com.example.geminichat.agent.invariant.InvariantSet
import com.example.geminichat.agent.memory.MemoryRoutingDecision
import com.example.geminichat.agent.memory.MemorySnapshot
import com.example.geminichat.agent.profile.ExpertiseLevel
import com.example.geminichat.agent.profile.PreferenceSuggestion
import com.example.geminichat.agent.profile.ProfileField
import com.example.geminichat.agent.profile.UserProfile
import com.example.geminichat.agent.task.TaskEvent
import com.example.geminichat.agent.task.TaskStage
import com.example.geminichat.agent.task.TaskState
import com.example.geminichat.agent.task.TaskTransitionAction
import com.example.geminichat.agent.task.TaskTransitionRecord
import com.example.geminichat.agent.task.TaskTransitionSuggestion
import com.example.geminichat.agent.task.ValidationOutcome
import dev.jeziellago.compose.markdowntext.MarkdownText

/**
 * The single screen of the app: a scrollable message list plus a text input row that sends
 * the user's message to the trainer agent and appends its reply. The app bar exposes the
 * branch switcher (bottom sheet) and the settings screen (model + profile) via icon buttons;
 * it no longer offers an agent picker since the app is trainer-only by default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }
    var showBranchSheet by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    if (showSettings) {
        SettingsScreen(uiState = uiState, viewModel = viewModel, onBack = { showSettings = false })
        return
    }

    if (showBranchSheet) {
        BranchBottomSheet(
            branches = uiState.branches,
            currentBranchId = uiState.currentBranchId,
            hasCheckpoint = uiState.hasCheckpoint,
            enabled = !uiState.isLoading,
            onBranchSelected = viewModel::onBranchSelected,
            onSaveCheckpoint = viewModel::onSaveCheckpoint,
            onCreateBranch = viewModel::onCreateBranchFromCheckpoint,
            onDismiss = { showBranchSheet = false }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(uiState.agentName) },
                    actions = {
                        IconButton(onClick = { showBranchSheet = true }) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_branch),
                                contentDescription = "Branch"
                            )
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Settings"
                            )
                        }
                    }
                )
                if (uiState.dialogTokenTotal > 0) {
                    Text(
                        text = "Tokens in dialog: ${uiState.dialogTokenTotal} (estimated)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                MemoryPanel(
                    longTermMemory = uiState.longTermMemory,
                    workingMemory = uiState.workingMemory,
                    memoryRoutingTokensTotal = uiState.memoryRoutingTokensTotal,
                    lastMemoryDecisions = uiState.lastMemoryDecisions,
                    enabled = !uiState.isLoading,
                    onPromote = viewModel::onPromoteToLongTerm,
                    onDeleteWorking = viewModel::onDeleteWorkingItem,
                    onDeleteLongTerm = viewModel::onDeleteLongTermItem,
                    onAddLongTerm = viewModel::onAddLongTermItem,
                    onEndTask = viewModel::onEndTask,
                    onClearDialog = viewModel::onClearDialog
                )
                TaskPanel(
                    taskState = uiState.taskState,
                    allowedEvents = uiState.allowedTaskEvents,
                    transitionHistory = uiState.taskTransitionHistory,
                    enabled = !uiState.isLoading,
                    onStartTask = viewModel::onStartTask,
                    onApprovePlan = viewModel::onApprovePlan,
                    onPreviousStep = viewModel::onPreviousStep,
                    onNextStep = viewModel::onNextStep,
                    onRequestValidation = viewModel::onRequestValidation,
                    onRecordValidation = viewModel::onRecordValidation,
                    onSendBackToExecution = viewModel::onSendBackToExecution,
                    onCompleteTask = viewModel::onCompleteTask,
                    onCancelTask = viewModel::onCancelTask,
                    onPauseTask = viewModel::onPauseTask,
                    onResumeTask = viewModel::onResumeTask,
                    onResetTask = viewModel::onResetTask
                )
                uiState.pendingPreferenceSuggestion?.let { suggestion ->
                    SuggestionBanner(
                        suggestion = suggestion,
                        onApply = viewModel::onApplySuggestion,
                        onDismiss = viewModel::onDismissSuggestion
                    )
                }
                uiState.pendingTaskTransitionSuggestion?.let { suggestion ->
                    TaskTransitionBanner(
                        suggestion = suggestion,
                        onApply = viewModel::onApplyTaskTransitionSuggestion,
                        onDismiss = viewModel::onDismissTaskTransitionSuggestion
                    )
                }
            }
        }
    ) { padding ->
        // imePadding() lets this column shrink above the keyboard instead of letting
        // messages scroll underneath it; combined with windowSoftInputMode="adjustResize"
        // (see AndroidManifest) the whole layout resizes cleanly when the keyboard opens.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.messages) { message ->
                    MessageBubble(message)
                }
                if (uiState.isLoading) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            uiState.errorMessage?.let { error ->
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = uiState.input,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask Gemini...") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.size(8.dp))
                IconButton(onClick = { viewModel.sendMessage() }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/**
 * Day 11 memory inspector. Lists every item in
 * [com.example.geminichat.agent.memory.MemoryLayer.WORKING] and
 * [com.example.geminichat.agent.memory.MemoryLayer.LONG_TERM] *separately* — so "what data
 * landed in which layer" is directly checkable, not just inferred from the model's answers —
 * plus manual overrides (promote/delete/add — see [ChatViewModel]) and "End task"/"Clear
 * dialog", which demonstrate the three layers are independent by only ever clearing one of
 * them at a time.
 */
@Composable
private fun MemoryPanel(
    longTermMemory: MemorySnapshot,
    workingMemory: MemorySnapshot,
    memoryRoutingTokensTotal: Int,
    lastMemoryDecisions: List<MemoryRoutingDecision>,
    enabled: Boolean,
    onPromote: (String) -> Unit,
    onDeleteWorking: (String) -> Unit,
    onDeleteLongTerm: (String) -> Unit,
    onAddLongTerm: (String, String) -> Unit,
    onEndTask: () -> Unit,
    onClearDialog: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Memory: ${workingMemory.items.size} working · " +
                    "${longTermMemory.items.size} long-term · routing cost " +
                    "$memoryRoutingTokensTotal tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (!expanded) return@Column

        Text(
            text = "Long-term (profile, decisions, knowledge)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (longTermMemory.items.isEmpty()) {
            Text(
                "(empty)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        longTermMemory.items.values.sortedBy { it.key }.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${item.key}: ${item.value}" + if (item.pinned) " (pinned)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onDeleteLongTerm(item.key) }, enabled = enabled) {
                    Text("Delete")
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                placeholder = { Text("key") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.size(4.dp))
            OutlinedTextField(
                value = newValue,
                onValueChange = { newValue = it },
                placeholder = { Text("value") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    onAddLongTerm(newKey, newValue)
                    newKey = ""
                    newValue = ""
                },
                enabled = enabled && newKey.isNotBlank() && newValue.isNotBlank()
            ) {
                Text("Add")
            }
        }

        Text(
            text = "Working (current task)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (workingMemory.items.isEmpty()) {
            Text(
                "(empty)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        workingMemory.items.values.sortedBy { it.key }.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${item.key}: ${item.value}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onPromote(item.key) }, enabled = enabled) {
                    Text("Promote")
                }
                TextButton(onClick = { onDeleteWorking(item.key) }, enabled = enabled) {
                    Text("Delete")
                }
            }
        }

        if (lastMemoryDecisions.isNotEmpty()) {
            Text(
                text = "Last turn routed: " + lastMemoryDecisions.joinToString("; ") { decision ->
                    "${decision.layer} ${decision.key} (${decision.reason})"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Row(modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = onEndTask, enabled = enabled) {
                Text("End task")
            }
            TextButton(onClick = onClearDialog, enabled = enabled) {
                Text("Clear dialog")
            }
        }
    }
}

/**
 * Day 13 task panel: shows where the task currently is (stage, current step, expected next
 * action, pause flag — see [TaskState]) and drives [TaskStateMachine] via
 * [ChatViewModel]'s per-transition handlers. Only one button set is shown at a time, matching
 * what [TaskStateMachine] actually allows from the current stage/pause combination, so a
 * disabled/invalid transition is never even offered rather than being offered and rejected.
 */
/**
 * Day 13 & 15 task panel: shows where the task currently is (stage, current step, expected next
 * action, validation outcome, pause flag — see [TaskState]) and drives [TaskStateMachine] via
 * [ChatViewModel]'s per-transition handlers. Buttons are rendered strictly according to
 * [allowedEvents] computed by [TaskTransitionTable], preventing illegal transitions.
 */
@Composable
private fun TaskPanel(
    taskState: TaskState,
    allowedEvents: Set<TaskEvent>,
    transitionHistory: List<TaskTransitionRecord>,
    enabled: Boolean,
    onStartTask: (String) -> Unit,
    onApprovePlan: (String) -> Unit,
    onPreviousStep: () -> Unit,
    onNextStep: () -> Unit,
    onRequestValidation: () -> Unit,
    onRecordValidation: (ValidationOutcome, String) -> Unit,
    onSendBackToExecution: (String) -> Unit,
    onCompleteTask: () -> Unit,
    onCancelTask: (String) -> Unit,
    onPauseTask: () -> Unit,
    onResumeTask: () -> Unit,
    onResetTask: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var stepsText by remember { mutableStateOf("") }
    var sendBackReason by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val summary = if (!taskState.isActive) {
                "Задача: не начата"
            } else {
                "Задача: ${taskState.title} · ${taskState.stage}" +
                    (taskState.progressLabel?.let { " ($it)" } ?: "") +
                    (if (taskState.validationOutcome != ValidationOutcome.NOT_RUN) " · Валидация: ${taskState.validationOutcome}" else "") +
                    if (taskState.paused) " · НА ПАУЗЕ" else ""
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Скрыть" else "Показать")
            }
        }
        if (!expanded) return@Column

        if (!taskState.isActive) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    placeholder = { Text("Название задачи") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { onStartTask(newTitle); newTitle = "" },
                    enabled = enabled && newTitle.isNotBlank()
                ) {
                    Text("Начать задачу")
                }
            }
            return@Column
        }

        // Display current steps
        if (taskState.steps.isNotEmpty()) {
            taskState.steps.forEachIndexed { index, step ->
                Text(
                    text = (if (index == taskState.currentStepIndex) "→ " else "   ") + "${index + 1}. $step",
                    style = if (index == taskState.currentStepIndex) {
                        MaterialTheme.typography.bodyMedium
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = if (index == taskState.currentStepIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        if (taskState.expectedAction.isNotBlank()) {
            Text(
                text = "Ожидается: ${taskState.expectedActor} — ${taskState.expectedAction}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (taskState.validationOutcome != ValidationOutcome.NOT_RUN) {
            Text(
                text = "Результат валидации: ${taskState.validationOutcome}" +
                    if (taskState.validationNote.isNotBlank()) " (${taskState.validationNote})" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (taskState.validationOutcome == ValidationOutcome.PASSED) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        if (taskState.stage == TaskStage.CANCELLED) {
            Text(
                text = "Задача отменена" + if (taskState.cancellationReason.isNotBlank()) ": ${taskState.cancellationReason}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Actions: Approve plan
        if (TaskEvent.APPROVE_PLAN in allowedEvents) {
            OutlinedTextField(
                value = stepsText,
                onValueChange = { stepsText = it },
                placeholder = { Text("По одному шагу на строку") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            Row(modifier = Modifier.padding(top = 4.dp)) {
                TextButton(
                    onClick = { onApprovePlan(stepsText); stepsText = "" },
                    enabled = enabled && stepsText.isNotBlank()
                ) {
                    Text("Утвердить план")
                }
            }
        }

        // Actions: Record validation outcome
        if (TaskEvent.RECORD_VALIDATION in allowedEvents) {
            Text(
                text = "Фиксация результата валидации:",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(modifier = Modifier.padding(top = 2.dp)) {
                TextButton(
                    onClick = { onRecordValidation(ValidationOutcome.PASSED, "Проверка пройдена") },
                    enabled = enabled
                ) {
                    Text("✅ Валидация пройдена (PASSED)")
                }
                TextButton(
                    onClick = { onRecordValidation(ValidationOutcome.FAILED, "Требуется доработка") },
                    enabled = enabled
                ) {
                    Text("❌ Не пройдена (FAILED)")
                }
            }
        }

        // Actions: Send back to execution
        if (TaskEvent.SEND_BACK_TO_EXECUTION in allowedEvents) {
            OutlinedTextField(
                value = sendBackReason,
                onValueChange = { sendBackReason = it },
                placeholder = { Text("Причина доработки") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            Row(modifier = Modifier.padding(top = 4.dp)) {
                TextButton(
                    onClick = { onSendBackToExecution(sendBackReason); sendBackReason = "" },
                    enabled = enabled && sendBackReason.isNotBlank()
                ) {
                    Text("Вернуть на доработку")
                }
            }
        }

        // Actions: Stepping & validation request & complete
        Row(modifier = Modifier.padding(top = 4.dp)) {
            if (TaskEvent.PREVIOUS_STEP in allowedEvents) {
                TextButton(onClick = onPreviousStep, enabled = enabled) { Text("Назад") }
            }
            if (TaskEvent.NEXT_STEP in allowedEvents) {
                TextButton(onClick = onNextStep, enabled = enabled) { Text("Вперед") }
            }
            if (TaskEvent.REQUEST_VALIDATION in allowedEvents) {
                TextButton(onClick = onRequestValidation, enabled = enabled) { Text("На валидацию") }
            }
            if (TaskEvent.COMPLETE in allowedEvents) {
                TextButton(onClick = onCompleteTask, enabled = enabled) { Text("Завершить задачу") }
            }
        }

        // Global status controls: Pause, Resume, Cancel, Reset
        Row(modifier = Modifier.padding(top = 4.dp)) {
            if (TaskEvent.PAUSE in allowedEvents) {
                TextButton(onClick = onPauseTask, enabled = enabled) { Text("Приостановить") }
            }
            if (TaskEvent.RESUME in allowedEvents) {
                TextButton(onClick = onResumeTask, enabled = enabled) { Text("Возобновить") }
            }
            if (TaskEvent.CANCEL in allowedEvents) {
                TextButton(onClick = { onCancelTask("Отменено пользователем") }, enabled = enabled) {
                    Text("Отменить задачу")
                }
            }
            if (TaskEvent.RESET in allowedEvents) {
                TextButton(onClick = onResetTask, enabled = enabled) { Text("Сбросить") }
            }
        }

        // Explanatory line for what is currently forbidden
        val forbiddenRule = when {
            taskState.paused -> "⛔ Запрещено: выполнение и валидация до возобновления задачи."
            taskState.stage == TaskStage.PLANNING -> "⛔ Запрещено: выполнение шагов и завершение до утверждения плана."
            taskState.stage == TaskStage.EXECUTION -> "⛔ Запрещено: завершение без прохождения этапа валидации."
            taskState.stage == TaskStage.VALIDATION && taskState.validationOutcome != ValidationOutcome.PASSED ->
                "⛔ Запрещено: завершение без зафиксированного успешного результата валидации (PASSED)."
            taskState.isTerminal -> "⛔ Задача завершена: дальнейшие переходы невозможны (используйте Сброс)."
            else -> null
        }
        if (forbiddenRule != null) {
            Text(
                text = forbiddenRule,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // Transition history journal
        if (transitionHistory.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Журнал переходов (${transitionHistory.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showHistory = !showHistory }) {
                    Text(if (showHistory) "Скрыть" else "Показать")
                }
            }
            if (showHistory) {
                Column(modifier = Modifier.padding(top = 2.dp)) {
                    transitionHistory.takeLast(10).reversed().forEach { record ->
                        val status = if (record.applied) "✅" else "❌"
                        val transition = if (record.toStage != null) {
                            "${record.fromStage} → ${record.toStage}"
                        } else {
                            "${record.fromStage} (отклонен)"
                        }
                        val note = if (record.note.isNotBlank()) " · ${record.note}" else ""
                        Text(
                            text = "$status ${record.event.name}: $transition$note",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (record.applied) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Day 13/15: surfaces a pending [TaskTransitionSuggestion] for the user to approve or dismiss —
 * mirrors [SuggestionBanner]'s "never applied automatically" shape. An
 * [TaskTransitionAction.APPROVE_PLAN] suggestion is one-tap applicable only when the advisor
 * managed to extract the plan's step list from the assistant's last message (see
 * [TaskTransitionSuggestion.proposedSteps]); if it couldn't, "Apply" is hidden and the banner
 * is shown only as a hint that the user should approve the plan via [TaskPanel]'s own field. */
@Composable
private fun TaskTransitionBanner(
    suggestion: TaskTransitionSuggestion,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val isApprovePlan = suggestion.action == TaskTransitionAction.APPROVE_PLAN
    val canApply = !isApprovePlan || suggestion.proposedSteps.isNotEmpty()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Assistant suggests advancing the task: ${suggestion.action.name.lowercase()}",
                style = MaterialTheme.typography.bodySmall
            )
            if (suggestion.reason.isNotBlank()) {
                Text(
                    text = suggestion.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (isApprovePlan && suggestion.proposedSteps.isNotEmpty()) {
                Text(
                    text = "Шаги: " + suggestion.proposedSteps.joinToString(" → "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(modifier = Modifier.padding(top = 4.dp)) {
                if (canApply) {
                    TextButton(onClick = onApply) { Text("Apply") }
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/**
 * Day 12 personalization panel: edits the single, global [UserProfile] in place — there is no
 * per-profile selector, since there is only ever one profile (see [UserProfile]). The preset
 * row at the top is a shortcut that overwrites every field at once with one of
 * [UserProfile.PRESETS], so maximally different profiles can be tried back to back; regular
 * text fields still commit on every keystroke via [onFieldChange] (mirrors [MemoryPanel]'s
 * immediate-apply style), and constraints are a separate add/remove list since they're a set,
 * not a single overwritable value.
 */
@Composable
private fun ProfilePanel(
    profile: UserProfile,
    personalizationTokensTotal: Int,
    enabled: Boolean,
    onFieldChange: (ProfileField, String) -> Unit,
    onAddConstraint: (String) -> Unit,
    onRemoveConstraint: (String) -> Unit,
    onApplyPreset: (UserProfile) -> Unit,
    onReset: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var newConstraint by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (profile.isEmpty()) {
                    "Profile: not set up · personalization cost $personalizationTokensTotal tokens"
                } else {
                    "Profile: ${profile.displayName.ifBlank { "(unnamed)" }} · " +
                        "personalization cost $personalizationTokensTotal tokens"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (!expanded) return@Column

        Text(
            text = "Presets (test with very different profiles)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            UserProfile.PRESETS.forEach { preset ->
                TextButton(onClick = { onApplyPreset(preset.profile) }, enabled = enabled) {
                    Text(preset.label)
                }
            }
        }

        OutlinedTextField(
            value = profile.displayName,
            onValueChange = { onFieldChange(ProfileField.DISPLAY_NAME, it) },
            label = { Text("Name") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.about,
            onValueChange = { onFieldChange(ProfileField.ABOUT, it) },
            label = { Text("About") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.language,
            onValueChange = { onFieldChange(ProfileField.LANGUAGE, it) },
            label = { Text("Language") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        ExpertiseSelector(
            selected = profile.expertise,
            enabled = enabled,
            onSelected = { level -> onFieldChange(ProfileField.EXPERTISE, level.name) }
        )
        OutlinedTextField(
            value = profile.tone,
            onValueChange = { onFieldChange(ProfileField.TONE, it) },
            label = { Text("Tone") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.format,
            onValueChange = { onFieldChange(ProfileField.FORMAT, it) },
            label = { Text("Preferred format") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.maxAnswerSentences?.toString() ?: "",
            onValueChange = { onFieldChange(ProfileField.MAX_ANSWER_SENTENCES, it) },
            label = { Text("Max answer sentences") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = profile.notes,
            onValueChange = { onFieldChange(ProfileField.NOTES, it) },
            label = { Text("Notes") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )

        Text(
            text = "Constraints",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (profile.constraints.isEmpty()) {
            Text(
                "(none)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        profile.constraints.forEach { constraint ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = constraint,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onRemoveConstraint(constraint) }, enabled = enabled) {
                    Text("Delete")
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            OutlinedTextField(
                value = newConstraint,
                onValueChange = { newConstraint = it },
                placeholder = { Text("e.g. no jumping") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    onAddConstraint(newConstraint)
                    newConstraint = ""
                },
                enabled = enabled && newConstraint.isNotBlank()
            ) {
                Text("Add")
            }
        }

        Row(modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = onReset, enabled = enabled) {
                Text("Reset profile")
            }
        }
    }
}

@Composable
private fun ExpertiseSelector(
    selected: ExpertiseLevel?,
    enabled: Boolean,
    onSelected: (ExpertiseLevel) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Text(
            text = "Expertise",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = { if (enabled) expanded = true }, enabled = enabled) {
                Text(selected?.name?.lowercase() ?: "not set")
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select expertise")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ExpertiseLevel.entries.forEach { level ->
                    DropdownMenuItem(
                        text = { Text(level.name.lowercase()) },
                        onClick = {
                            onSelected(level)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Day 14: shows the current [com.example.geminichat.agent.invariant.InvariantSet] — hard rules
 * the agent must never break, grouped by category. A [Invariant.locked] entry shows a 🔒 badge
 * instead of a switch (it can never be turned off) and has no delete action; every other
 * invariant can be toggled or deleted. Mirrors [ProfilePanel]'s collapsible-panel/preset-row
 * shape.
 */
@Composable
private fun InvariantPanel(
    invariants: InvariantSet,
    enabled: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: (String, InvariantCategory, String, String, String, String) -> Unit,
    onApplyPreset: (InvariantPreset) -> Unit,
    onReset: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf(InvariantCategory.SAFETY) }
    var newStatement by remember { mutableStateOf("") }
    var newRationale by remember { mutableStateOf("") }
    var newAlternative by remember { mutableStateOf("") }
    var newTriggers by remember { mutableStateOf("") }

    val enabledCount = invariants.enabledInvariants.size
    val totalCount = invariants.invariants.size

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Invariants: $enabledCount of $totalCount enabled",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (!expanded) return@Column

        Text(
            text = "Presets",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            InvariantSet.PRESETS.forEach { preset ->
                TextButton(onClick = { onApplyPreset(preset) }, enabled = enabled) {
                    Text(preset.label)
                }
            }
        }

        InvariantCategory.entries.forEach { category ->
            val invariantsInCategory = invariants.invariants.filter { it.category == category }
            if (invariantsInCategory.isEmpty()) return@forEach
            Text(
                text = category.name,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            invariantsInCategory.forEach { invariant ->
                InvariantRow(invariant = invariant, enabled = enabled, onToggle = onToggle, onDelete = onDelete)
            }
        }

        Text(
            text = "Add invariant",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        InvariantCategorySelector(
            selected = newCategory,
            enabled = enabled,
            onSelected = { newCategory = it }
        )
        OutlinedTextField(
            value = newStatement,
            onValueChange = { newStatement = it },
            label = { Text("Rule") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = newRationale,
            onValueChange = { newRationale = it },
            label = { Text("Why") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = newAlternative,
            onValueChange = { newAlternative = it },
            label = { Text("Alternative to offer instead") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        OutlinedTextField(
            value = newTriggers,
            onValueChange = { newTriggers = it },
            label = { Text("Trigger patterns (comma-separated)") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        Row(modifier = Modifier.padding(top = 4.dp)) {
            TextButton(
                onClick = {
                    val id = newStatement.trim().lowercase()
                        .replace(Regex("[^a-z0-9]+"), "-").trim('-')
                    onAdd(id, newCategory, newStatement, newRationale, newAlternative, newTriggers)
                    if (id.isNotBlank()) {
                        newStatement = ""
                        newRationale = ""
                        newAlternative = ""
                        newTriggers = ""
                    }
                },
                enabled = enabled && newStatement.isNotBlank() && newRationale.isNotBlank()
            ) {
                Text("Add")
            }
            TextButton(onClick = onReset, enabled = enabled) {
                Text("Reset to defaults")
            }
        }
    }
}

@Composable
private fun InvariantRow(
    invariant: Invariant,
    enabled: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = invariant.statement,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            if (invariant.locked) {
                Text(
                    text = "\uD83D\uDD12",
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            } else {
                Switch(
                    checked = invariant.enabled,
                    onCheckedChange = { onToggle(invariant.id, it) },
                    enabled = enabled
                )
                TextButton(onClick = { onDelete(invariant.id) }, enabled = enabled) {
                    Text("Delete")
                }
            }
        }
        Text(
            text = invariant.rationale,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InvariantCategorySelector(
    selected: InvariantCategory,
    enabled: Boolean,
    onSelected: (InvariantCategory) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Text(
            text = "Category",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = { if (enabled) expanded = true }, enabled = enabled) {
                Text(selected.name.lowercase())
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select category")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                InvariantCategory.entries.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.name.lowercase()) },
                        onClick = {
                            onSelected(category)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Day 12's hybrid update path made visible: a [com.example.geminichat.agent.profile.PreferenceAdvisor]
 * suggestion is never applied to [UserProfile] automatically — it's shown here with the
 * inferred reason, and only [onApply] (not the advisor call itself) ever changes the profile.
 */
@Composable
private fun SuggestionBanner(
    suggestion: PreferenceSuggestion,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Assistant suggests updating your profile: ${suggestion.field.name.lowercase()} → " +
                    "\"${suggestion.value}\"",
                style = MaterialTheme.typography.bodySmall
            )
            if (suggestion.reason.isNotBlank()) {
                Text(
                    text = suggestion.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onApply) { Text("Apply") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/**
 * Day 10 branching controls, now surfaced as a bottom sheet from the app bar's branch button
 * instead of an inline dropdown. Each branch is a toggle-style switch: only the active branch's
 * switch is on, and flipping another branch's switch on selects it (mirrors single-select radio
 * semantics while satisfying the "toggle" look). "Save checkpoint" / "Branch from checkpoint"
 * still work the same as before — see [ChatViewModel.onSaveCheckpoint] /
 * [ChatViewModel.onCreateBranchFromCheckpoint].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchBottomSheet(
    branches: List<BranchOption>,
    currentBranchId: String,
    hasCheckpoint: Boolean,
    enabled: Boolean,
    onBranchSelected: (String) -> Unit,
    onSaveCheckpoint: () -> Unit,
    onCreateBranch: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text = "Branches", style = MaterialTheme.typography.titleMedium)
            branches.forEach { branch ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = branch.name, modifier = Modifier.weight(1f))
                    Switch(
                        checked = branch.id == currentBranchId,
                        onCheckedChange = { isOn -> if (isOn) onBranchSelected(branch.id) },
                        enabled = enabled
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = onSaveCheckpoint, enabled = enabled) {
                    Text("Save checkpoint")
                }
                TextButton(onClick = onCreateBranch, enabled = enabled && hasCheckpoint) {
                    Text("New branch")
                }
            }
        }
    }
}

/**
 * Day-12-cleanup settings screen: model choice and the personalization profile used to live
 * inline on the chat screen's top bar; both now live here, reached via the app bar's settings
 * button, keeping the chat screen focused on the conversation itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(uiState: ChatUiState, viewModel: ChatViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Model",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                ModelSelector(
                    selectedModel = uiState.selectedModel,
                    availableModels = uiState.availableModels,
                    enabled = !uiState.isLoading,
                    onModelSelected = viewModel::onModelSelected
                )
            }
            ProfilePanel(
                profile = uiState.userProfile,
                personalizationTokensTotal = uiState.personalizationTokensTotal,
                enabled = !uiState.isLoading,
                onFieldChange = viewModel::onProfileFieldChange,
                onAddConstraint = viewModel::onAddConstraint,
                onRemoveConstraint = viewModel::onRemoveConstraint,
                onApplyPreset = viewModel::onApplyPreset,
                onReset = viewModel::onResetProfile
            )
            InvariantPanel(
                invariants = uiState.invariants,
                enabled = !uiState.isLoading,
                onToggle = viewModel::onToggleInvariant,
                onDelete = viewModel::onDeleteInvariant,
                onAdd = viewModel::onAddInvariant,
                onApplyPreset = viewModel::onApplyInvariantPreset,
                onReset = viewModel::onResetInvariants
            )
        }
    }
}

@Composable
private fun ModelSelector(
    selectedModel: String,
    availableModels: List<String>,
    enabled: Boolean,
    onModelSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { if (enabled) expanded = true }, enabled = enabled) {
            Text(selectedModel)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select model")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            availableModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        onModelSelected(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isFromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.isFromUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else if (message.refusedByInvariantIds.isNotEmpty() || message.blockedByStageName != null) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            )
        ) {
            if (message.isFromUser) {
                // The user's own input is shown as-is; only the model's replies are
                // rendered as Markdown (bold, lists, code blocks, links, etc.).
                Text(
                    text = message.text,
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (message.refusedByInvariantIds.isNotEmpty()) {
                        // Day 14: this reply is a deterministic refusal — the model was never
                        // called (see LlmAgent.handle) — so it's badged instead of looking like
                        // an ordinary answer.
                        Text(
                            text = "⛔ Инвариант: ${message.refusedByInvariantIds.joinToString(", ")}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    if (message.blockedByStageName != null) {
                        // Day 15: deterministic refusal when user requests an action out of stage lifecycle
                        Text(
                            text = "⛔ Этап задачи: ${message.blockedByStageName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    MarkdownText(
                        markdown = message.text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        isTextSelectable = true
                    )
                    message.tokenUsage?.let { usage ->
                        Text(
                            text = "prompt ${usage.promptTokens} (history ${usage.historyTokens}, " +
                                "lt ${usage.longTermMemoryTokens}, wm ${usage.workingMemoryTokens}, " +
                                "profile ${usage.profileTokens}, inv ${usage.invariantTokens}) · " +
                                "reply ${usage.completionTokens} · total ${usage.totalTokens}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
