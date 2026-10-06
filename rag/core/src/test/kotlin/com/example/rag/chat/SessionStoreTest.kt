package com.example.rag.chat

import com.example.core.platform.toKxPath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun roundTripListAndDelete() {
        val store = SessionStore(tmp.newFolder("s").toKxPath())
        val s = store.create()
        val mem = TaskMemory().merge(MemoryPatch(goal = "plan", constraints = mapOf("diet" to "vegetarian")), 1).memory
        val saved = s.copy(title = "t", memory = mem, summary = "sum", summarizedUpTo = 1,
            messages = listOf(ChatMessage("user", "q", 1), ChatMessage("assistant", "a", 1, TurnInfo(llmCalls = 3))))
        store.save(saved)
        assertEquals(saved, store.load(s.id))
        assertEquals(listOf(s.id), store.list().map { it.id })
        assertEquals("plan", store.list().single().goal)
        store.delete(s.id)
        assertNull(store.load(s.id))
    }

    @Test fun rejectsPathTraversalIds() {
        val store = SessionStore(tmp.newFolder("s2").toKxPath())
        assertFalse(SessionStore.isValidId("../x"))
        assertFalse(SessionStore.isValidId(""))
        assertTrue(SessionStore.isValidId("s-0a1b"))
        assertNull(runCatching { store.load("../x") }.getOrNull())
    }
}
