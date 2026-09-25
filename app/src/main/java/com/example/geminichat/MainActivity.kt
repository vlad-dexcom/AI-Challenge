package com.example.geminichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.geminichat.agent.invariant.InvariantStore
import com.example.geminichat.agent.memory.LongTermMemoryStore
import com.example.geminichat.agent.memory.WorkingMemoryStore
import com.example.geminichat.agent.profile.UserProfileStore
import com.example.geminichat.agent.task.TaskStateStore
import com.example.geminichat.agent.task.TaskTransitionLogStore
import com.example.geminichat.agent.workout.WorkoutDigestScheduler
import com.example.geminichat.agent.workout.WorkoutLogStore
import com.example.geminichat.agent.workout.WorkoutSummaryStore
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                // Persists chat history to disk (see ChatHistoryStore) so the conversation
                // resumes across app restarts instead of starting empty every launch. Day 11's
                // working/long-term memory layers are persisted in their own separate files
                // (see LongTermMemoryStore/WorkingMemoryStore) rather than mixed into this one.
                // Day 12's single global user profile is likewise its own file (UserProfileStore).
                // Day 13's per-branch task state is its own file too (TaskStateStore), so a
                // paused task survives a restart just like the transcript and memory layers do.
                val historyStore = ChatHistoryStore(File(filesDir, ChatHistoryStore.FILE_NAME))
                val longTermMemoryStore =
                    LongTermMemoryStore(File(filesDir, LongTermMemoryStore.FILE_NAME))
                val workingMemoryStore =
                    WorkingMemoryStore(File(filesDir, WorkingMemoryStore.FILE_NAME))
                val userProfileStore =
                    UserProfileStore(File(filesDir, UserProfileStore.FILE_NAME))
                val taskStateStore =
                    TaskStateStore(File(filesDir, TaskStateStore.FILE_NAME))
                // Day 14: invariants are global and stored in their own file too, entirely
                // outside the dialog — see InvariantStore.
                val invariantStore =
                    InvariantStore(File(filesDir, InvariantStore.FILE_NAME))
                // Day 15: transition log per branch.
                val taskTransitionLogStore =
                    TaskTransitionLogStore(File(filesDir, TaskTransitionLogStore.FILE_NAME))
                // Day 18: workout log + its periodically-aggregated summary — see
                // WorkoutDigestWorker/WorkoutDigestScheduler for the background job that keeps
                // the summary fresh.
                val workoutLogStore = WorkoutLogStore(File(filesDir, WorkoutLogStore.FILE_NAME))
                val workoutSummaryStore =
                    WorkoutSummaryStore(File(filesDir, WorkoutSummaryStore.FILE_NAME))
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(
                    apiKey = BuildConfig.GEMINI_API_KEY,
                    historyStore = historyStore,
                    longTermMemoryStore = longTermMemoryStore,
                    workingMemoryStore = workingMemoryStore,
                    userProfileStore = userProfileStore,
                    taskStateStore = taskStateStore,
                    invariantStore = invariantStore,
                    taskTransitionLogStore = taskTransitionLogStore,
                    workoutLogStore = workoutLogStore,
                    workoutSummaryStore = workoutSummaryStore
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Day 18: registers the periodic workout-digest aggregation once per process — WorkManager
        // persists the schedule itself, so this is safe/idempotent to call on every launch (see
        // WorkoutDigestScheduler's use of ExistingPeriodicWorkPolicy.KEEP).
        WorkoutDigestScheduler.schedule(applicationContext)
        setContent {
            ChatScreen(viewModel = viewModel)
        }
    }
}
