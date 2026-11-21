package com.example

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlin.concurrent.thread

class NotificationSyncJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!NotificationStore.hasSession(applicationContext)) {
            return false
        }

        thread(name = "OrujovNotifJob") {
            try {
                NotificationHelper.createNotificationChannels(applicationContext)
                FirebaseNotifFetcher.fetchOnceSync(applicationContext)
                ForegroundSyncService.start(applicationContext)
                SilentSseManager.ensureRunning(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "Job sync error: ${e.message}")
            } finally {
                if (NotificationStore.hasSession(applicationContext)) {
                    if (params?.jobId == JOB_ID_FAST) {
                        scheduleFastCheck(applicationContext, forceReschedule = true)
                    }
                    NotificationAlarmReceiver.scheduleNext(applicationContext)
                }
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }

    companion object {
        private const val TAG = "NotifSyncJob"
        private const val JOB_ID_PERIODIC = 7001
        private const val JOB_ID_FAST = 7002

        fun scheduleAll(context: Context) {
            if (!NotificationStore.hasSession(context)) return
            schedulePeriodic(context)
            scheduleFastCheck(context, forceReschedule = false)
            NotificationAlarmReceiver.scheduleNext(context)
        }

        fun schedulePeriodic(context: Context) {
            if (!NotificationStore.hasSession(context)) return
            try {
                val scheduler = context.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                if (scheduler.getPendingJob(JOB_ID_PERIODIC) != null) return

                val component = ComponentName(context.applicationContext, NotificationSyncJobService::class.java)
                val jobInfo = JobInfo.Builder(JOB_ID_PERIODIC, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
                scheduler.schedule(jobInfo)
                Log.d(TAG, "Scheduled periodic background notification sync job")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule periodic job: ${e.message}")
            }
        }

        fun scheduleFastCheck(context: Context, forceReschedule: Boolean = false) {
            if (!NotificationStore.hasSession(context)) return
            try {
                val scheduler = context.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                if (!forceReschedule && scheduler.getPendingJob(JOB_ID_FAST) != null) return

                val component = ComponentName(context.applicationContext, NotificationSyncJobService::class.java)
                val jobInfo = JobInfo.Builder(JOB_ID_FAST, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(15_000L)
                    .setOverrideDeadline(40_000L)
                    .setPersisted(true)
                    .build()
                scheduler.schedule(jobInfo)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule fast sync job: ${e.message}")
            }
        }

        fun cancel(context: Context) {
            try {
                val scheduler = context.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                scheduler.cancel(JOB_ID_PERIODIC)
                scheduler.cancel(JOB_ID_FAST)
            } catch (_: Exception) {
            }
        }
    }
}
