package com.example.geminichat.ui.task

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.geminichat.agent.task.TaskEvent
import com.example.geminichat.agent.task.TaskStage
import com.example.geminichat.agent.task.TaskState
import com.example.geminichat.agent.task.TaskTransitionRecord
import com.example.geminichat.agent.task.ValidationOutcome

/**
 * Day 13 task panel: shows where the task currently is (stage, current step, expected next
 * action, pause flag — see [TaskState]) and drives [TaskStateMachine] via
 * [com.example.geminichat.ChatController]'s per-transition handlers. Only one button set is shown at a time, matching
 * what [TaskStateMachine] actually allows from the current stage/pause combination, so a
 * disabled/invalid transition is never even offered rather than being offered and rejected.
 */
/**
 * Day 13 & 15 task panel: shows where the task currently is (stage, current step, expected next
 * action, validation outcome, pause flag — see [TaskState]) and drives [TaskStateMachine] via
 * [com.example.geminichat.ChatController]'s per-transition handlers. Buttons are rendered strictly according to
 * [allowedEvents] computed by [TaskTransitionTable], preventing illegal transitions.
 */
@Composable
internal fun TaskPanel(
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
    var expanded by remember { mutableStateOf(true) }
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
