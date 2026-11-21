package com.example

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread

/**
 * Silent Allow-While-Idle Alarm wakeup receiver that checks Firebase RTDB for missed
 * messages/calls even when the app is closed, swiped from Recents, or in Doze mode.
 * Requires no SCHEDULE_EXACT_ALARM permission and shows zero persistent service notification.
 */
class NotificationAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val appCtx = context.applicationContext
        if (!NotificationStore.hasSession(appCtx)) {
            cancel(appCtx)
            return
        }

        val pendingResult = goAsync()
        thread(name = "OrujovAlarmSync") {
            try {
                NotificationHelper.createNotificationChannels(appCtx)
                FirebaseNotifFetcher.fetchOnceSync(appCtx)
                ForegroundSyncService.start(appCtx)
                SilentSseManager.ensureRunning(appCtx)
                NotificationSyncJobService.scheduleAll(appCtx)
            } catch (e: Exception) {
                Log.w(TAG, "Alarm sync error: ${e.message}")
            } finally {
                if (NotificationStore.hasSession(appCtx)) {
                    scheduleNext(appCtx, ALARM_INTERVAL_MS)
                }
                try {
                    pendingResult.finish()
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        private const val TAG = "NotifAlarmReceiver"
        private const val ACTION_SYNC_ALARM = "com.chatog.app.ACTION_SILENT_NOTIF_SYNC"
        private const val REQUEST_CODE = 7103
        const val ALARM_INTERVAL_MS = 25_000L

        private fun createPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context.applicationContext, NotificationAlarmReceiver::class.java).apply {
                action = ACTION_SYNC_ALARM
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getBroadcast(context.applicationContext, REQUEST_CODE, intent, flags)
        }

        fun scheduleNext(context: Context, delayMs: Long = ALARM_INTERVAL_MS) {
            val appCtx = context.applicationContext
            if (!NotificationStore.hasSession(appCtx)) return
            try {
                val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(10_000L)
                val pi = createPendingIntent(appCtx)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule alarm wakeup: ${e.message}")
            }
        }

        fun cancel(context: Context) {
            try {
                val appCtx = context.applicationContext
                val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(createPendingIntent(appCtx))
            } catch (_: Exception) {
            }
        }
    }
}
