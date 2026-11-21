package com.example

import android.content.Context
import org.json.JSONArray

data class FirebaseAuthSession(
    val uid: String,
    val idToken: String,
    val refreshToken: String,
    val apiKey: String,
    val databaseUrl: String,
    val sessionStartMs: Long
)

object NotificationStore {
    private const val PREFS_NAME = "orujov_bg_notif_prefs"
    private const val KEY_UID = "auth_uid"
    private const val KEY_ID_TOKEN = "auth_id_token"
    private const val KEY_REFRESH_TOKEN = "auth_refresh_token"
    private const val KEY_API_KEY = "firebase_api_key"
    private const val KEY_DB_URL = "firebase_db_url"
    private const val KEY_SESSION_START_MS = "session_start_ms"
    private const val KEY_LAST_NOTIF_MS = "last_notif_ms"
    private const val KEY_SEEN_IDS = "seen_notif_ids"
    private const val KEY_UNREAD_BADGE_COUNT = "unread_badge_count"
    private const val MAX_SEEN_IDS = 300

    private val lock = Any()

    @Volatile
    var isAppForeground: Boolean = false

    @Volatile
    var activeChatUid: String = ""

    fun saveSession(
        context: Context,
        uid: String,
        idToken: String,
        refreshToken: String,
        apiKey: String,
        databaseUrl: String,
        loginTimestampMs: Long
    ) {
        if (uid.isBlank() || databaseUrl.isBlank()) return
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val prevUid = prefs.getString(KEY_UID, "") ?: ""
            val existingLastMs = prefs.getLong(KEY_LAST_NOTIF_MS, 0L)
            val effectiveSessionStart = if (prevUid == uid && existingLastMs > 0L) {
                minOf(existingLastMs, loginTimestampMs)
            } else {
                loginTimestampMs
            }

            val existingRefresh = prefs.getString(KEY_REFRESH_TOKEN, "") ?: ""
            val resolvedRefresh = refreshToken.ifBlank { if (prevUid == uid) existingRefresh else "" }

            prefs.edit()
                .putString(KEY_UID, uid)
                .putString(KEY_ID_TOKEN, idToken)
                .putString(KEY_REFRESH_TOKEN, resolvedRefresh)
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_DB_URL, databaseUrl.trimEnd('/'))
                .putLong(KEY_SESSION_START_MS, effectiveSessionStart)
                .apply()

            if (prevUid != uid) {
                prefs.edit()
                    .putLong(KEY_LAST_NOTIF_MS, loginTimestampMs)
                    .putString(KEY_SEEN_IDS, "[]")
                    .putInt(KEY_UNREAD_BADGE_COUNT, 0)
                    .apply()
            }
        }
    }

    fun updateTokens(context: Context, idToken: String, refreshToken: String? = null) {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit().putString(KEY_ID_TOKEN, idToken)
            if (!refreshToken.isNullOrBlank()) {
                editor.putString(KEY_REFRESH_TOKEN, refreshToken)
            }
            editor.apply()
        }
    }

    fun getSession(context: Context): FirebaseAuthSession? {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val uid = prefs.getString(KEY_UID, "") ?: ""
            val dbUrl = prefs.getString(KEY_DB_URL, "") ?: ""
            if (uid.isBlank() || dbUrl.isBlank()) return null
            return FirebaseAuthSession(
                uid = uid,
                idToken = prefs.getString(KEY_ID_TOKEN, "") ?: "",
                refreshToken = prefs.getString(KEY_REFRESH_TOKEN, "") ?: "",
                apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
                databaseUrl = dbUrl,
                sessionStartMs = prefs.getLong(KEY_SESSION_START_MS, 0L)
            )
        }
    }

    fun hasSession(context: Context): Boolean = getSession(context) != null

    fun clearSession(context: Context) {
        synchronized(lock) {
            activeChatUid = ""
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        }
    }

    fun getUnreadBadgeCount(context: Context): Int {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_UNREAD_BADGE_COUNT, 0).coerceAtLeast(0)
        }
    }

    fun setUnreadBadgeCount(context: Context, count: Int) {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_UNREAD_BADGE_COUNT, count.coerceAtLeast(0)).apply()
        }
    }

    fun incrementAndGetBadgeCount(context: Context): Int {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val next = (prefs.getInt(KEY_UNREAD_BADGE_COUNT, 0).coerceAtLeast(0)) + 1
            prefs.edit().putInt(KEY_UNREAD_BADGE_COUNT, next).apply()
            return next
        }
    }

    fun getLastNotifTimestamp(context: Context): Long {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getLong(KEY_LAST_NOTIF_MS, prefs.getLong(KEY_SESSION_START_MS, 0L))
        }
    }

    fun updateLastNotifTimestamp(context: Context, timestampMs: Long) {
        if (timestampMs <= 0L) return
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getLong(KEY_LAST_NOTIF_MS, 0L)
            if (timestampMs > current) {
                prefs.edit().putLong(KEY_LAST_NOTIF_MS, timestampMs).apply()
            }
        }
    }

    /**
     * Atomically checks if [notifId] has been seen before and marks it seen.
     * Returns true if this is the first time seeing [notifId], false if duplicate.
     */
    fun tryMarkNotifSeen(context: Context, notifId: String): Boolean {
        if (notifId.isBlank()) return true
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_SEEN_IDS, "[]") ?: "[]"
            val list = mutableListOf<String>()
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
            } catch (_: Exception) {
            }
            if (list.contains(notifId)) {
                return false
            }
            list.add(notifId)
            while (list.size > MAX_SEEN_IDS) {
                list.removeAt(0)
            }
            val newArr = JSONArray()
            list.forEach { newArr.put(it) }
            prefs.edit().putString(KEY_SEEN_IDS, newArr.toString()).apply()
            return true
        }
    }

    fun isNotifSeen(context: Context, notifId: String): Boolean {
        if (notifId.isBlank()) return false
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_SEEN_IDS, "[]") ?: "[]"
            return try {
                val arr = JSONArray(raw)
                var found = false
                for (i in 0 until arr.length()) {
                    if (arr.getString(i) == notifId) {
                        found = true
                        break
                    }
                }
                found
            } catch (_: Exception) {
                false
            }
        }
    }

    fun shouldSuppressInForeground(senderUid: String): Boolean {
        if (!isAppForeground) return false
        if (senderUid.isBlank()) return false
        return activeChatUid == senderUid
    }
}
