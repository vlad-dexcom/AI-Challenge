package com.example.geminichat.agent.memory

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Day 11's core "разные типы памяти хранятся отдельно" requirement: each layer's [MemoryStore]
 * writes to its own file and never leaks into another layer or another branch.
 */
class MemoryLayersTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File.createTempFile("memory-layers-test", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun item(key: String, value: String, pinned: Boolean = false, turn: Int = 1) =
        MemoryItem(key = key, value = value, source = if (pinned) MemorySource.USER else MemorySource.ROUTER, turn = turn, pinned = pinned)

    // --- LongTermMemoryStore: a single global snapshot ---

    @Test
    fun `long-term store round-trips a saved snapshot`() {
        val store = LongTermMemoryStore(File(tempDir, LongTermMemoryStore.FILE_NAME))
        val snapshot = MemorySnapshot(mapOf("age" to item("age", "34", pinned = true)))

        store.save(snapshot)

        assertEquals(snapshot, store.load())
    }

    @Test
    fun `long-term store returns empty when no file exists yet`() {
        val store = LongTermMemoryStore(File(tempDir, "missing.json"))

        assertEquals(MemorySnapshot.EMPTY, store.load())
    }

    @Test
    fun `long-term store writes to its own file, never touching working memory's file`() {
        val longTermFile = File(tempDir, LongTermMemoryStore.FILE_NAME)
        val workingFile = File(tempDir, WorkingMemoryStore.FILE_NAME)
        LongTermMemoryStore(longTermFile).save(MemorySnapshot(mapOf("age" to item("age", "34"))))

        assertTrue(longTermFile.exists())
        assertTrue(!workingFile.exists())
    }

    // --- WorkingMemoryStore: one snapshot per branch, isolated from each other ---

    @Test
    fun `working memory store round-trips a saved snapshot for a branch`() {
        val store = WorkingMemoryStore(File(tempDir, WorkingMemoryStore.FILE_NAME))
        val snapshot = MemorySnapshot(mapOf("task" to item("task", "4-week plan")))

        store.save("branch-a", snapshot)

        assertEquals(snapshot, store.load("branch-a"))
    }

    @Test
    fun `working memory for one branch never leaks into another branch`() {
        val store = WorkingMemoryStore(File(tempDir, WorkingMemoryStore.FILE_NAME))
        store.save("branch-a", MemorySnapshot(mapOf("task" to item("task", "cardio plan"))))
        store.save("branch-b", MemorySnapshot(mapOf("task" to item("task", "strength plan"))))

        assertEquals("cardio plan", store.load("branch-a").items["task"]?.value)
        assertEquals("strength plan", store.load("branch-b").items["task"]?.value)
    }

    @Test
    fun `removing one branch's working memory leaves other branches untouched`() {
        val store = WorkingMemoryStore(File(tempDir, WorkingMemoryStore.FILE_NAME))
        store.save("branch-a", MemorySnapshot(mapOf("task" to item("task", "cardio plan"))))
        store.save("branch-b", MemorySnapshot(mapOf("task" to item("task", "strength plan"))))

        store.remove("branch-a")

        assertEquals(MemorySnapshot.EMPTY, store.load("branch-a"))
        assertEquals("strength plan", store.load("branch-b").items["task"]?.value)
    }

    @Test
    fun `working memory store returns empty for a branch that was never saved`() {
        val store = WorkingMemoryStore(File(tempDir, WorkingMemoryStore.FILE_NAME))
        store.save("branch-a", MemorySnapshot(mapOf("task" to item("task", "cardio plan"))))

        assertEquals(MemorySnapshot.EMPTY, store.load("branch-never-touched"))
    }

    // --- Cross-layer isolation: same items, different files, no accidental sharing ---

    @Test
    fun `saving the same key to long-term and working memory keeps them independent`() {
        val longTermStore = LongTermMemoryStore(File(tempDir, LongTermMemoryStore.FILE_NAME))
        val workingStore = WorkingMemoryStore(File(tempDir, WorkingMemoryStore.FILE_NAME))

        longTermStore.save(MemorySnapshot(mapOf("goal" to item("goal", "run a marathon", pinned = true))))
        workingStore.save("branch-a", MemorySnapshot(mapOf("goal" to item("goal", "finish week 1"))))

        assertEquals("run a marathon", longTermStore.load().items["goal"]?.value)
        assertEquals("finish week 1", workingStore.load("branch-a").items["goal"]?.value)
    }

    // --- MemorySnapshot.render() / MemoryAssembler: what actually reaches the prompt ---

    @Test
    fun `render lists items sorted by key as dash-prefixed lines`() {
        val snapshot = MemorySnapshot(
            mapOf(
                "zebra" to item("zebra", "last"),
                "age" to item("age", "34")
            )
        )

        assertEquals("- age: 34\n- zebra: last", snapshot.render())
    }

    @Test
    fun `render is empty for an empty snapshot`() {
        assertEquals("", MemorySnapshot.EMPTY.render())
    }

    @Test
    fun `assembler puts long-term memory before working memory and labels both blocks`() {
        val longTerm = MemorySnapshot(mapOf("age" to item("age", "34", pinned = true)))
        val working = MemorySnapshot(mapOf("task" to item("task", "4-week plan")))

        val assembled = MemoryAssembler.assemble(longTerm, working)

        assertTrue(assembled.longTermBlock.contains("age: 34"))
        assertTrue(assembled.workingBlock.contains("task: 4-week plan"))
        assertTrue(assembled.longTermBlock.startsWith("Long-term memory"))
        assertTrue(assembled.workingBlock.startsWith("Working memory"))
    }

    @Test
    fun `assembler renders empty blocks and zero tokens for empty layers`() {
        val assembled = MemoryAssembler.assemble(MemorySnapshot.EMPTY, MemorySnapshot.EMPTY)

        assertEquals("", assembled.longTermBlock)
        assertEquals("", assembled.workingBlock)
        assertEquals(0, assembled.longTermTokens)
        assertEquals(0, assembled.workingTokens)
    }

    @Test
    fun `assembler estimates tokens per layer independently, not as one combined total`() {
        val longTerm = MemorySnapshot(mapOf("age" to item("age", "34")))
        val working = MemorySnapshot(
            mapOf(
                "task" to item("task", "4-week plan"),
                "week" to item("week", "1 of 4")
            )
        )

        val assembled = MemoryAssembler.assemble(longTerm, working)

        assertTrue(assembled.workingTokens > assembled.longTermTokens)
    }
}
