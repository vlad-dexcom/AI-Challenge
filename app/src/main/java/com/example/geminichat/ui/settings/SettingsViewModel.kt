package com.example.geminichat.ui.settings

import com.example.geminichat.ChatController
import com.example.geminichat.agent.rag.RagAgentMode
import com.example.geminichat.toSettings
import com.example.geminichat.ui.FeatureViewModel

class SettingsViewModel(controller: ChatController) : FeatureViewModel(controller) {
    val state = slice { it.toSettings() }

    fun onAgentSelected(id: String) = controller.onAgentSelected(id)
    fun onModelSelected(model: String) = controller.onModelSelected(model)
    fun onRagModeSelected(mode: RagAgentMode) = controller.onRagModeSelected(mode)
}
