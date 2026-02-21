package com.todoer.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.todoer.app.data.preferences.ServerPreferences
import com.todoer.app.data.repository.TodoRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate

class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = ServerPreferences(applicationContext)
        val serverUrl = prefs.serverUrl.first()
        val notificationsEnabled = prefs.notificationsEnabled.first()

        if (serverUrl.isBlank() || !notificationsEnabled) {
            return Result.success()
        }

        val repo = TodoRepository()
        repo.configure(serverUrl)

        return try {
            val allTodos = repo.getAllTodosFlat()
            val today = LocalDate.now()

            var overdueCount = 0
            var dueTodayCount = 0

            for (todo in allTodos) {
                if (todo.completed) continue
                val dueDate = todo.dueDate ?: continue

                val due = try {
                    LocalDate.parse(dueDate)
                } catch (e: Exception) {
                    continue
                }

                when {
                    due.isBefore(today) -> overdueCount++
                    due.isEqual(today) -> dueTodayCount++
                }
            }

            if (overdueCount > 0 || dueTodayCount > 0) {
                NotificationHelper.showSummaryNotification(
                    applicationContext,
                    overdueCount,
                    dueTodayCount
                )
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
