package com.example

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Registration Token: $token")
        saveToken(applicationContext, token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "From: ${remoteMessage.from}")

        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            Log.d(TAG, "Message data payload: $data")
            val title = data["title"] ?: remoteMessage.notification?.title ?: "ORUJOV Chat"
            val body = data["body"] ?: remoteMessage.notification?.body ?: "Yeni bildiriş"
            val type = data["type"] ?: "chat"
            val senderName = data["sender_name"] ?: data["senderName"] ?: ""
            val senderDp = data["sender_dp"] ?: data["senderDp"] ?: ""
            val chatId = data["chat_id"] ?: data["chatId"] ?: ""
            val senderUid = data["sender_uid"] ?: data["senderUid"] ?: ""
            val notifId = data["id"] ?: data["notif_id"] ?: (remoteMessage.messageId ?: "")

            if (NotificationStore.shouldSuppressInForeground(senderUid)) {
                NotificationStore.tryMarkNotifSeen(applicationContext, notifId)
                return
            }

            NotificationHelper.showNotification(
                context = applicationContext,
                title = title,
                body = body,
                type = type,
                senderName = senderName,
                senderDp = senderDp,
                chatId = chatId,
                senderUid = senderUid,
                notifId = notifId,
                runAsync = false
            )
        } else {
            remoteMessage.notification?.let {
                NotificationHelper.showNotification(
                    context = applicationContext,
                    title = it.title ?: "ORUJOV Chat",
                    body = it.body ?: "",
                    type = "chat",
                    notifId = remoteMessage.messageId ?: "",
                    runAsync = false
                )
            }
        }
    }

    companion object {
        private const val TAG = "MyFirebaseMsgService"
        private const val PREFS_NAME = "fcm_prefs"
        private const val KEY_TOKEN = "fcm_token"

        fun saveToken(context: Context, token: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_TOKEN, token).apply()
        }

        fun getSavedToken(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_TOKEN, "") ?: ""
        }

        fun fetchAndLogToken(context: Context, callback: ((String) -> Unit)? = null) {
            try {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                        val saved = getSavedToken(context)
                        callback?.invoke(saved)
                        return@addOnCompleteListener
                    }
                    val token = task.result
                    Log.d(TAG, "FCM Token retrieved successfully: $token")
                    saveToken(context, token)
                    callback?.invoke(token)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error obtaining FCM token: ${e.message}")
                val saved = getSavedToken(context)
                callback?.invoke(saved)
            }
        }
    }
}
