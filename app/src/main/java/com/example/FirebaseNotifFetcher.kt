package com.example

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object FirebaseNotifFetcher {
    private const val TAG = "FirebaseNotifFetcher"
    private const val CLOCK_SKEW_GRACE_MS = 300_000L

    fun refreshIdToken(
        context: Context,
        session: FirebaseAuthSession,
        tokenEndpointOverride: String? = null
    ): String? {
        if (session.refreshToken.isBlank()) return null
        if (tokenEndpointOverride.isNullOrBlank() && session.apiKey.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            val endpoint = tokenEndpointOverride ?: "https://securetoken.googleapis.com/v1/token?key=${URLEncoder.encode(session.apiKey, "UTF-8")}"
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val body = "grant_type=refresh_token&refresh_token=${URLEncoder.encode(session.refreshToken, "UTF-8")}"
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                val newIdToken = json.optString("id_token", "")
                val newRefreshToken = json.optString("refresh_token", "")
                if (newIdToken.isNotBlank()) {
                    NotificationStore.updateTokens(context, newIdToken, newRefreshToken.ifBlank { null })
                    Log.d(TAG, "Successfully refreshed Firebase idToken")
                    return newIdToken
                }
            } else {
                Log.w(TAG, "Token refresh HTTP ${conn.responseCode}")
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Token refresh error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun buildNotificationsUrl(
        session: FirebaseAuthSession,
        overrideToken: String? = null,
        baseUrlOverride: String? = null
    ): String {
        val dbRoot = (baseUrlOverride ?: session.databaseUrl).trimEnd('/')
        val base = "$dbRoot/userNotifications/${session.uid}.json"
        val token = overrideToken ?: session.idToken
        return if (token.isNotBlank()) {
            "$base?auth=${URLEncoder.encode(token, "UTF-8")}"
        } else {
            base
        }
    }

    fun buildUnreadCountsUrl(
        session: FirebaseAuthSession,
        overrideToken: String? = null,
        baseUrlOverride: String? = null
    ): String {
        val dbRoot = (baseUrlOverride ?: session.databaseUrl).trimEnd('/')
        val base = "$dbRoot/unreadCounts/${session.uid}.json"
        val token = overrideToken ?: session.idToken
        return if (token.isNotBlank()) {
            "$base?auth=${URLEncoder.encode(token, "UTF-8")}"
        } else {
            base
        }
    }

    fun fetchOnce(context: Context) {
        fetchOnceSync(context)
    }

    /**
     * Fetches unread counts and pending notifications synchronously from Firebase RTDB,
     * refreshing the auth token on 401/403 and posting heads-up notifications + numeric badge.
     * Returns number of newly displayed notifications.
     */
    fun fetchOnceSync(
        context: Context,
        baseUrlOverride: String? = null,
        tokenEndpointOverride: String? = null
    ): Int {
        val session = NotificationStore.getSession(context) ?: return 0
        var activeToken: String? = session.idToken.ifBlank { null }

        val unreadResult = executeAuthorizedGet(
            context = context,
            session = session,
            initialToken = activeToken,
            tokenEndpointOverride = tokenEndpointOverride
        ) { tok ->
            buildUnreadCountsUrl(session, tok, baseUrlOverride)
        }
        activeToken = unreadResult.tokenUsed

        val totalUnreadFromDb = parseTotalUnreadCount(unreadResult.body)

        val notifResult = executeAuthorizedGet(
            context = context,
            session = session,
            initialToken = activeToken,
            tokenEndpointOverride = tokenEndpointOverride
        ) { tok ->
            buildNotificationsUrl(session, tok, baseUrlOverride)
        }
        activeToken = notifResult.tokenUsed

        var shownCount = 0
        val rawBody = notifResult.body?.trim().orEmpty()
        if (rawBody.isNotBlank() && rawBody != "null") {
            try {
                val json = JSONObject(rawBody)
                shownCount = processNotificationsMap(
                    context = context,
                    session = session,
                    mapObj = json,
                    currentToken = activeToken,
                    totalUnreadHint = totalUnreadFromDb ?: 0,
                    runSync = true,
                    baseUrlOverride = baseUrlOverride
                )
            } catch (e: Exception) {
                Log.w(TAG, "fetchOnceSync JSON parse error: ${e.message}")
            }
        }

        if (totalUnreadFromDb != null) {
            if (totalUnreadFromDb == 0 && shownCount == 0 && NotificationStore.getUnreadBadgeCount(context) > 0) {
                // Only keep cleared if DB explicitly reports 0
                BadgeHelper.clearBadge(context)
            } else if (totalUnreadFromDb > 0) {
                val synced = maxOf(totalUnreadFromDb, NotificationStore.getUnreadBadgeCount(context))
                NotificationStore.setUnreadBadgeCount(context, synced)
                if (shownCount == 0) {
                    BadgeHelper.applyLauncherBadge(context, synced)
                }
            }
        }
        return shownCount
    }

    private data class HttpGetResult(val body: String?, val tokenUsed: String?)

    private fun executeAuthorizedGet(
        context: Context,
        session: FirebaseAuthSession,
        initialToken: String?,
        tokenEndpointOverride: String?,
        urlBuilder: (String?) -> String
    ): HttpGetResult {
        var token = initialToken
        var conn: HttpURLConnection? = null
        return try {
            var urlStr = urlBuilder(token)
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }
            if ((conn.responseCode == 401 || conn.responseCode == 403) && session.refreshToken.isNotBlank()) {
                conn.disconnect()
                val refreshed = refreshIdToken(context, session, tokenEndpointOverride)
                if (!refreshed.isNullOrBlank()) {
                    token = refreshed
                    urlStr = urlBuilder(token)
                    conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 10000
                        readTimeout = 10000
                    }
                }
            }
            if (conn.responseCode in 200..299) {
                HttpGetResult(conn.inputStream.bufferedReader().use { it.readText() }, token)
            } else {
                Log.w(TAG, "GET $urlStr HTTP ${conn.responseCode}")
                HttpGetResult(null, token)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET error: ${e.message}")
            HttpGetResult(null, token)
        } finally {
            conn?.disconnect()
        }
    }

    internal fun parseTotalUnreadCount(rawJson: String?): Int? {
        val trimmed = rawJson?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        if (trimmed == "null") return 0
        return try {
            val obj = JSONObject(trimmed)
            var sum = 0
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optInt(key, 0)
                if (value > 0) sum += value
            }
            sum
        } catch (_: Exception) {
            null
        }
    }

    fun handleSsePayload(
        context: Context,
        session: FirebaseAuthSession,
        rawDataJson: String,
        currentToken: String?
    ): Int {
        try {
            if (rawDataJson.isBlank() || rawDataJson == "null") return 0
            val root = JSONObject(rawDataJson)
            val path = root.optString("path", "/")
            val dataAny = root.opt("data")
            if (dataAny == null || dataAny == JSONObject.NULL) return 0

            if (path == "/" || path.isBlank()) {
                if (dataAny is JSONObject) {
                    return processNotificationsMap(context, session, dataAny, currentToken, runSync = true)
                }
            } else {
                val cleanKey = path.trimStart('/').substringBefore('/')
                if (cleanKey.isNotBlank() && dataAny is JSONObject) {
                    if (dataAny.has("title") || dataAny.has("body") || dataAny.has("senderUid") || dataAny.has("type")) {
                        val shown = processSingleNotification(context, session, cleanKey, dataAny, currentToken, runSync = true)
                        return if (shown) 1 else 0
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleSsePayload parse error: ${e.message}")
        }
        return 0
    }

    private fun processNotificationsMap(
        context: Context,
        session: FirebaseAuthSession,
        mapObj: JSONObject,
        currentToken: String?,
        totalUnreadHint: Int = 0,
        runSync: Boolean = true,
        baseUrlOverride: String? = null
    ): Int {
        val keys = mapObj.keys()
        val items = mutableListOf<Pair<String, JSONObject>>()
        while (keys.hasNext()) {
            val key = keys.next()
            val child = mapObj.optJSONObject(key) ?: continue
            items.add(key to child)
        }
        items.sortBy { it.second.optLong("timestamp", 0L) }
        var shownCount = 0
        for ((key, obj) in items) {
            if (processSingleNotification(
                    context = context,
                    session = session,
                    notifKey = key,
                    notifObj = obj,
                    currentToken = currentToken,
                    totalUnreadHint = totalUnreadHint,
                    runSync = runSync,
                    baseUrlOverride = baseUrlOverride
                )
            ) {
                shownCount++
            }
        }
        return shownCount
    }

    private fun processSingleNotification(
        context: Context,
        session: FirebaseAuthSession,
        notifKey: String,
        notifObj: JSONObject,
        currentToken: String?,
        totalUnreadHint: Int = 0,
        runSync: Boolean = true,
        baseUrlOverride: String? = null
    ): Boolean {
        if (notifObj.optBoolean("displayedLocally", false)) {
            return false
        }

        val notifTime = notifObj.optLong("timestamp", 0L)
        val thresholdMs = if (session.sessionStartMs > CLOCK_SKEW_GRACE_MS) {
            session.sessionStartMs - CLOCK_SKEW_GRACE_MS
        } else {
            0L
        }
        if (notifTime > 0L && thresholdMs > 0L && notifTime < thresholdMs) {
            return false
        }

        if (NotificationStore.isNotifSeen(context, notifKey)) {
            return false
        }

        val senderUid = notifObj.optString("senderUid", "")
        if (senderUid.isNotBlank() && senderUid == session.uid) {
            NotificationStore.tryMarkNotifSeen(context, notifKey)
            markDisplayedLocally(context, session, notifKey, currentToken, runSync, baseUrlOverride)
            return false
        }

        if (NotificationStore.shouldSuppressInForeground(senderUid)) {
            NotificationStore.tryMarkNotifSeen(context, notifKey)
            if (notifTime > 0L) NotificationStore.updateLastNotifTimestamp(context, notifTime)
            markDisplayedLocally(context, session, notifKey, currentToken, runSync, baseUrlOverride)
            return false
        }

        val title = notifObj.optString("title", "ORUJOV Chat")
        val body = notifObj.optString("body", "Yeni mesaj")
        val type = notifObj.optString("type", "message")
        val senderName = notifObj.optString("senderName", "")
        val senderDp = notifObj.optString("senderDp", "")
        val chatId = notifObj.optString("chatId", "")

        if (notifTime > 0L) {
            NotificationStore.updateLastNotifTimestamp(context, notifTime)
        }

        val posted = NotificationHelper.showNotification(
            context = context,
            title = title,
            body = body,
            type = type,
            senderName = senderName,
            senderDp = senderDp,
            chatId = chatId,
            senderUid = senderUid,
            notifId = notifKey,
            minBadgeCount = totalUnreadHint,
            runAsync = !runSync
        )

        markDisplayedLocally(context, session, notifKey, currentToken, runSync, baseUrlOverride)
        return posted
    }

    private fun markDisplayedLocally(
        context: Context,
        session: FirebaseAuthSession,
        notifKey: String,
        currentToken: String?,
        runSync: Boolean,
        baseUrlOverride: String? = null
    ) {
        val action = {
            var conn: HttpURLConnection? = null
            try {
                val token = NotificationStore.getSession(context)?.idToken?.ifBlank { currentToken } ?: currentToken
                val dbRoot = (baseUrlOverride ?: session.databaseUrl).trimEnd('/')
                val base = "$dbRoot/userNotifications/${session.uid}/$notifKey/displayedLocally.json"
                val urlStr = if (!token.isNullOrBlank()) "$base?auth=${URLEncoder.encode(token, "UTF-8")}" else base
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write("true")
                }
                conn.responseCode
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mark displayedLocally: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
        if (runSync) {
            action()
        } else {
            Thread { action() }.start()
        }
    }

    fun readSseLoop(
        context: Context,
        isRunning: () -> Boolean,
        onConnectionChanged: (Boolean) -> Unit
    ) {
        var backoffMs = 2000L
        while (isRunning()) {
            val session = NotificationStore.getSession(context)
            if (session == null) {
                Log.d(TAG, "No active session; stopping SSE loop")
                break
            }

            var activeToken: String? = session.idToken.ifBlank { null }
            var conn: HttpURLConnection? = null
            try {
                var urlStr = buildNotificationsUrl(session, activeToken)
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Cache-Control", "no-cache")
                    connectTimeout = 15000
                    readTimeout = 90000
                }

                var code = conn.responseCode
                if ((code == 401 || code == 403) && session.refreshToken.isNotBlank()) {
                    conn.disconnect()
                    val refreshed = refreshIdToken(context, session)
                    if (!refreshed.isNullOrBlank()) {
                        activeToken = refreshed
                        urlStr = buildNotificationsUrl(session, activeToken)
                        conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Accept", "text/event-stream")
                            setRequestProperty("Cache-Control", "no-cache")
                            connectTimeout = 15000
                            readTimeout = 90000
                        }
                        code = conn.responseCode
                    }
                }

                if (code in 200..299) {
                    onConnectionChanged(true)
                    backoffMs = 2000L
                    Log.d(TAG, "SSE stream connected for uid=${session.uid}")

                    val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                    var currentEvent = ""
                    val dataBuilder = StringBuilder()

                    while (isRunning()) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("event:") -> {
                                currentEvent = line.substring(6).trim()
                            }
                            line.startsWith("data:") -> {
                                if (dataBuilder.isNotEmpty()) dataBuilder.append('\n')
                                dataBuilder.append(line.substring(5).trim())
                            }
                            line.isEmpty() -> {
                                val dataStr = dataBuilder.toString()
                                dataBuilder.setLength(0)
                                if (currentEvent == "auth_revoked") {
                                    Log.w(TAG, "SSE auth_revoked received, refreshing token...")
                                    refreshIdToken(context, session)
                                    break
                                } else if (currentEvent == "cancel") {
                                    Log.w(TAG, "SSE cancel received")
                                    break
                                } else if ((currentEvent == "put" || currentEvent == "patch") && dataStr.isNotBlank()) {
                                    val freshSession = NotificationStore.getSession(context) ?: break
                                    handleSsePayload(context, freshSession, dataStr, activeToken)
                                }
                                currentEvent = ""
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "SSE connection returned HTTP $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "SSE stream error: ${e.message}")
            } finally {
                onConnectionChanged(false)
                conn?.disconnect()
            }

            if (!isRunning()) break
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(30000L)
        }
    }
}
