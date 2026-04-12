package com.todoer.manager

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class TodoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        NotificationHelper.ensureChannels(this)
        TodoSyncWorker.schedule(this)
    }
}
