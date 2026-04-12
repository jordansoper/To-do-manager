package com.todoer.manager

import android.app.Application

class TodoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        TodoSyncWorker.schedule(this)
    }
}
