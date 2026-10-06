package com.example.geminichat

import android.app.Application
import com.example.geminichat.agent.workout.WorkoutDigestScheduler

class GeminiChatApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // WorkManager persists the schedule itself, so registering on every launch is idempotent
        // (see WorkoutDigestScheduler's ExistingPeriodicWorkPolicy.KEEP).
        WorkoutDigestScheduler.schedule(applicationContext)
    }
}
