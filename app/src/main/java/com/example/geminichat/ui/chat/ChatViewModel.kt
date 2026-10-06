package com.example.geminichat.ui.chat

import com.example.geminichat.ChatController
import com.example.geminichat.toConversation
import com.example.geminichat.ui.FeatureViewModel

class ChatViewModel(controller: ChatController) : FeatureViewModel(controller) {
    val state = slice { it.toConversation() }

    fun onInputChange(value: String) = controller.onInputChange(value)
    fun sendMessage() = controller.sendMessage()

    fun onBranchSelected(id: String) = controller.onBranchSelected(id)
    fun onSaveCheckpoint() = controller.onSaveCheckpoint()
    fun onCreateBranchFromCheckpoint() = controller.onCreateBranchFromCheckpoint()

    fun onApplySuggestion() = controller.onApplySuggestion()
    fun onDismissSuggestion() = controller.onDismissSuggestion()
    fun onApplyTaskTransitionSuggestion() = controller.onApplyTaskTransitionSuggestion()
    fun onDismissTaskTransitionSuggestion() = controller.onDismissTaskTransitionSuggestion()
}
