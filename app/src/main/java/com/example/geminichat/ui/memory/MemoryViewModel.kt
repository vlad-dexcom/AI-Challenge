package com.example.geminichat.ui.memory

import com.example.geminichat.ChatController
import com.example.geminichat.toMemory
import com.example.geminichat.ui.FeatureViewModel

class MemoryViewModel(controller: ChatController) : FeatureViewModel(controller) {
    val state = slice { it.toMemory() }

    fun onPromote(key: String) = controller.onPromoteToLongTerm(key)
    fun onDeleteWorking(key: String) = controller.onDeleteWorkingItem(key)
    fun onDeleteLongTerm(key: String) = controller.onDeleteLongTermItem(key)
    fun onAddLongTerm(key: String, value: String) = controller.onAddLongTermItem(key, value)
    fun onEndTask() = controller.onEndTask()
    fun onClearDialog() = controller.onClearDialog()
}
