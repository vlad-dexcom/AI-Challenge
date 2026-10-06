package com.example.geminichat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.geminichat.ChatController
import com.example.geminichat.ChatUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Base for feature ViewModels: a screen-sized projection of the shared [ChatController] state. */
abstract class FeatureViewModel(protected val controller: ChatController) : ViewModel() {
    protected fun <T> slice(transform: (ChatUiState) -> T): StateFlow<T> =
        controller.uiState.map(transform).distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, transform(controller.uiState.value))
}
