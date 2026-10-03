package com.example.rag.chat

import com.example.rag.TextGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractorTest {
    @Test fun parsesAllFields() {
        val p = MemoryExtractor.parse("""```json
{"goal":"plan","goalChanged":false,"clarifications":["beginner"],"constraints":{"diet":"vegetarian","injury":null},"terms":{},"openQuestions":{"add":["x"],"resolved":["y"]}}
```""").getOrThrow()
        assertEquals("plan", p.goal)
        assertEquals(mapOf("diet" to "vegetarian", "injury" to null), p.constraints)
        assertEquals(listOf("x"), p.openAdd)
        assertEquals(listOf("y"), p.openResolved)
    }

    @Test fun rejectsWrongTypesAndNonObjects() {
        assertTrue(MemoryExtractor.parse("[1]").isFailure)
        assertTrue(MemoryExtractor.parse("not json").isFailure)
        assertTrue(MemoryExtractor.parse("""{"goal":5}""").isFailure)
        assertTrue(MemoryExtractor.parse("""{"constraints":{"a":1}}""").isFailure)
        assertTrue(MemoryExtractor.parse("""{"clarifications":"x"}""").isFailure)
    }

    @Test fun emptyObjectIsAValidNoOp() {
        val p = MemoryExtractor.parse("{}").getOrThrow()
        assertTrue(TaskMemory().merge(p, 1).diff.isEmpty())
    }

    @Test fun callFailureOrBadJsonKeepsMemoryAndExplains() = runTest {
        val failing = MemoryExtractor(TextGenerator { _, _ -> Result.failure(RuntimeException("boom")) })
        val a = failing.extract(TaskMemory(), emptyList(), "hi")
        assertNull(a.patch); assertTrue(a.note!!.contains("boom"))
        val bad = MemoryExtractor(TextGenerator { _, _ -> Result.success("oops") })
        val b = bad.extract(TaskMemory(), emptyList(), "hi")
        assertNull(b.patch); assertNotNull(b.note)
    }

    @Test fun promptCarriesMemoryAndMessage() = runTest {
        var seen = ""
        MemoryExtractor(TextGenerator { _, p -> seen = p; Result.success("{}") })
            .extract(TaskMemory().merge(MemoryPatch(goal = "plan"), 1).memory, emptyList(), "I am vegetarian")
        assertTrue(seen.contains("Goal: plan") && seen.contains("I am vegetarian"))
    }
}
