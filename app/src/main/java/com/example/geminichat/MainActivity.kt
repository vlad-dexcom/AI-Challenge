package com.example.geminichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                // Persists chat history to disk (see ChatHistoryStore) so the conversation
                // resumes across app restarts instead of starting empty every launch.
                val historyStore = ChatHistoryStore(File(filesDir, ChatHistoryStore.FILE_NAME))
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(apiKey = BuildConfig.GEMINI_API_KEY, historyStore = historyStore) as T
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
