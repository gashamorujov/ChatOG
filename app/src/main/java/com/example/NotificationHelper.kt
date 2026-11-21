package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.URL
import kotlin.concurrent.thread

object NotificationHelper {

    const val CHANNEL_CHAT_ID = "chat_notifications"
    const val CHANNEL_CHAT_NAME = "Söhbət Mesajları"
    const val CHANNEL_CHAT_DESC = "Yeni daxil olan söhbət mesajları və fayllar üçün bildirişlər"

    const val CHANNEL_CALLS_ID = "calls_notifications"
    const val CHANNEL_CALLS_NAME = "Səsli və Video Zənglər"
    const val CHANNEL_CALLS_DESC = "Daxil olan zənglər üçün yüksək prioritetli bildirişlər"

    const val CHANNEL_FRIENDS_ID = "friends_notifications"
    const val CHANNEL_FRIENDS_NAME = "Dostluq Bildirişləri"
    const val CHANNEL_FRIENDS_DESC = "Dostluq sorğuları və təsdiqləmələr üçün bildirişlər"

    private const val LEGACY_BG_SERVICE_CHANNEL_ID = "background_service_channel"
    private const val TAG = "NotificationHelper"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Remove legacy foreground-service channel if an older build created it
            try {
                notificationManager.deleteNotificationChannel(LEGACY_BG_SERVICE_CHANNEL_ID)
                notificationManager.deleteNotificationChannel("background_sync_min")
            } catch (_: Exception) {
            }

            val chatChannel = NotificationChannel(
                CHANNEL_CHAT_ID,
                CHANNEL_CHAT_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_CHAT_DESC
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }

            val callsChannel = NotificationChannel(
                CHANNEL_CALLS_ID,
                CHANNEL_CALLS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_CALLS_DESC
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }

            val friendsChannel = NotificationChannel(
                CHANNEL_FRIENDS_ID,
                CHANNEL_FRIENDS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_FRIENDS_DESC
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(chatChannel)
            notificationManager.createNotificationChannel(callsChannel)
            notificationManager.createNotificationChannel(friendsChannel)
            Log.d(TAG, "Notification channels registered successfully")
        }
    }

    fun showNotification(
        context: Context,
        title: String,
        body: String,
        type: String,
        senderName: String = "",
        senderDp: String = "",
        chatId: String = "",
        senderUid: String = "",
        notifId: String = "",
        overrideBadgeCount: Int? = null,
        minBadgeCount: Int = 0,
        runAsync: Boolean = true
    ): Boolean {
        if (notifId.isNotBlank() && !NotificationStore.tryMarkNotifSeen(context, notifId)) {
            Log.d(TAG, "Skipping duplicate notification id=$notifId")
            return false
        }

        val appCtx = context.applicationContext
        createNotificationChannels(appCtx)

        val effectiveBadgeCount = if (overrideBadgeCount != null && overrideBadgeCount > 0) {
            NotificationStore.setUnreadBadgeCount(appCtx, overrideBadgeCount)
            overrideBadgeCount
        } else {
            val next = maxOf(NotificationStore.incrementAndGetBadgeCount(appCtx), minBadgeCount)
            NotificationStore.setUnreadBadgeCount(appCtx, next)
            next
        }

        val work = {
            postNotificationInternal(
                context = appCtx,
                title = title,
                body = body,
                type = type,
                senderName = senderName,
                senderDp = senderDp,
                chatId = chatId,
                senderUid = senderUid,
                notifId = notifId,
                badgeCount = effectiveBadgeCount
            )
        }

        return if (runAsync) {
            thread(name = "OrujovNotifPost") { work() }
            true
        } else {
            work()
        }
    }

    private fun postNotificationInternal(
        context: Context,
        title: String,
        body: String,
        type: String,
        senderName: String,
        senderDp: String,
        chatId: String,
        senderUid: String,
        notifId: String,
        badgeCount: Int
    ): Boolean {
        return try {
            val isCall = (type == "call" || type == "incoming_call")
            val channelId = when {
                isCall -> CHANNEL_CALLS_ID
                type == "friend_request" || type == "request_accepted" -> CHANNEL_FRIENDS_ID
                else -> CHANNEL_CHAT_ID
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("notification_type", type)
                putExtra("sender_name", senderName)
                putExtra("chat_id", chatId)
                putExtra("sender_uid", senderUid)
            }

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val requestCode = if (notifId.isNotBlank()) notifId.hashCode() else (System.currentTimeMillis() % 100000).toInt()
            val pendingIntent = PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                pendingIntentFlags
            )

            var avatarBitmap: Bitmap? = null
            if (senderDp.isNotBlank() && senderDp.startsWith("http")) {
                try {
                    val connection = URL(senderDp).openConnection()
                    connection.connectTimeout = 2500
                    connection.readTimeout = 2500
                    avatarBitmap = BitmapFactory.decodeStream(connection.getInputStream())
                } catch (e: Exception) {
                    Log.w(TAG, "Could not download sender avatar: ${e.message}")
                }
            }

            val category = if (isCall) {
                NotificationCompat.CATEGORY_CALL
            } else {
                NotificationCompat.CATEGORY_MESSAGE
            }

            val resolvedTitle = title.ifBlank { senderName.ifBlank { "ORUJOV Chat" } }
            val resolvedBody = body.ifBlank { if (isCall) "Daxil olan zəng" else "Yeni bildiriş" }

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setColor(0xFF00E676.toInt())
                .setContentTitle(resolvedTitle)
                .setContentText(resolvedBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(resolvedBody))
                .setCategory(category)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setNumber(badgeCount)
                .setBadgeIconType(NotificationCompat.BADGE_ICON_LARGE)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setPriority(if (isCall) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)

            avatarBitmap?.let {
                builder.setLargeIcon(it)
            }
            if (isCall) {
                builder.setOngoing(false)
                    .setTimeoutAfter(45_000L)
                    .setFullScreenIntent(pendingIntent, true)
            } else {
                builder.setGroup("orujov_messages")
            }

            val notification = builder.build()
            BadgeHelper.applyNotificationExtra(notification, badgeCount)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationId = if (notifId.isNotBlank()) {
                notifId.hashCode()
            } else {
                (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            }
            notificationManager.notify(notificationId, notification)
            BadgeHelper.applyLauncherBadge(context, badgeCount)
            Log.d(TAG, "Notification displayed: id=$notificationId, key=$notifId, type=$type, badge=$badgeCount")
            true
        } catch (se: SecurityException) {
            Log.w(TAG, "Missing POST_NOTIFICATIONS permission: ${se.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error displaying notification", e)
            false
        }
    }
}
