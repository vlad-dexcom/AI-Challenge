package com.example.geminichat.agent.task

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Day 13's "task state is scoped per branch, and survives a restart" requirement — modeled
 * directly on [com.example.geminichat.agent.memory.MemoryLayersTest]'s working-memory-store
 * coverage. */
class TaskStateStoreTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File.createTempFile("task-state-store-test", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `returns NONE for a branch with nothing saved yet`() {
        val store = TaskStateStore(File(tempDir, TaskStateStore.FILE_NAME))

        assertEquals(TaskState.NONE, store.load("main"))
    }

    @Test
    fun `round-trips a saved state for a branch`() {
        val store = TaskStateStore(File(tempDir, TaskStateStore.FILE_NAME))
        val state = TaskState(
            title = "Race prep",
            stage = TaskStage.EXECUTION,
            steps = listOf("Week 1", "Week 2"),
            currentStepIndex = 1,
            paused = true
        )

        store.save("main", state)

        assertEquals(state, store.load("main"))
    }

    @Test
    fun `each branch's state is isolated from every other branch`() {
        val store = TaskStateStore(File(tempDir, TaskStateStore.FILE_NAME))
        val mainState = TaskState(title = "Race prep", stage = TaskStage.EXECUTION)
        val otherState = TaskState(title = "Strength plan", stage = TaskStage.PLANNING)

        store.save("main", mainState)
        store.save("strength-branch", otherState)

        assertEquals(mainState, store.load("main"))
        assertEquals(otherState, store.load("strength-branch"))
    }

    @Test
    fun `remove drops only the given branch's state`() {
        val store = TaskStateStore(File(tempDir, TaskStateStore.FILE_NAME))
        store.save("main", TaskState(title = "Race prep"))
        store.save("other", TaskState(title = "Strength plan"))

        store.remove("main")

        assertEquals(TaskState.NONE, store.load("main"))
        assertEquals(TaskState(title = "Strength plan"), store.load("other"))
    }

    @Test
    fun `corrupt file falls back to NONE instead of throwing`() {
        val file = File(tempDir, TaskStateStore.FILE_NAME)
        file.writeText("{ not valid json")
        val store = TaskStateStore(file)

        assertEquals(TaskState.NONE, store.load("main"))
    }
}
