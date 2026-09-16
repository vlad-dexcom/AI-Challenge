package com.example.geminichat.agent.profile

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Day 12: [UserProfileStore] holds a single global record — unlike
 * [com.example.geminichat.agent.memory.WorkingMemoryStore], there's no branch id, and unlike
 * [com.example.geminichat.agent.memory.LongTermMemoryStore] it defaults to a filled-in
 * [UserProfile.STARTER] rather than an empty snapshot on first run.
 */
class UserProfileStoreTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File.createTempFile("user-profile-store-test", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `store returns STARTER when no file exists yet`() {
        val store = UserProfileStore(File(tempDir, UserProfileStore.FILE_NAME))

        assertEquals(UserProfile.STARTER, store.load())
    }

    @Test
    fun `saved edits round-trip and survive a fresh store instance (simulated restart)`() {
        val file = File(tempDir, UserProfileStore.FILE_NAME)
        val edited = UserProfile(
            displayName = "Марк",
            tone = "технично",
            expertise = ExpertiseLevel.ADVANCED,
            constraints = listOf("без прыжков")
        )
        UserProfileStore(file).save(edited)

        val reloaded = UserProfileStore(file).load()

        assertEquals(edited, reloaded)
    }

    @Test
    fun `a corrupted file is treated as no file, not a crash`() {
        val file = File(tempDir, UserProfileStore.FILE_NAME)
        file.parentFile?.mkdirs()
        file.writeText("{ not valid json ")

        val loaded = UserProfileStore(file).load()

        assertEquals(UserProfile.STARTER, loaded)
    }

    @Test
    fun `resetting to EMPTY and saving again overwrites the previous edit`() {
        val file = File(tempDir, UserProfileStore.FILE_NAME)
        val store = UserProfileStore(file)
        store.save(UserProfile(displayName = "Ирина"))

        store.save(UserProfile.EMPTY)

        assertEquals(UserProfile.EMPTY, store.load())
    }
}
