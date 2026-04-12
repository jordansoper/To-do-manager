package com.todoer.manager

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.todoer.manager.data.Prefs
import com.todoer.manager.data.TodoApiFactory
import java.util.concurrent.TimeUnit

class TodoSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val base = prefs.baseUrl?.trim().orEmpty()
        if (base.isEmpty()) return Result.success()

        return try {
            val api = TodoApiFactory.create(base, prefs.apiKey?.trim())
            val res = api.getTodos(null)
            NotificationHelper.processAfterFetch(applicationContext, res)
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }

    companion object {
        private const val NAME = "todoer_periodic_sync"

        fun schedule(context: Context) {
            val prefs = Prefs(context)
            if (prefs.baseUrl.isNullOrBlank()) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<TodoSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
