package com.example.rag.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEngineTest {
    private val opts = ChatOptions(threshold = 0f)
    private val goalPatch = """{"goal":"3-day strength plan","clarifications":["beginner"],"constraints":{"injury":"knee injury"}}"""

    private suspend fun engine(llm: FakeLlm) = ChatEngine(fixtureRetriever(), llm)

    @Test fun turnUpdatesMemoryAnswersWithSourcesAndInjectsMemory() = runTest {
        val llm = FakeLlm(extractor = { Result.success(goalPatch) })
        val r = engine(llm).send(ChatSession("s"), "I am a beginner with a knee injury, plan 3 days", opts).getOrThrow()
        assertEquals("3-day strength plan", r.memory.goal)
        assertEquals("knee injury", r.memory.constraints["injury"])
        val info = r.messages.last().info!!
        assertFalse(info.structured!!.idk)
        assertEquals("protein.md#1", info.sources.first().chunkId)
        assertTrue(info.structured!!.quotes.all { it.ok })
        assertTrue(info.memoryDiff.isNotEmpty())
        assertTrue(llm.rewritePrompts.single().contains("knee injury"))
        assertTrue(llm.answerPrompts.single().contains("Goal: 3-day strength plan"))
        assertEquals(1, r.turnCount)
        assertEquals("I am a beginner with a knee injury, plan 3 days".take(50), r.title)
    }

    @Test fun laterTurnsSeeHistoryAndMemory() = runTest {
        val llm = FakeLlm(extractor = { Result.success(goalPatch) })
        val e = engine(llm)
        var s = e.send(ChatSession("s"), "plan please", opts).getOrThrow()
        llm.extractor = { Result.success("{}") }
        s = e.send(s, "what about protein?", opts).getOrThrow()
        assertTrue(llm.rewritePrompts.last().contains("Conversation so far") && llm.rewritePrompts.last().contains("plan please"))
        assertTrue(llm.answerPrompts.last().contains("Dialogue so far") && llm.answerPrompts.last().contains("Goal: 3-day strength plan"))
        assertEquals("3-day strength plan", s.memory.goal)
        assertEquals(2, s.turnCount)
    }

    @Test fun extractorFailureKeepsMemoryUnchangedButTurnSucceeds() = runTest {
        val llm = FakeLlm(extractor = { Result.failure(RuntimeException("down")) })
        val before = TaskMemory().merge(MemoryPatch(goal = "plan"), 1).memory
        val r = engine(llm).send(ChatSession("s", memory = before), "protein?", opts).getOrThrow()
        assertEquals(before, r.memory)
        assertTrue(r.messages.last().info!!.memoryNotes.single().contains("memory unchanged"))
    }

    @Test fun idkKeepsGoalAddsOpenQuestionAndKeepsClosestTopics() = runTest {
        val llm = FakeLlm(extractor = { Result.success(goalPatch) }, answer = { Result.success(FakeLlm.IDK) })
        val r = engine(llm).send(ChatSession("s"), "best vegetarian protein brand?", opts).getOrThrow()
        val info = r.messages.last().info!!
        assertTrue(info.structured!!.idk)
        assertTrue(info.sources.isEmpty())
        assertEquals("3-day strength plan", r.memory.goal)
        assertTrue(r.memory.openQuestions.isNotEmpty())
        assertTrue(r.messages.last().text.contains("3-day strength plan"))
    }

    @Test fun answerFailureIsAtomic() = runTest {
        val llm = FakeLlm(extractor = { Result.success(goalPatch) }, answer = { Result.failure(RuntimeException("503")) })
        val s = ChatSession("s")
        assertTrue(engine(llm).send(s, "q", opts).isFailure)
        assertTrue(s.messages.isEmpty() && s.memory.isEmpty)
    }

    @Test fun ablationModesSkipMemory() = runTest {
        val llm = FakeLlm(extractor = { Result.success(goalPatch) })
        val e = engine(llm)
        val h = e.send(ChatSession("s"), "q1", ChatOptions(memoryMode = MemoryMode.HISTORY_ONLY, threshold = 0f)).getOrThrow()
        assertTrue(llm.extractorPrompts.isEmpty() && h.memory.isEmpty)
        assertFalse(llm.answerPrompts.single().contains("Task memory"))
        val h2 = e.send(h, "q2", ChatOptions(memoryMode = MemoryMode.NONE, threshold = 0f)).getOrThrow()
        assertFalse(llm.answerPrompts.last().contains("Dialogue so far"))
        assertEquals(2, h2.turnCount)
    }

    @Test fun longDialogFoldsOldTurnsIntoSummaryAndKeepsMemory() = runTest {
        val llm = FakeLlm(extractor = { Result.success(goalPatch) })
        val e = engine(llm)
        var s = ChatSession("s")
        val o = ChatOptions(keepLastTurns = 2, summaryBatch = 2, threshold = 0f)
        for (i in 1..6) { s = e.send(s, "question $i", o).getOrThrow(); llm.extractor = { Result.success("{}") } }
        assertTrue(llm.summaries >= 1)
        assertTrue(s.summary.isNotBlank() && s.summarizedUpTo >= 2)
        assertEquals("3-day strength plan", s.memory.goal)
        val last = llm.answerPrompts.last()
        assertTrue(last.contains("Summary of the earlier dialogue") && last.contains("Goal: 3-day strength plan"))
        assertFalse(last.contains("user: question 1"))
    }

    @Test fun blankMessageIsRejected() = runTest {
        val r = runCatching { engine(FakeLlm()).send(ChatSession("s"), "  ", opts) }
        assertTrue(r.isFailure || r.getOrThrow().isFailure)
    }
}
