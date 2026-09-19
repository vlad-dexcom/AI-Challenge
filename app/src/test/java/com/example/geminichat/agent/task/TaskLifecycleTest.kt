package com.example.geminichat.agent.task

import com.example.geminichat.agent.AgentConfig
import com.example.geminichat.agent.AgentRequest
import com.example.geminichat.agent.LlmAgent
import com.example.geminichat.agent.LlmClient
import com.example.geminichat.agent.LlmRequestSpec
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class CountingFakeLlmClient(private val answer: String = "Normal assistant response") : LlmClient {
    var callCount = 0
        private set
    var lastSpec: LlmRequestSpec? = null
        private set

    override suspend fun complete(spec: LlmRequestSpec): Result<String> {
        callCount++
        lastSpec = spec
        return Result.success(answer)
    }
}

/**
 * Day 15: End-to-end lifecycle and controlled transition tests with [LlmAgent] and [TaskStateMachine].
 */
class TaskLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testConfig = AgentConfig(
        id = "test-trainer",
        displayName = "Personal Trainer",
        description = "Trainer agent",
        systemInstruction = "You are a personal trainer.",
        model = "test-model"
    )

    @Test
    fun `assistant refuses jumping to execution before plan is approved with zero token cost`() = runTest {
        val client = CountingFakeLlmClient()
        val agent = LlmAgent(config = testConfig, client = client)

        val planningState = TaskState(
            title = "10km race",
            stage = TaskStage.PLANNING
        )

        // Attempt to bypass planning and demand an execution workout
        val request = AgentRequest(
            userMessage = "Не умничай, дай сразу программу тренировок на 8 недель",
            taskStateSnapshot = planningState
        )

        val result = agent.handle(request)

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()

        // 1. Client was never called (0 LLM calls, zero tokens spent on prompt)
        assertEquals(0, client.callCount)
        assertEquals(0, response.tokenUsage.promptTokens)

        // 2. Clear explanatory refusal explaining current stage and required action
        assertNotNull(response.blockedByStage)
        assertEquals(TaskStage.PLANNING, response.blockedByStage?.currentStage)
        assertEquals(TaskStage.EXECUTION, response.blockedByStage?.attemptedStage)
        assertTrue(response.text.contains("⛔ Не могу выполнить этот запрос"))
        assertTrue(response.text.contains("PLANNING"))
        assertTrue(response.text.contains("до утверждения плана"))
        assertTrue(response.text.contains("Утвердить план"))
    }

    @Test
    fun `after plan is approved execution request passes through to model`() = runTest {
        val client = CountingFakeLlmClient("Вот рекомендации по шагу 1: разминка 10 минут")
        val agent = LlmAgent(config = testConfig, client = client)

        var state = TaskState(title = "10km race", stage = TaskStage.PLANNING)
        val approved = TaskStateMachine.approvePlan(state, listOf("Шаг 1: Разминка", "Шаг 2: Бег 5км"))
        assertTrue(approved is TransitionResult.Applied)
        state = (approved as TransitionResult.Applied).state
        assertEquals(TaskStage.EXECUTION, state.stage)

        val request = AgentRequest(
            userMessage = "Как правильно выполнить шаг 1?",
            taskStateSnapshot = state
        )

        val result = agent.handle(request)

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(1, client.callCount)
        assertNull(response.blockedByStage)
        assertEquals("Вот рекомендации по шагу 1: разминка 10 минут", response.text)
        assertTrue(response.tokenUsage.promptTokens > 0)
    }

    @Test
    fun `assistant refuses completing task directly from execution without validation`() = runTest {
        val client = CountingFakeLlmClient()
        val agent = LlmAgent(config = testConfig, client = client)

        val executionState = TaskState(
            title = "10km race",
            stage = TaskStage.EXECUTION,
            steps = listOf("Шаг 1", "Шаг 2"),
            currentStepIndex = 1
        )

        val request = AgentRequest(
            userMessage = "Все сделано, завершай задачу и закрывай",
            taskStateSnapshot = executionState
        )

        val result = agent.handle(request)

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(0, client.callCount)
        assertNotNull(response.blockedByStage)
        assertTrue(response.text.contains("VALIDATION"))
    }

    @Test
    fun `pause freezes execution, survives persistence, and resumes without losing position`() = runTest {
        val client = CountingFakeLlmClient()
        val agent = LlmAgent(config = testConfig, client = client)

        val taskFile = File(tempFolder.root, "task_state.json")
        val logFile = File(tempFolder.root, "task_log.json")
        val taskStore = TaskStateStore(taskFile)
        val logStore = TaskTransitionLogStore(logFile)

        // 1. Create task, approve plan, step to index 1
        var state = (TaskStateMachine.start("Марафон") as TransitionResult.Applied).state
        state = (TaskStateMachine.approvePlan(state, listOf("Неделя 1", "Неделя 2", "Неделя 3")) as TransitionResult.Applied).state
        state = (TaskStateMachine.nextStep(state) as TransitionResult.Applied).state
        assertEquals(1, state.currentStepIndex)
        assertEquals("Неделя 2", state.currentStep)

        // 2. Pause task
        val pauseResult = TaskStateMachine.pause(state)
        assertTrue(pauseResult is TransitionResult.Applied)
        state = (pauseResult as TransitionResult.Applied).state
        assertTrue(state.paused)

        taskStore.save("branch-1", state)
        logStore.append(
            "branch-1",
            TaskTransitionRecord(100L, TaskEvent.PAUSE, TaskStage.EXECUTION, TaskStage.EXECUTION, true, "Пауза")
        )

        // 3. User attempts work while paused -> blocked by code
        val blockedResult = agent.handle(
            AgentRequest(userMessage = "дай тренировку на сегодня", taskStateSnapshot = state)
        )
        assertTrue(blockedResult.isSuccess)
        val blockedResponse = blockedResult.getOrThrow()
        assertEquals(0, client.callCount)
        assertTrue(blockedResponse.text.contains("на паузе"))
        assertTrue(blockedResponse.text.contains("Возобновить"))

        // 4. Simulate app restart: fresh store instances reload from disk
        val reloadedTaskStore = TaskStateStore(taskFile)
        val reloadedLogStore = TaskTransitionLogStore(logFile)
        val restoredState = reloadedTaskStore.load("branch-1")
        val restoredLog = reloadedLogStore.load("branch-1")

        assertEquals(state, restoredState)
        assertTrue(restoredState.paused)
        assertEquals(1, restoredState.currentStepIndex)
        assertEquals(1, restoredLog.size)
        assertEquals(TaskEvent.PAUSE, restoredLog[0].event)

        // 5. Resume task
        val resumeResult = TaskStateMachine.resume(restoredState)
        assertTrue(resumeResult is TransitionResult.Applied)
        val resumedState = (resumeResult as TransitionResult.Applied).state
        assertFalse(resumedState.paused)
        assertEquals(TaskStage.EXECUTION, resumedState.stage)
        assertEquals(1, resumedState.currentStepIndex)
        assertEquals("Неделя 2", resumedState.currentStep)

        // 6. After resuming, work request passes through to LLM
        val resumeRequestResult = agent.handle(
            AgentRequest(userMessage = "продолжим, как пробежать интервалы?", taskStateSnapshot = resumedState)
        )
        assertTrue(resumeRequestResult.isSuccess)
        assertEquals(1, client.callCount)
    }
}
