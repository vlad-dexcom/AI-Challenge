package com.example.geminichat.agent.invariant

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val invariantJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
}

/**
 * Day 14: persists the single, global [InvariantSet] to its own file ([FILE_NAME]), completely
 * separate from the chat transcript ([com.example.geminichat.ChatHistoryStore]), the Day 11
 * memory layers, and the Day 12 profile — "invariants live outside the dialog" is the whole
 * point: nothing about how a chat goes can change them except an explicit call into
 * [InvariantRules] from the UI.
 *
 * [load] also *sanitizes* the result, which is the actual state guarantee behind
 * [Invariant.locked]: even a hand-edited `invariants.json` on disk that deletes or disables a
 * locked core invariant is repaired back to locked+enabled the moment it's loaded, so the app
 * can never come up in a state where a core rule is silently missing.
 */
class InvariantStore(private val file: File) {

    /** Reads the saved set, or [InvariantSet.DEFAULTS] the very first time (no file yet); a
     * corrupted file is treated the same as "no file" rather than crashing the app. Every
     * locked invariant from [InvariantSet.DEFAULTS] that is missing is re-inserted, and every
     * locked invariant present is forced back to `enabled = true` regardless of what the file
     * said — see the class doc for why. Duplicate ids collapse to the first occurrence. */
    fun load(): InvariantSet {
        val raw = if (!file.exists()) {
            InvariantSet.DEFAULTS
        } else {
            try {
                invariantJson.decodeFromString(InvariantSet.serializer(), file.readText())
            } catch (e: SerializationException) {
                InvariantSet.DEFAULTS
            } catch (e: IllegalArgumentException) {
                InvariantSet.DEFAULTS
            }
        }
        return sanitize(raw)
    }

    private fun sanitize(set: InvariantSet): InvariantSet {
        val deduped = LinkedHashMap<String, Invariant>()
        for (invariant in set.invariants) {
            if (!deduped.containsKey(invariant.id)) deduped[invariant.id] = invariant
        }
        for (locked in InvariantSet.DEFAULTS.invariants.filter { it.locked }) {
            val existing = deduped[locked.id]
            deduped[locked.id] = when {
                existing == null -> locked
                !existing.enabled -> existing.copy(enabled = true)
                else -> existing
            }
        }
        return InvariantSet(deduped.values.toList())
    }

    /** Overwrites the saved set as-is (callers are expected to only ever pass results that
     * already went through [InvariantRules], which never removes/disables a locked entry). */
    fun save(set: InvariantSet) {
        file.parentFile?.mkdirs()
        file.writeText(invariantJson.encodeToString(InvariantSet.serializer(), set))
    }

    companion object {
        const val FILE_NAME = "invariants.json"
    }
}
