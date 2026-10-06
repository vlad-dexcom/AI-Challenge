package com.example.geminichat.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.example.geminichat.AppGraph
import com.example.geminichat.ChatController
import com.example.geminichat.mcp.McpScreen
import com.example.geminichat.ui.chat.ChatNavigation
import com.example.geminichat.ui.chat.ChatScreen
import com.example.geminichat.ui.chat.ChatViewModel
import com.example.geminichat.ui.invariants.InvariantsScreen
import com.example.geminichat.ui.invariants.InvariantsViewModel
import com.example.geminichat.ui.memory.MemoryScreen
import com.example.geminichat.ui.memory.MemoryViewModel
import com.example.geminichat.ui.profile.ProfileScreen
import com.example.geminichat.ui.profile.ProfileViewModel
import com.example.geminichat.ui.settings.SettingsScreen
import com.example.geminichat.ui.settings.SettingsViewModel
import com.example.geminichat.ui.task.TaskScreen
import com.example.geminichat.ui.task.TaskViewModel
import kotlinx.serialization.Serializable

@Serializable data object ChatKey : NavKey
@Serializable data object SettingsKey : NavKey
@Serializable data object ProfileKey : NavKey
@Serializable data object InvariantsKey : NavKey
@Serializable data object MemoryKey : NavKey
@Serializable data object TaskKey : NavKey
@Serializable data object McpKey : NavKey

/**
 * Navigation 3 graph: the back stack is a plain, saveable list of [NavKey]s; each entry gets its
 * own ViewModel store, and every feature ViewModel projects the shared [ChatController].
 */
@Composable
fun AppNavigation(graph: AppGraph) {
    val backStack = rememberNavBackStack(ChatKey)
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() }

    NavDisplay(
        backStack = backStack,
        onBack = back,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<ChatKey> {
                ChatScreen(
                    viewModel = featureViewModel(graph.chat, ::ChatViewModel),
                    navigation = ChatNavigation(
                        onOpenSettings = { backStack.add(SettingsKey) },
                        onOpenMcp = { backStack.add(McpKey) },
                        onOpenMemory = { backStack.add(MemoryKey) },
                        onOpenTask = { backStack.add(TaskKey) },
                    )
                )
            }
            entry<SettingsKey> {
                SettingsScreen(
                    viewModel = featureViewModel(graph.chat, ::SettingsViewModel),
                    onBack = back,
                    onOpenProfile = { backStack.add(ProfileKey) },
                    onOpenInvariants = { backStack.add(InvariantsKey) },
                )
            }
            entry<ProfileKey> { ProfileScreen(featureViewModel(graph.chat, ::ProfileViewModel), back) }
            entry<InvariantsKey> { InvariantsScreen(featureViewModel(graph.chat, ::InvariantsViewModel), back) }
            entry<MemoryKey> { MemoryScreen(featureViewModel(graph.chat, ::MemoryViewModel), back) }
            entry<TaskKey> { TaskScreen(featureViewModel(graph.chat, ::TaskViewModel), back) }
            entry<McpKey> { McpScreen(onBack = back) }
        }
    )
}

@Composable
private inline fun <reified VM : ViewModel> featureViewModel(
    controller: ChatController,
    crossinline create: (ChatController) -> VM,
): VM = viewModel(factory = viewModelFactory { initializer { create(controller) } })
