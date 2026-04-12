package com.todoer.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.todoer.manager.data.Prefs
import com.todoer.manager.data.RootSnap
import com.todoer.manager.data.TodoNode
import com.todoer.manager.data.collectDueToday
import com.todoer.manager.data.rootSnapshots
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object NotificationHelper {
    private const val CH_DUE = "todoer_due"
    private const val CH_REC = "todoer_recurring"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CH_DUE,
                context.getString(R.string.channel_due),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_REC,
                context.getString(R.string.channel_recurring),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    fun processAfterFetch(context: Context, roots: List<TodoNode>) {
        val prefs = Prefs(context)
        val gson = Gson()
        val type = object : TypeToken<Map<String, RootSnap>>() {}.type
        val oldJson = prefs.lastRootSnapshotJson()
        val oldMap: Map<Int, RootSnap> =
            if (oldJson.isNullOrBlank()) {
                emptyMap()
            } else {
                val raw: Map<String, RootSnap> = gson.fromJson(oldJson, type) ?: emptyMap()
                raw.mapKeys { it.key.toInt() }
            }
        val newMap = rootSnapshots(roots)

        if (oldMap.isNotEmpty()) {
            for ((id, new) in newMap) {
                val old = oldMap[id] ?: continue
                if (old.completed && !new.completed && !new.recurrence.isNullOrBlank()) {
                    val title = roots.find { it.id == id }?.title ?: "Task"
                    showRecurring(context, id, title)
                }
            }
        }

        prefs.setLastRootSnapshotJson(
            gson.toJson(newMap.mapKeys { it.key.toString() })
        )

        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val dueList = mutableListOf<Pair<Int, String>>()
        collectDueToday(roots, today, dueList)

        if (oldJson.isNullOrBlank()) {
            prefs.markDueNotified(
                today,
                dueList.map { it.first.toString() }.toSet()
            )
            return
        }

        var notified = prefs.dueNotifiedIds().toMutableSet()
        if (prefs.dueNotifiedDate() != today) {
            notified = mutableSetOf()
        }
        for ((id, title) in dueList) {
            val key = id.toString()
            if (!notified.contains(key)) {
                showDue(context, id, title)
                notified.add(key)
            }
        }
        prefs.markDueNotified(today, notified)
    }

    private fun showDue(context: Context, id: Int, title: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(context, CH_DUE)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.notif_due_title))
            .setContentText(title)
            .setAutoCancel(true)
            .build()
        nm.notify(10_000 + id, n)
    }

    private fun showRecurring(context: Context, id: Int, title: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(context, CH_REC)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.notif_recurring_title))
            .setContentText(title)
            .setAutoCancel(true)
            .build()
        nm.notify(20_000 + id, n)
    }
}
