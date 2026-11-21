package com.example

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the realtime notification stream alive while the app UI is closed or swiped away,
 * so messages / calls / friend requests are delivered as normal system notifications
 * (and the launcher icon badge is updated) without opening the app.
 *
 * The status notification uses an IMPORTANCE_MIN channel: no sound, no status-bar icon.
 */
class ForegroundSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!NotificationStore.hasSession(applicationContext)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(this), type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        NotificationHelper.createNotificationChannels(applicationContext)
        SilentSseManager.ensureRunning(applicationContext)
        NotificationSyncJobService.scheduleAll(applicationContext)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // App swiped from recents: make sure we come back immediately.
        scheduleRestart(applicationContext, 2_000L)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 timeout safety net (not used for specialUse, but harmless).
        Log.w(TAG, "FGS timeout")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (NotificationStore.hasSession(applicationContext)) {
            scheduleRestart(applicationContext, 5_000L)
        }
    }

    companion object {
        private const val TAG = "FgSyncService"
        const val CHANNEL_ID = "background_sync_min"
        const val NOTIFICATION_ID = 7777
        private const val RESTART_REQUEST_CODE = 7104

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Arxa plan bağlantısı",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Bildirişlərin tətbiq bağlı olanda da gəlməsi üçün. Səssizdir."
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                nm.createNotificationChannel(ch)
            }
        }

        private fun buildNotification(context: Context): Notification {
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pi = PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle("ORUJOV Chat")
                .setContentText("Bildirişlər aktivdir")
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setContentIntent(pi)
                .build()
        }

        /** Safe to call from anywhere; never throws (Android 12+ may forbid background starts). */
        fun start(context: Context) {
            val appCtx = context.applicationContext
            if (!NotificationStore.hasSession(appCtx)) return
            try {
                ensureChannel(appCtx)
                ContextCompat.startForegroundService(appCtx, Intent(appCtx, ForegroundSyncService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start foreground service now: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val appCtx = context.applicationContext
                appCtx.stopService(Intent(appCtx, ForegroundSyncService::class.java))
            } catch (_: Exception) {
            }
        }

        private fun scheduleRestart(context: Context, delayMs: Long) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                // Re-use the sync alarm receiver: it restarts the service as part of its work.
                NotificationAlarmReceiver.scheduleNext(context, delayMs.coerceAtLeast(10_000L))
                val intent = Intent(context, ForegroundSyncService::class.java)
                val pi = PendingIntent.getService(
                    context, RESTART_REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi)
            } catch (e: Exception) {
                Log.w(TAG, "scheduleRestart failed: ${e.message}")
            }
        }
    }
}
