package com.example

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushNotificationTest {

    private lateinit var context: Context
    private lateinit var serverSocket: ServerSocket
    private lateinit var baseUrl: String
    @Volatile
    private var serverRunning = true

    private val notificationsJson = mutableMapOf<String, JSONObject>()
    private val unreadCountsMap = mutableMapOf<String, Int>()
    private val displayedLocallyPutPaths = mutableListOf<String>()
    private var failNextGetWith401 = false
    private var tokenRefreshCalled = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        NotificationStore.clearSession(context)
        NotificationStore.isAppForeground = false
        NotificationStore.activeChatUid = ""
        notificationsJson.clear()
        unreadCountsMap.clear()
        displayedLocallyPutPaths.clear()
        failNextGetWith401 = false
        tokenRefreshCalled = false

        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        baseUrl = "http://127.0.0.1:${serverSocket.localPort}"
        serverRunning = true

        thread(name = "MockFirebaseRtdbServer", isDaemon = true) {
            while (serverRunning && !serverSocket.isClosed) {
                try {
                    val client = serverSocket.accept()
                    handleHttpClient(client)
                } catch (_: Exception) {
                    if (!serverRunning) break
                }
            }
        }

        NotificationStore.saveSession(
            context = context,
            uid = "user_receiver",
            idToken = "initial_token",
            refreshToken = "refresh_tok_123",
            apiKey = "fake_key",
            databaseUrl = baseUrl,
            loginTimestampMs = 1_000_000L
        )
    }

    private fun handleHttpClient(socket: java.net.Socket) {
        socket.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val requestLine = reader.readLine() ?: return
            var contentLength = 0
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.lowercase().startsWith("content-length:")) {
                    contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                reader.read(buf)
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val fullTarget = parts.getOrNull(1) ?: "/"
            val path = fullTarget.substringBefore("?")

            val (statusCode, responseBody) = when {
                path.startsWith("/token") -> {
                    tokenRefreshCalled = true
                    200 to JSONObject().apply {
                        put("id_token", "refreshed_id_token_999")
                        put("refresh_token", "refreshed_refresh_token_999")
                    }.toString()
                }
                path.startsWith("/userNotifications/") && method == "PUT" && path.endsWith("/displayedLocally.json") -> {
                    synchronized(displayedLocallyPutPaths) {
                        displayedLocallyPutPaths.add(path)
                    }
                    200 to "true"
                }
                failNextGetWith401 -> {
                    failNextGetWith401 = false
                    401 to "{\"error\":\"Auth token is expired\"}"
                }
                path.startsWith("/unreadCounts/") -> {
                    200 to JSONObject().apply {
                        unreadCountsMap.forEach { (k, v) -> put(k, v) }
                    }.toString()
                }
                path.startsWith("/userNotifications/") -> {
                    val root = JSONObject()
                    notificationsJson.forEach { (k, v) -> root.put(k, v) }
                    200 to root.toString()
                }
                else -> 404 to "{}"
            }

            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            val statusText = if (statusCode == 200) "OK" else "Unauthorized"
            val headers = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            sock.getOutputStream().apply {
                write(headers.toByteArray(Charsets.UTF_8))
                write(bytes)
                flush()
            }
        }
    }

    @After
    fun tearDown() {
        serverRunning = false
        try { serverSocket.close() } catch (_: Exception) {}
        NotificationStore.clearSession(context)
    }

    @Test
    fun `closed app delivers message notification synchronously with +1 badge and marks displayedLocally`() {
        unreadCountsMap["user_sender"] = 1
        notificationsJson["notif_1"] = JSONObject().apply {
            put("id", "notif_1")
            put("type", "message")
            put("title", "Elvin")
            put("body", "Salam, necəsən?")
            put("senderUid", "user_sender")
            put("senderName", "Elvin")
            put("chatId", "user_receiver_user_sender")
            put("timestamp", 1_005_000L)
            put("displayedLocally", false)
        }

        val shown = FirebaseNotifFetcher.fetchOnceSync(
            context = context,
            baseUrlOverride = baseUrl,
            tokenEndpointOverride = "$baseUrl/token"
        )

        assertEquals(1, shown)
        assertEquals(1, NotificationStore.getUnreadBadgeCount(context))
        assertTrue(displayedLocallyPutPaths.any { it.contains("notif_1/displayedLocally.json") })

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = shadowOf(nm).allNotifications
        assertEquals(1, active.size)
        val postedNotif = active.first()
        assertEquals(1, postedNotif.number)
        assertEquals(NotificationHelper.CHANNEL_CHAT_ID, postedNotif.channelId)
        assertNotNull(postedNotif.contentIntent)

        // Second notification (incoming call) arrives while still outside APK -> badge becomes 2
        unreadCountsMap["user_sender"] = 2
        notificationsJson["notif_2"] = JSONObject().apply {
            put("id", "notif_2")
            put("type", "call")
            put("title", "Gələn səsli zəng")
            put("body", "Elvin sizə zəng edir")
            put("senderUid", "user_sender")
            put("senderName", "Elvin")
            put("callId", "call_77")
            put("timestamp", 1_010_000L)
            put("displayedLocally", false)
        }

        val shown2 = FirebaseNotifFetcher.fetchOnceSync(
            context = context,
            baseUrlOverride = baseUrl,
            tokenEndpointOverride = "$baseUrl/token"
        )
        assertEquals(1, shown2)
        assertEquals(2, NotificationStore.getUnreadBadgeCount(context))
        val latestCallNotif = shadowOf(nm).allNotifications.find { it.channelId == NotificationHelper.CHANNEL_CALLS_ID }
        assertNotNull(latestCallNotif)
        assertEquals(2, latestCallNotif!!.number)
    }

    @Test
    fun `expired idToken automatically refreshes via refreshToken when app is closed`() {
        failNextGetWith401 = true
        unreadCountsMap["user_sender"] = 1
        notificationsJson["notif_auth"] = JSONObject().apply {
            put("id", "notif_auth")
            put("type", "message")
            put("title", "Aysel")
            put("body", "Yeni mesaj")
            put("senderUid", "user_sender")
            put("timestamp", 1_008_000L)
            put("displayedLocally", false)
        }

        val shown = FirebaseNotifFetcher.fetchOnceSync(
            context = context,
            baseUrlOverride = baseUrl,
            tokenEndpointOverride = "$baseUrl/token"
        )

        assertTrue(tokenRefreshCalled)
        assertEquals("refreshed_id_token_999", NotificationStore.getSession(context)?.idToken)
        assertEquals(1, shown)
    }

    @Test
    fun `active chat in foreground suppresses popup notification and does not duplicate`() {
        NotificationStore.isAppForeground = true
        NotificationStore.activeChatUid = "user_sender"

        notificationsJson["notif_fg"] = JSONObject().apply {
            put("id", "notif_fg")
            put("type", "message")
            put("title", "Elvin")
            put("body", "Active chat message")
            put("senderUid", "user_sender")
            put("timestamp", 1_012_000L)
            put("displayedLocally", false)
        }

        val shown = FirebaseNotifFetcher.fetchOnceSync(
            context = context,
            baseUrlOverride = baseUrl,
            tokenEndpointOverride = "$baseUrl/token"
        )

        assertEquals(0, shown)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(0, shadowOf(nm).allNotifications.size)
        assertTrue(NotificationStore.isNotifSeen(context, "notif_fg"))
    }

    @Test
    fun `clearing badge cancels posted tray notifications and resets stored unread count`() {
        NotificationHelper.showNotification(
            context = context,
            title = "Test",
            body = "Mesaj",
            type = "message",
            senderUid = "u2",
            notifId = "n_clear_1",
            runAsync = false
        )
        assertEquals(1, NotificationStore.getUnreadBadgeCount(context))

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(1, shadowOf(nm).allNotifications.size)

        BadgeHelper.clearBadge(context)
        assertEquals(0, NotificationStore.getUnreadBadgeCount(context))
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun `jobScheduler and alarmManager background sync arm properly and cancel on logout`() {
        NotificationSyncJobService.scheduleAll(context)

        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        assertFalse(scheduler.allPendingJobs.isEmpty())

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextAlarm = shadowOf(alarmManager).peekNextScheduledAlarm()
        assertNotNull(nextAlarm)

        NotificationSyncJobService.cancel(context)
        NotificationAlarmReceiver.cancel(context)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test
    fun `boot receiver restores job and alarm scheduling when session exists`() {
        val receiver = BootCompletedReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        assertFalse(scheduler.allPendingJobs.isEmpty())

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `foreground sync service starts only when a session exists`() {
        val app = shadowOf(context as android.app.Application)
        NotificationStore.clearSession(context)
        ForegroundSyncService.start(context)
        assertEquals(null, app.nextStartedService)

        NotificationStore.saveSession(
            context = context, uid = "u", idToken = "t", refreshToken = "r",
            apiKey = "k", databaseUrl = baseUrl, loginTimestampMs = 1_000_000L
        )
        ForegroundSyncService.start(context)
        val started = app.nextStartedService
        assertNotNull(started)
        assertEquals(ForegroundSyncService::class.java.name, started.component?.className)
    }
}
