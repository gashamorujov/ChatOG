package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d("BootCompletedReceiver", "Received $action")
            if (NotificationStore.hasSession(context)) {
                NotificationHelper.createNotificationChannels(context)
                NotificationSyncJobService.scheduleAll(context)
                NotificationAlarmReceiver.scheduleNext(context)
                SilentSseManager.ensureRunning(context)
            }
        }
    }
}
