package com.example.geminichat

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the "Day 7" requirement directly: history saved by one [ChatHistoryStore] instance
 * (simulating the app before a restart) is exactly what a fresh instance reading the same file
 * (simulating the app after a restart) loads back — no messages lost, agent/model resumed.
 */
class ChatHistoryStoreTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        file = File.createTempFile("chat_history_test", ".json")
        file.delete()
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun `load returns empty snapshot when no file exists yet`() {
        val store = ChatHistoryStore(file)

        val snapshot = store.load()

        assertTrue(snapshot.messages.isEmpty())
    }

    @Test
    fun `save then load with a new store instance restores the full conversation`() {
        val original = ChatHistorySnapshot(
            messages = listOf(
                ChatMessage("How do I warm up before squats?", isFromUser = true),
                ChatMessage("Start with 5 minutes of light cardio, then bodyweight squats.", isFromUser = false)
            ),
            selectedAgentId = "personal-trainer",
            selectedModel = "gemini-1.5-flash",
            contextStrategy = "SLIDING_WINDOW",
            summary = "User asked about squat warm-ups; agent suggested light cardio first.",
            summarizedMessageCount = 2
        )
        ChatHistoryStore(file).save(original)

        // A brand new instance stands in for "the agent/app restarted".
        val restored = ChatHistoryStore(file).load()

        assertEquals(original, restored)
    }

    @Test
    fun `load returns empty snapshot when file contains invalid json`() {
        file.writeText("not valid json")

        val snapshot = ChatHistoryStore(file).load()

        assertFalse(snapshot.messages.isNotEmpty())
    }

    @Test
    fun `load defaults Day 10 context-strategy fields for a pre-Day-10 snapshot file`() {
        // Simulates a file saved before contextStrategy/facts/branches existed —
        // ignoreUnknownKeys plus field defaults should make this a no-op upgrade.
        file.writeText(
            """{"messages":[],"selectedAgentId":"personal-trainer","selectedModel":"gemini-1.5-flash"}"""
        )

        val snapshot = ChatHistoryStore(file).load()

        assertEquals("SUMMARY", snapshot.contextStrategy)
        assertEquals("", snapshot.summary)
        assertEquals(0, snapshot.summarizedMessageCount)
        assertTrue(snapshot.facts.isEmpty())
        assertEquals("main", snapshot.currentBranchId)
    }
}
