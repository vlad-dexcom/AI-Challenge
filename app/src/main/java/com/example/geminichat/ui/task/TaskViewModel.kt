package com.example.geminichat.ui.task

import com.example.geminichat.ChatController
import com.example.geminichat.agent.task.ValidationOutcome
import com.example.geminichat.toTaskScreen
import com.example.geminichat.ui.FeatureViewModel

class TaskViewModel(controller: ChatController) : FeatureViewModel(controller) {
    val state = slice { it.toTaskScreen() }

    fun onStartTask(title: String) = controller.onStartTask(title)
    fun onApprovePlan(steps: String) = controller.onApprovePlan(steps)
    fun onPreviousStep() = controller.onPreviousStep()
    fun onNextStep() = controller.onNextStep()
    fun onRequestValidation() = controller.onRequestValidation()
    fun onRecordValidation(outcome: ValidationOutcome, note: String) = controller.onRecordValidation(outcome, note)
    fun onSendBackToExecution(reason: String) = controller.onSendBackToExecution(reason)
    fun onCompleteTask() = controller.onCompleteTask()
    fun onCancelTask(reason: String) = controller.onCancelTask(reason)
    fun onPauseTask() = controller.onPauseTask()
    fun onResumeTask() = controller.onResumeTask()
    fun onResetTask() = controller.onResetTask()
}
