package com.example.geminichat.agent.invariant

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Day 14: [InvariantStore] doesn't just persist — [InvariantStore.load] also sanitizes, which
 * is the actual state guarantee behind [Invariant.locked]: a hand-edited file can never leave
 * the app with a core rule silently missing or disabled.
 */
class InvariantStoreTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File.createTempFile("invariant-store-test", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `store returns DEFAULTS when no file exists yet`() {
        val store = InvariantStore(File(tempDir, InvariantStore.FILE_NAME))
        assertEquals(InvariantSet.DEFAULTS, store.load())
    }

    @Test
    fun `saved edits round-trip and survive a fresh store instance (simulated restart)`() {
        val file = File(tempDir, InvariantStore.FILE_NAME)
        val edited = InvariantSet.DEFAULTS.copy(
            invariants = InvariantSet.DEFAULTS.invariants.map {
                if (it.id == "home-equipment-only") it.copy(enabled = false) else it
            }
        )
        InvariantStore(file).save(edited)

        val reloaded = InvariantStore(file).load()

        assertEquals(false, reloaded.byId("home-equipment-only")?.enabled)
    }

    @Test
    fun `a corrupted file is treated as no file, not a crash`() {
        val file = File(tempDir, InvariantStore.FILE_NAME)
        file.parentFile?.mkdirs()
        file.writeText("{ not valid json ")

        assertEquals(InvariantSet.DEFAULTS, InvariantStore(file).load())
    }

    @Test
    fun `a locked invariant removed by hand from the file is re-inserted on load`() {
        val file = File(tempDir, InvariantStore.FILE_NAME)
        val withoutLocked = InvariantSet.DEFAULTS.copy(
            invariants = InvariantSet.DEFAULTS.invariants.filterNot { it.id == "no-ped" }
        )
        InvariantStore(file).save(withoutLocked)

        val reloaded = InvariantStore(file).load()

        assertTrue(reloaded.byId("no-ped")?.locked == true)
    }

    @Test
    fun `a locked invariant disabled by hand in the file is forced back to enabled on load`() {
        val file = File(tempDir, InvariantStore.FILE_NAME)
        val disabledLocked = InvariantSet.DEFAULTS.copy(
            invariants = InvariantSet.DEFAULTS.invariants.map {
                if (it.id == "no-ped") it.copy(enabled = false) else it
            }
        )
        InvariantStore(file).save(disabledLocked)

        val reloaded = InvariantStore(file).load()

        assertEquals(true, reloaded.byId("no-ped")?.enabled)
    }

    @Test
    fun `duplicate ids in the file collapse to a single entry`() {
        val file = File(tempDir, InvariantStore.FILE_NAME)
        val duplicated = InvariantSet(
            InvariantSet.DEFAULTS.invariants + InvariantSet.DEFAULTS.invariants.first()
        )
        InvariantStore(file).save(duplicated)

        val reloaded = InvariantStore(file).load()

        assertEquals(
            InvariantSet.DEFAULTS.invariants.map { it.id }.toSet(),
            reloaded.invariants.map { it.id }.toSet()
        )
        assertEquals(InvariantSet.DEFAULTS.invariants.size, reloaded.invariants.size)
    }
}
