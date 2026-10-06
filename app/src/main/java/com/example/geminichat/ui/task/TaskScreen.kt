package com.example.geminichat.ui.task

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.geminichat.ui.DetailScaffold
import com.example.geminichat.ui.ErrorText

@Composable
fun TaskScreen(viewModel: TaskViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    DetailScaffold(title = "Task", onBack = onBack) {
        TaskPanel(
            taskState = state.taskState,
            allowedEvents = state.allowedEvents,
            transitionHistory = state.transitionHistory,
            enabled = !state.isLoading,
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
        ErrorText(state.errorMessage)
    }
}
