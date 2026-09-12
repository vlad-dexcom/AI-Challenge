package com.example.geminichat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [GeminiApiClient.contextWindowTokens] doesn't touch the network, so it can be tested
 * directly. In particular this locks in the `debugContextWindowOverrideTokens` escape hatch
 * used to manually trigger [com.example.geminichat.agent.ContextWindowExceededException] in the
 * running app (see its kdoc on [GeminiApiClient] and [ChatViewModel]).
 */
class GeminiApiClientTest {

    @Test
    fun `reports the real per-model context window by default`() {
        val client = GeminiApiClient(apiKey = "test-key")

        assertEquals(1_000_000, client.contextWindowTokens("gemini-3.5-flash"))
        assertEquals(2_000_000, client.contextWindowTokens("gemini-3-pro"))
    }

    @Test
    fun `falls back to the default context window for an unknown model`() {
        val client = GeminiApiClient(apiKey = "test-key")

        assertEquals(
            com.example.geminichat.agent.LlmClient.DEFAULT_CONTEXT_WINDOW_TOKENS,
            client.contextWindowTokens("some-future-model")
        )
    }

    @Test
    fun `debug override forces the same tiny window for every model`() {
        val client = GeminiApiClient(apiKey = "test-key", debugContextWindowOverrideTokens = 200)

        assertEquals(200, client.contextWindowTokens("gemini-3.5-flash"))
        assertEquals(200, client.contextWindowTokens("gemini-3-pro"))
        assertEquals(200, client.contextWindowTokens("some-future-model"))
    }
}
