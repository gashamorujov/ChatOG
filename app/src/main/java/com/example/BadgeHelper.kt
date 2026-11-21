package com.example

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Updates numeric unread badge (+1, +2, ...) on launcher icons across Android OEMs
 * (Samsung, Xiaomi MIUI/HyperOS, Huawei/Honor, Sony, OPPO, Vivo, etc.) alongside
 * standard Android 8+ Notification.number badges.
 */
object BadgeHelper {
    private const val TAG = "BadgeHelper"

    fun applyNotificationExtra(notification: Notification, count: Int) {
        val safeCount = count.coerceAtLeast(0)
        notification.number = safeCount
        try {
            // Xiaomi MIUI / HyperOS launcher reads messageCount on extraNotification
            val field = notification.javaClass.getDeclaredField("extraNotification")
            val extraNotification = field.get(notification)
            if (extraNotification != null) {
                val setMessageCount = extraNotification.javaClass.getDeclaredMethod(
                    "setMessageCount",
                    Int::class.javaPrimitiveType
                )
                setMessageCount.invoke(extraNotification, safeCount)
            }
        } catch (_: Throwable) {
        }
    }

    fun applyLauncherBadge(context: Context, rawCount: Int) {
        val count = rawCount.coerceAtLeast(0)
        val appCtx = context.applicationContext
        val packageName = appCtx.packageName
        val launcherClassName = getLauncherClassName(appCtx) ?: MainActivity::class.java.name

        // Samsung / generic Android launcher broadcast
        try {
            val samsungIntent = Intent("android.intent.action.BADGE_COUNT_UPDATE").apply {
                putExtra("badge_count", count)
                putExtra("badge_count_package_name", packageName)
                putExtra("badge_count_class_name", launcherClassName)
            }
            appCtx.sendBroadcast(samsungIntent)
        } catch (_: Throwable) {
        }

        // Samsung One UI ContentProvider
        try {
            val extras = Bundle().apply {
                putString("package", packageName)
                putString("class", launcherClassName)
                putInt("badgenumber", count)
            }
            appCtx.contentResolver.call(
                Uri.parse("content://com.sec.badge/apps"),
                "changeBadge",
                null,
                extras
            )
        } catch (_: Throwable) {
        }

        // Huawei / Honor launcher badge
        try {
            val huaweiExtras = Bundle().apply {
                putString("package", packageName)
                putString("class", launcherClassName)
                putInt("badgenumber", count)
            }
            appCtx.contentResolver.call(
                Uri.parse("content://com.huawei.android.launcher.settings/badge/"),
                "change_badge",
                null,
                huaweiExtras
            )
        } catch (_: Throwable) {
        }

        // Sony launcher badge
        try {
            val sonyIntent = Intent("com.sonyericsson.home.action.UPDATE_BADGE").apply {
                putExtra("com.sonyericsson.home.intent.extra.badge.PACKAGE_NAME", packageName)
                putExtra("com.sonyericsson.home.intent.extra.badge.ACTIVITY_NAME", launcherClassName)
                putExtra("com.sonyericsson.home.intent.extra.badge.MESSAGE", count.toString())
                putExtra("com.sonyericsson.home.intent.extra.badge.SHOW_MESSAGE", count > 0)
            }
            appCtx.sendBroadcast(sonyIntent)
        } catch (_: Throwable) {
        }

        // OPPO / Realme ColorOS badge
        try {
            val oppoIntent = Intent("com.oppo.unsettledevent").apply {
                putExtra("pakeageName", packageName)
                putExtra("number", count)
                putExtra("upgradeNumber", count)
            }
            appCtx.sendBroadcast(oppoIntent)
        } catch (_: Throwable) {
        }

        // Vivo FuntouchOS badge
        try {
            val vivoIntent = Intent("launcher.action.CHANGE_APPLICATION_NOTIFICATION_NUM").apply {
                putExtra("packageName", packageName)
                putExtra("className", launcherClassName)
                putExtra("notificationNum", count)
            }
            appCtx.sendBroadcast(vivoIntent)
        } catch (_: Throwable) {
        }

        // If 0 unread, clear posted tray notifications so Android 8+ launcher dot also clears
        if (count == 0) {
            try {
                val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    nm.activeNotifications.forEach {
                        if (it.id != ForegroundSyncService.NOTIFICATION_ID) nm.cancel(it.tag, it.id)
                    }
                } else {
                    nm.cancelAll()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed clearing notifications: ${e.message}")
            }
        }
    }

    fun clearBadge(context: Context) {
        NotificationStore.setUnreadBadgeCount(context, 0)
        applyLauncherBadge(context, 0)
    }

    private fun getLauncherClassName(context: Context): String? {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            for (info in resolveInfos) {
                if (info.activityInfo.applicationInfo.packageName == context.packageName) {
                    return ComponentName(info.activityInfo.packageName, info.activityInfo.name).className
                }
            }
            MainActivity::class.java.name
        } catch (_: Throwable) {
            MainActivity::class.java.name
        }
    }
}
