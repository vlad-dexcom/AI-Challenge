package com.example.rag.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScenarioRunnerTest {
    private val scenario = Scenario(
        "sc", "t", "plan", listOf(listOf("plan")),
        listOf(
            ScenarioTurn("I want a plan, I am vegetarian", constraintHas = listOf(listOf("vegetarian")), diffKinds = listOf("goal"), rewriteHas = listOf(listOf("protein")), answerHas = listOf(listOf("1.6")), sourceFileHas = listOf("protein")),
            ScenarioTurn("unknown thing", expect = "idk"),
        ),
    )
    private val patch = """{"goal":"plan","constraints":{"diet":"vegetarian"}}"""

    @Test fun assertsMemoryStateSourcesAndIdkTurns() = runTest {
        val llm = FakeLlm(extractor = { Result.success(patch) }, answer = { p -> if (p.contains("unknown thing")) Result.success(FakeLlm.IDK) else Result.success(FakeLlm.ANSWER) })
        val run = ScenarioRunner(ChatEngine(fixtureRetriever(), llm), null, ChatOptions(threshold = 0f)).run(scenario, MemoryMode.FULL)
        assertEquals(2, run.turns.size)
        assertTrue(run.turns[0].checks.joinToString { "${it.name}:${it.detail}" }, run.turns[0].checks.all { it.ok })
        assertTrue(run.turns[1].checks.joinToString { "${it.name}:${it.detail}" }, run.turns[1].checks.all { it.ok })
        assertTrue(run.turns[1].checks.any { it.name == "idk_keeps_goal" })
        assertEquals("plan", run.finalMemory!!.goal)
    }

    @Test fun missingMemoryFactIsReportedAsFailedCheck() = runTest {
        val llm = FakeLlm(extractor = { Result.success("""{"goal":"plan"}""") })
        val run = ScenarioRunner(ChatEngine(fixtureRetriever(), llm), null, ChatOptions(threshold = 0f)).run(scenario.copy(turns = scenario.turns.take(1)), MemoryMode.FULL)
        val failed = run.turns[0].checks.filter { !it.ok }.map { it.name }
        assertTrue("constraint_in_memory" in failed && "memory_change_logged" !in failed)
    }

    @Test fun ablationHasNoMemoryChecksAndFailedTurnIsRecordedNotFatal() = runTest {
        var n = 0
        val llm = FakeLlm(answer = { if (++n <= 2) Result.failure(RuntimeException("503")) else Result.success(FakeLlm.ANSWER) })
        val run = ScenarioRunner(ChatEngine(fixtureRetriever(), llm), null, ChatOptions(threshold = 0f)).run(scenario.copy(turns = scenario.turns.take(1) + scenario.turns.take(1)), MemoryMode.HISTORY_ONLY)
        assertTrue(run.turns[0].error != null)
        assertFalse(run.turns[1].checks.any { it.name == "goal_in_memory" })
        assertEquals(null, run.finalMemory?.goal)
    }

    @Test fun shippedScenariosLoadAndHaveTenToFifteenTurns() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "rag/eval/scenarios").isDirectory }
        val dir = File(root, "rag/eval/scenarios")
        val all = ScenarioLoader.loadAll(dir)
        assertEquals(2, all.size)
        all.forEach { assertTrue(it.id, it.turns.size in 10..15) }
    }
}
