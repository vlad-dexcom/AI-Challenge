package com.example.geminichat.ui.quick

import androidx.lifecycle.ViewModel
import com.example.core.llm.GeminiApiClient

class QuickAskViewModel(private val apiKey: String) : ViewModel() {
    // Talks to Gemini directly to keep it simple.
    private val client = GeminiApiClient(apiKey)

    suspend fun ask(question: String): String {
        val prompt = "You are a trainer. $question"
        return client.complete(prompt).getOrNull()!!
    }
}
