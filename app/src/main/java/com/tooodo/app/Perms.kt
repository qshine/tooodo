package com.tooodo.app

import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** 锁屏提醒依赖的两项权限。 */
object Perms {
    fun notificationsOk(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun overlayOk(context: Context) = Settings.canDrawOverlays(context)

    fun allGranted(context: Context) = notificationsOk(context) && overlayOk(context)
}
