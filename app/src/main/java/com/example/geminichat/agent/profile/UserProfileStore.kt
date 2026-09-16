package com.example.geminichat.agent.profile

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val profileJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
}

/**
 * Day 12: persists the single, global [UserProfile] to its own file
 * ([FILE_NAME]), independently of every Day 11 memory store
 * ([com.example.geminichat.agent.memory.LongTermMemoryStore],
 * [com.example.geminichat.agent.memory.WorkingMemoryStore]) — editing the profile can never
 * touch memory, and vice versa. There is no branch id and no map of multiple profiles: unlike
 * [com.example.geminichat.agent.memory.WorkingMemoryStore], this is a single record, mirroring
 * [com.example.geminichat.agent.memory.LongTermMemoryStore]'s "one file, one global value" shape.
 */
class UserProfileStore(private val file: File) {

    /** Reads the saved profile, or [UserProfile.STARTER] the very first time (no file yet) so
     * the user edits a realistic example instead of a blank form. A corrupted file is treated
     * the same as "no file" rather than crashing the app. */
    fun load(): UserProfile {
        if (!file.exists()) return UserProfile.STARTER
        return try {
            profileJson.decodeFromString(UserProfile.serializer(), file.readText())
        } catch (e: SerializationException) {
            UserProfile.STARTER
        } catch (e: IllegalArgumentException) {
            UserProfile.STARTER
        }
    }

    /** Overwrites the saved profile. */
    fun save(profile: UserProfile) {
        file.parentFile?.mkdirs()
        file.writeText(profileJson.encodeToString(UserProfile.serializer(), profile))
    }

    companion object {
        const val FILE_NAME = "user_profile.json"
    }
}
