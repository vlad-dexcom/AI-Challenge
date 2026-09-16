package com.example.geminichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.geminichat.agent.memory.LongTermMemoryStore
import com.example.geminichat.agent.memory.WorkingMemoryStore
import com.example.geminichat.agent.profile.UserProfileStore
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                // Persists chat history to disk (see ChatHistoryStore) so the conversation
                // resumes across app restarts instead of starting empty every launch. Day 11's
                // working/long-term memory layers are persisted in their own separate files
                // (see LongTermMemoryStore/WorkingMemoryStore) rather than mixed into this one.
                // Day 12's single global user profile is likewise its own file (UserProfileStore).
                val historyStore = ChatHistoryStore(File(filesDir, ChatHistoryStore.FILE_NAME))
                val longTermMemoryStore =
                    LongTermMemoryStore(File(filesDir, LongTermMemoryStore.FILE_NAME))
                val workingMemoryStore =
                    WorkingMemoryStore(File(filesDir, WorkingMemoryStore.FILE_NAME))
                val userProfileStore =
                    UserProfileStore(File(filesDir, UserProfileStore.FILE_NAME))
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(
                    apiKey = BuildConfig.GEMINI_API_KEY,
                    historyStore = historyStore,
                    longTermMemoryStore = longTermMemoryStore,
                    workingMemoryStore = workingMemoryStore,
                    userProfileStore = userProfileStore
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChatScreen(viewModel = viewModel)
        }
    }
}
