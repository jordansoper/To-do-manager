package com.todoer.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.todoer.app.MainActivity
import com.todoer.app.R

object NotificationHelper {

    const val CHANNEL_ID = "todoer_reminders"
    private const val CHANNEL_NAME = "Task Reminders"
    private const val CHANNEL_DESC = "Notifications for due and overdue tasks"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESC
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun showDueNotification(context: Context, title: String, body: String, notificationId: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }

    fun showSummaryNotification(context: Context, overdueCount: Int, dueTodayCount: Int) {
        val parts = mutableListOf<String>()
        if (overdueCount > 0) parts.add("$overdueCount overdue")
        if (dueTodayCount > 0) parts.add("$dueTodayCount due today")
        if (parts.isEmpty()) return

        val body = "You have ${parts.joinToString(" and ")} task${if (overdueCount + dueTodayCount > 1) "s" else ""}"

        showDueNotification(context, "To-Doer Reminder", body, 0)
    }
}
