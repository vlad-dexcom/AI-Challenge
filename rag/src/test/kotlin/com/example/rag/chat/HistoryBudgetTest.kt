package com.example.rag.chat

import com.example.rag.TextGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryBudgetTest {
    private fun session(turns: Int, summarizedUpTo: Int = 0) = ChatSession("s", summarizedUpTo = summarizedUpTo,
        messages = (1..turns).flatMap { listOf(ChatMessage("user", "q$it", it), ChatMessage("assistant", "a$it", it)) })

    @Test fun evictsOnlyFullBatchesOutsideTheWindow() {
        val o = ChatOptions(keepLastTurns = 4, summaryBatch = 2)
        assertTrue(HistoryBudget.evictable(session(5), 5, o).isEmpty())
        assertEquals(listOf(1, 2), HistoryBudget.evictable(session(6), 6, o).map { it.turn }.distinct())
        assertEquals(listOf(3, 4), HistoryBudget.evictable(session(8, 2), 8, o).map { it.turn }.distinct())
    }

    @Test fun verbatimSkipsSummarizedTurnsAndHonoursCharCap() {
        val o = ChatOptions()
        assertEquals(listOf("q3", "a3", "q4", "a4"), HistoryBudget.verbatim(session(4, 2), o).map { it.text })
        val big = ChatSession("s", messages = (1..5).flatMap { listOf(ChatMessage("user", "x".repeat(500), it), ChatMessage("assistant", "y".repeat(300), it)) })
        val v = HistoryBudget.verbatim(big, ChatOptions(maxHistoryChars = 2000))
        assertTrue(v.sumOf { it.text.length } <= 2000 && v.size >= 2)
    }

    @Test fun noneModeHasNoHistory() {
        assertTrue(HistoryBudget.verbatim(session(3), ChatOptions(memoryMode = MemoryMode.NONE)).isEmpty())
        assertTrue(HistoryBudget.evictable(session(9), 9, ChatOptions(memoryMode = MemoryMode.NONE)).isEmpty())
    }

    @Test fun dialogContextAlwaysContainsMemory() {
        assertNull(HistoryBudget.dialogContext(TaskMemory(), "", emptyList()))
        val m = TaskMemory().merge(MemoryPatch(goal = "plan"), 1).memory
        val c = HistoryBudget.dialogContext(m, "earlier", listOf(com.example.rag.HistoryMessage("user", "hi")))!!
        assertTrue(c.contains("Goal: plan") && c.contains("earlier") && c.contains("user: hi"))
    }

    @Test fun summarizerFallsBackToUserLinesOnFailure() = runTest {
        val (s, fallback) = Summarizer(TextGenerator { _, _ -> Result.failure(RuntimeException("x")) }).update("", session(2).messages)
        assertTrue(fallback && s.contains("q1") && s.contains("q2"))
        val (ok, fb) = Summarizer(TextGenerator { _, _ -> Result.success(" short ") }).update("", session(1).messages)
        assertEquals("short", ok); assertTrue(!fb)
    }
}
