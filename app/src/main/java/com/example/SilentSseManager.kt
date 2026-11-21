package com.example

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Maintains a silent Firebase RTDB SSE stream in the background without posting
 * any ongoing foreground service notification.
 */
object SilentSseManager {
    private const val TAG = "SilentSseManager"
    private val running = AtomicBoolean(false)

    @Volatile
    var isStreamConnected: Boolean = false
        private set

    @Volatile
    private var workerThread: Thread? = null

    fun ensureRunning(context: Context) {
        val appCtx = context.applicationContext
        if (!NotificationStore.hasSession(appCtx)) {
            stop()
            return
        }

        if (running.compareAndSet(false, true)) {
            workerThread = Thread({
                try {
                    Log.d(TAG, "Starting silent background notification SSE stream")
                    FirebaseNotifFetcher.readSseLoop(
                        context = appCtx,
                        isRunning = { running.get() && NotificationStore.hasSession(appCtx) },
                        onConnectionChanged = { connected ->
                            isStreamConnected = connected
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Silent SSE loop ended: ${e.message}")
                } finally {
                    isStreamConnected = false
                    running.set(false)
                    workerThread = null
                }
            }, "OrujovSilentSseThread").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        running.set(false)
        isStreamConnected = false
        try {
            workerThread?.interrupt()
        } catch (_: Exception) {
        }
        workerThread = null
    }
}
