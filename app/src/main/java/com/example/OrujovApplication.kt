package com.example

import android.app.Application

class OrujovApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)
        if (NotificationStore.hasSession(this)) {
            ForegroundSyncService.start(this)
            SilentSseManager.ensureRunning(this)
            NotificationSyncJobService.scheduleAll(this)
            NotificationAlarmReceiver.scheduleNext(this)
        }
    }
}
