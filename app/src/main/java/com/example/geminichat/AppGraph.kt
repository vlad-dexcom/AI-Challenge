package com.example.geminichat

import android.content.Context
import com.example.core.platform.toKxPath
import com.example.geminichat.agent.invariant.InvariantStore
import com.example.geminichat.agent.memory.LongTermMemoryStore
import com.example.geminichat.agent.memory.WorkingMemoryStore
import com.example.geminichat.agent.profile.UserProfileStore
import com.example.geminichat.agent.task.TaskStateStore
import com.example.geminichat.agent.task.TaskTransitionLogStore
import com.example.geminichat.agent.workout.WorkoutLogStore
import com.example.geminichat.agent.workout.WorkoutSummaryStore
import com.example.rag.IndexStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Process-wide object graph: the one place that knows about Android (`filesDir`, assets) and wires
 * the platform-independent [ChatController] to its stores. Feature ViewModels receive the
 * controller from here, so the conversation state survives navigation between screens.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun file(name: String) = File(appContext.filesDir, name).toKxPath()

    val chat: ChatController = ChatController(
        apiKey = BuildConfig.GEMINI_API_KEY,
        scope = scope,
        historyStore = ChatHistoryStore(file(ChatHistoryStore.FILE_NAME)),
        longTermMemoryStore = LongTermMemoryStore(file(LongTermMemoryStore.FILE_NAME)),
        workingMemoryStore = WorkingMemoryStore(file(WorkingMemoryStore.FILE_NAME)),
        userProfileStore = UserProfileStore(file(UserProfileStore.FILE_NAME)),
        taskStateStore = TaskStateStore(file(TaskStateStore.FILE_NAME)),
        invariantStore = InvariantStore(file(InvariantStore.FILE_NAME)),
        taskTransitionLogStore = TaskTransitionLogStore(file(TaskTransitionLogStore.FILE_NAME)),
        workoutLogStore = WorkoutLogStore(file(WorkoutLogStore.FILE_NAME)),
        workoutSummaryStore = WorkoutSummaryStore(file(WorkoutSummaryStore.FILE_NAME)),
        ragIndexLoader = {
            appContext.assets.open(RAG_INDEX_ASSET).bufferedReader().use { IndexStore().parse(it.readText()) }
        },
    )

    private companion object {
        const val RAG_INDEX_ASSET = "rag/structure.json"
    }
}
