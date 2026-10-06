package com.example.geminichat.ui.profile

import com.example.geminichat.ChatController
import com.example.geminichat.agent.profile.ProfileField
import com.example.geminichat.agent.profile.UserProfile
import com.example.geminichat.toProfile
import com.example.geminichat.ui.FeatureViewModel

class ProfileViewModel(controller: ChatController) : FeatureViewModel(controller) {
    val state = slice { it.toProfile() }

    fun onFieldChange(field: ProfileField, value: String) = controller.onProfileFieldChange(field, value)
    fun onAddConstraint(value: String) = controller.onAddConstraint(value)
    fun onRemoveConstraint(value: String) = controller.onRemoveConstraint(value)
    fun onApplyPreset(preset: UserProfile) = controller.onApplyPreset(preset)
    fun onReset() = controller.onResetProfile()
}
