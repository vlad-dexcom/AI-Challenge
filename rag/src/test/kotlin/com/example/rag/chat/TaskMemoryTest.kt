package com.example.rag.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskMemoryTest {
    private fun patch(goal: String? = null, changed: Boolean = false, c: Map<String, String?> = emptyMap(), cl: List<String> = emptyList()) =
        MemoryPatch(goal = goal, goalChanged = changed, constraints = c, clarifications = cl)

    @Test fun addsGoalConstraintAndClarification() {
        val r = TaskMemory().merge(patch("3-day plan", c = mapOf("Injury" to "knee"), cl = listOf("beginner")), 1)
        assertEquals("3-day plan", r.memory.goal)
        assertEquals(mapOf("injury" to "knee"), r.memory.constraints)
        assertEquals(listOf(Clarification("beginner", 1)), r.memory.clarifications)
        assertEquals(3, r.diff.size)
        assertEquals(3, r.memory.changes.size)
    }

    @Test fun goalIsNeverReplacedWithoutExplicitChangeAndNeverCleared() {
        val m = TaskMemory().merge(patch("plan A"), 1).memory
        val ignored = m.merge(patch("nutrition question"), 2)
        assertEquals("plan A", ignored.memory.goal)
        assertTrue(ignored.notes.single().contains("ignored"))
        assertEquals("plan A", m.merge(patch(null), 3).memory.goal)
        val changed = m.merge(patch("plan B", changed = true), 4)
        assertEquals("plan B", changed.memory.goal)
        assertEquals("plan A", changed.diff.single().old)
    }

    @Test fun correctionOverridesAndIsLogged() {
        val m = TaskMemory().merge(patch(c = mapOf("diet" to "eats everything")), 1).memory
        val r = m.merge(patch(c = mapOf("Diet" to "vegetarian")), 5)
        assertEquals("vegetarian", r.memory.constraints["diet"])
        val ch = r.diff.single()
        assertEquals("correct", ch.kind)
        assertEquals("eats everything", ch.old)
        assertEquals(5, ch.turn)
    }

    @Test fun sameValueAndDuplicatesAreNoOps() {
        val m = TaskMemory().merge(patch(c = mapOf("diet" to "Vegetarian"), cl = listOf("Beginner")), 1).memory
        val r = m.merge(patch(c = mapOf("diet" to "vegetarian"), cl = listOf("beginner!")), 2)
        assertTrue(r.diff.isEmpty())
    }

    @Test fun nullRemovesConstraint() {
        val m = TaskMemory().merge(patch(c = mapOf("injury" to "knee")), 1).memory
        val r = m.merge(patch(c = mapOf("injury" to null)), 2)
        assertTrue(r.memory.constraints.isEmpty())
        assertEquals("remove", r.diff.single().kind)
    }

    @Test fun normalizesCyrillicCaseAndYo() {
        assertEquals(TaskMemory.norm("Ёлка!"), TaskMemory.norm("елка"))
        val m = TaskMemory().merge(patch(cl = listOf("Всё хорошо")), 1).memory
        assertTrue(m.merge(patch(cl = listOf("все хорошо")), 2).diff.isEmpty())
    }

    @Test fun capsDropOldestAndLongValuesAreClipped() {
        var m = TaskMemory()
        for (i in 1..20) m = m.merge(patch(cl = listOf("fact $i")), i).memory
        assertEquals(TaskMemory.MAX_CLARIFICATIONS, m.clarifications.size)
        assertEquals("fact 20", m.clarifications.last().text)
        val long = TaskMemory().merge(patch("g".repeat(1000)), 1).memory
        assertEquals(TaskMemory.MAX_GOAL, long.goal!!.length)
    }

    @Test fun userEditWinsMayClearGoalAndIsMarkedAsUser() {
        val m = TaskMemory().merge(patch("plan", c = mapOf("injury" to "knee"), cl = listOf("beginner")), 1).memory
        val r = m.applyEdit(MemoryEdit(goalSet = true, goal = null, constraints = mapOf("injury" to null), removeClarifications = listOf("beginner")), 2)
        assertNull(r.memory.goal)
        assertTrue(r.memory.constraints.isEmpty() && r.memory.clarifications.isEmpty())
        assertTrue(r.diff.all { it.source == "user" })
    }

    @Test fun openQuestionsAddAndResolve() {
        val m = TaskMemory().withOpenQuestion("which brand?", 1).memory
        assertEquals(listOf("which brand?"), m.openQuestions)
        val r = m.merge(MemoryPatch(openResolved = listOf("brand")), 2)
        assertTrue(r.memory.openQuestions.isEmpty())
    }

    @Test fun renderListsEverythingAndEmptyIsEmpty() {
        assertTrue(TaskMemory().isEmpty)
        val m = TaskMemory().merge(patch("plan", c = mapOf("diet" to "vegetarian")), 1).memory
        assertTrue(m.render().contains("Goal: plan") && m.render().contains("diet: vegetarian"))
        assertTrue(m.searchTerms().contains("vegetarian"))
    }
}
