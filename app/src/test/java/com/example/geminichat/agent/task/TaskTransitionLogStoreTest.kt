package com.example.geminichat.agent.task

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskTransitionLogStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `load returns empty list when file does not exist`() {
        val store = TaskTransitionLogStore(File(tempFolder.root, "non_existent.json"))
        assertTrue(store.load("branch-1").isEmpty())
    }

    @Test
    fun `append writes records and reloads accurately`() {
        val file = File(tempFolder.root, "task_log.json")
        val store = TaskTransitionLogStore(file)

        val record1 = TaskTransitionRecord(
            timestamp = 1000L,
            event = TaskEvent.START,
            fromStage = TaskStage.PLANNING,
            toStage = TaskStage.PLANNING,
            applied = true,
            note = "Start"
        )
        val record2 = TaskTransitionRecord(
            timestamp = 2000L,
            event = TaskEvent.APPROVE_PLAN,
            fromStage = TaskStage.PLANNING,
            toStage = TaskStage.EXECUTION,
            applied = true,
            note = "Plan approved"
        )
        val record3 = TaskTransitionRecord(
            timestamp = 3000L,
            event = TaskEvent.COMPLETE,
            fromStage = TaskStage.EXECUTION,
            toStage = null,
            applied = false,
            note = "Cannot complete from execution"
        )

        store.append("main", record1)
        store.append("main", record2)
        store.append("main", record3)

        // Reload from fresh store instance
        val reloadedStore = TaskTransitionLogStore(file)
        val history = reloadedStore.load("main")

        assertEquals(3, history.size)
        assertEquals(record1, history[0])
        assertEquals(record2, history[1])
        assertEquals(record3, history[2])
    }

    @Test
    fun `branches keep independent transition records`() {
        val file = File(tempFolder.root, "task_log.json")
        val store = TaskTransitionLogStore(file)

        val recBranchA = TaskTransitionRecord(100L, TaskEvent.START, TaskStage.PLANNING, TaskStage.PLANNING, true)
        val recBranchB = TaskTransitionRecord(200L, TaskEvent.CANCEL, TaskStage.PLANNING, TaskStage.CANCELLED, true)

        store.append("branch-a", recBranchA)
        store.append("branch-b", recBranchB)

        val storeReloaded = TaskTransitionLogStore(file)
        assertEquals(listOf(recBranchA), storeReloaded.load("branch-a"))
        assertEquals(listOf(recBranchB), storeReloaded.load("branch-b"))
        assertTrue(storeReloaded.load("branch-c").isEmpty())
    }
}
