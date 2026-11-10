package com.tooodo.app

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 前台服务：SCREEN_ON 广播只能动态注册，所以需要一个常驻服务来接收，
 * 每次亮屏时拉起 WakeActivity 显示未完成的待办。
 */
class ScreenWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    // 亮屏后用户是否已经解锁；指纹/人脸解锁很快，提醒刚弹出就被收起是正常的，不需要再兜底
    private var unlockedSinceScreenOn = false

    // 本次亮屏的时间，用来判断提醒界面是否在这次亮屏后真的显示过
    private var screenOnAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "broadcast ${intent.action}")
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    handler.removeCallbacksAndMessages(null)
                    unlockedSinceScreenOn = false
                    screenOnAt = SystemClock.elapsedRealtime()
                    showWakeScreen()
                }
                // 被杀后重启的进程里没有提醒界面了；趁熄屏（用户不在用手机）预先建好，亮屏时直接切回前台
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacksAndMessages(null)
                    if (!WakeActivity.alive) WakeActivity.warmUp(this@ScreenWatchService)
                }
                // 解锁后提醒就没用了，收起兜底通知
                Intent.ACTION_USER_PRESENT -> {
                    unlockedSinceScreenOn = true
                    getSystemService(NotificationManager::class.java).cancel(ID_WAKE)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        startInForeground()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!TodoStore(this).wakeEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 待办变化后刷新常驻通知上的数量
        startInForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 从最近任务划掉应用时会先回调这里，紧接着 realme/OPPO 会杀掉进程且不会按 START_STICKY 重启服务。
     * 趁进程还在定一个 1 秒后的闹钟，由 RestartReceiver 把服务拉回来（闹钟在进程被杀后依然有效）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed, scheduling restart")
        RestartReceiver.schedule(this, 1000)
        super.onTaskRemoved(rootIntent)
    }

    private fun startInForeground() {
        val count = TodoStore(this).pending().size
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.watch_title))
            .setContentText(getString(R.string.watch_text, count))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_WATCH, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_WATCH, n)
        }
    }

    private fun showWakeScreen() {
        // 只在解锁之前显示。设了锁屏密码的手机在自动熄屏后有一段「宽限期」（比如 5 秒内不上锁），
        // 这时 isKeyguardLocked 为 false，但对用户来说仍是「还没进入手机」，所以也要显示；
        // 完全没设锁屏的手机则不打扰
        val km = getSystemService(KeyguardManager::class.java)
        Log.i(TAG, "screen on: keyguardLocked=${km.isKeyguardLocked} deviceSecure=${km.isDeviceSecure}")
        if (!km.isKeyguardLocked && !km.isDeviceSecure) return
        val store = TodoStore(this)
        val pending = store.pending()
        if (pending.isEmpty() && store.skipWhenEmpty) return

        val intent = Intent(this, WakeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // 有「显示在其他应用上层」权限时可以直接从后台启动界面；否则退回到全屏通知
        if (Settings.canDrawOverlays(this)) {
            startActivity(intent)
            // 部分系统（如 MIUI 的「后台弹出界面」）会静默拦截，startActivity 不报错也不显示；
            // 稍等一下还没显示出来，就改用全屏通知兜底。
            // 只看 visible 不够：用户在 1.5 秒内点了「知道了」时 visible 也是 false，会被误判成被拦截而再弹一次
            handler.postDelayed({
                val screenOn = getSystemService(PowerManager::class.java).isInteractive
                val shown = WakeActivity.shownAt >= screenOnAt
                Log.i(TAG, "wake visible=${WakeActivity.visible} shown=$shown screenOn=$screenOn")
                if (!WakeActivity.visible && !shown && screenOn && !unlockedSinceScreenOn) postFallback(intent, pending)
            }, 1500)
            return
        }
        postFallback(intent, pending)
    }

    private fun postFallback(intent: Intent, pending: List<Todo>) {
        val pi = PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = pending.joinToString("、") { it.text }
        val n = NotificationCompat.Builder(this, CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.wake_count, pending.size))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_WAKE, n)
    }

    companion object {
        private const val TAG = "Tooodo"
        const val CHANNEL_WATCH = "watch"
        const val CHANNEL_WAKE = "wake"
        const val ID_WATCH = 1
        const val ID_WAKE = 2

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_WATCH, context.getString(R.string.channel_watch), NotificationManager.IMPORTANCE_MIN)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_WAKE, context.getString(R.string.channel_wake), NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }

        /** 按设置启动/停止服务，或刷新它的通知。 */
        fun sync(context: Context) {
            val intent = Intent(context, ScreenWatchService::class.java)
            val store = TodoStore(context)
            if (store.wakeEnabled && store.privacyAgreed) {
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: IllegalStateException) {
                    // Android 12+ 在后台启动前台服务可能被拒（ForegroundServiceStartNotAllowedException）；
                    // 看门狗下次运行或用户打开应用时会再试
                }
                WatchdogJob.schedule(context)
            } else {
                context.stopService(intent)
                WatchdogJob.cancel(context)
            }
        }
    }
}
