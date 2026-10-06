package com.example.geminichat.ui.invariants

import com.example.geminichat.ChatController
import com.example.geminichat.agent.invariant.InvariantCategory
import com.example.geminichat.agent.invariant.InvariantPreset
import com.example.geminichat.toInvariants
import com.example.geminichat.ui.FeatureViewModel

class InvariantsViewModel(controller: ChatController) : FeatureViewModel(controller) {
    val state = slice { it.toInvariants() }

    fun onToggle(id: String, enabled: Boolean) = controller.onToggleInvariant(id, enabled)
    fun onDelete(id: String) = controller.onDeleteInvariant(id)
    fun onAdd(id: String, category: InvariantCategory, statement: String, rationale: String, alternative: String, triggersCsv: String) =
        controller.onAddInvariant(id, category, statement, rationale, alternative, triggersCsv)
    fun onApplyPreset(preset: InvariantPreset) = controller.onApplyInvariantPreset(preset)
    fun onReset() = controller.onResetInvariants()
}
