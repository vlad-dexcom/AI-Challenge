package com.example.rag

import com.example.core.llm.GeminiEmbeddingClient
import com.example.core.llm.HashingEmbeddingClient
import com.example.core.llm.OllamaEmbeddingClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProviderTest {
    @Test fun optionsPickProviderAndOllamaSettings() {
        val p = LlmProvider.from(mapOf("provider" to "ollama", "ollama-model" to "m1", "ollama-embed-model" to "e1", "ollama-url" to "http://h:2"), "")
        assertTrue(p.isLocal && p.available)
        assertEquals("m1", p.defaultModel)
        assertEquals("ollama:e1", p.defaultEmbedderName)
        assertEquals("http://h:2", p.ollamaUrl)
    }

    @Test fun localDefaultsAreTheTunedOnesAndNoneTurnsThemOff() {
        val p = LlmProvider.from(mapOf("provider" to "ollama"), "")
        assertEquals(PromptProfile.LOCAL, p.promptProfile)
        assertEquals(com.example.core.llm.OllamaTuning.RECOMMENDED, p.tuning)
        assertEquals(8192, p.tuning.base.numCtx)
        val off = LlmProvider.from(mapOf("provider" to "ollama", "ollama-tuning" to "none", "prompt-profile" to "default"), "")
        assertEquals(PromptProfile.DEFAULT, off.promptProfile)
        assertEquals(com.example.core.llm.OllamaTuning.DEFAULT, off.tuning)
        // the cloud never gets the local prompts
        assertEquals(PromptProfile.DEFAULT, LlmProvider.from(emptyMap(), "k").promptProfile)
    }

    @Test fun tuningComesFromTheOptionAndBadValuesAreRejected() {
        val p = LlmProvider.from(mapOf("provider" to "ollama", "ollama-tuning" to "numCtx=4096,rewrite.maxTokens=64"), "")
        assertEquals(4096, p.tuning.base.numCtx)
        assertEquals(64, p.tuning.resolve(com.example.core.llm.CallPurpose.REWRITE).maxTokens)
        assertTrue(runCatching { LlmProvider.from(mapOf("ollama-tuning" to "bogus=1"), "") }.isFailure)
    }

    @Test fun geminiNeedsAKeyAndFallsBackToOfflineEmbedder() {
        val noKey = LlmProvider.from(emptyMap(), "")
        assertTrue(!noKey.isLocal && !noKey.available)
        assertEquals("offline", noKey.defaultEmbedderName)
        assertEquals("gemini", LlmProvider.from(emptyMap(), "k").defaultEmbedderName)
        assertTrue(runCatching { LlmProvider.from(mapOf("provider" to "openai"), "") }.isFailure)
    }

    @Test fun embedderIsRebuiltFromIndexMetadata() {
        val local = LlmProvider("ollama", "KEY")
        assertTrue(local.embedderFor("offline-hashing-bow-64", 64) is HashingEmbeddingClient)
        val e = local.embedderFor("ollama:embeddinggemma-2:270m-bf16-text+prompts", 768) as OllamaEmbeddingClient
        assertEquals("ollama:embeddinggemma-2:270m-bf16-text+prompts", e.modelName)
        assertEquals("ollama:bge-m3", (local.embedderFor("ollama:bge-m3", 1024) as OllamaEmbeddingClient).modelName)
        assertNull(local.embedderFor("gemini-embedding-001", 768))
        assertTrue(LlmProvider("gemini", "KEY").embedderFor("gemini-embedding-001", 768) is GeminiEmbeddingClient)
        assertNull(LlmProvider("gemini", "").embedderFor("gemini-embedding-001", 768))
    }
}
