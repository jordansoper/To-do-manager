package com.todoer.app

import android.app.Application
import com.todoer.app.notifications.NotificationHelper

class ToDoerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
    }
}
