package com.tooodo.app

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 亮屏时显示在锁屏之上的待办提醒。
 *
 * ColorOS 等系统不允许从后台「新建」界面，但允许把已存在的界面切回前台，
 * 所以这个界面关掉时只退到后台、不销毁；打开应用时也会在后台预先建好一个（warm up）。
 */
class WakeActivity : AppCompatActivity() {

    private lateinit var store: TodoStore

    // 用户解锁（指纹、人脸、密码）后自动收起提醒
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = hide()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alive = true
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        setContentView(R.layout.activity_wake)
        findViewById<View>(R.id.root).padForSystemBars()
        store = TodoStore(this)

        findViewById<Button>(R.id.btn_ok).setOnClickListener { hide() }
        findViewById<Button>(R.id.btn_open).setOnClickListener { openApp() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = hide()
        })
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        applyIntent()
    }

    override fun onDestroy() {
        alive = false
        unregisterReceiver(unlockReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent()
    }

    private val isWarmUp get() = intent.getBooleanExtra(EXTRA_WARM_UP, false)

    /** 预热启动时不显示内容，直接退到后台待命。 */
    private fun applyIntent() {
        findViewById<View>(R.id.root).visibility = if (isWarmUp) View.INVISIBLE else View.VISIBLE
        if (isWarmUp) hide()
    }

    /** 退到后台而不是 finish，保留界面以便下次亮屏能被切回前台。 */
    private fun hide() {
        moveTaskToBack(true)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (isWarmUp) return
        visible = true
        shownAt = SystemClock.elapsedRealtime()
        getSystemService(NotificationManager::class.java).cancel(ScreenWatchService.ID_WAKE)
        render()
    }

    private fun render() {
        val now = Date()
        findViewById<TextView>(R.id.time).text = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
        findViewById<TextView>(R.id.date).text = MainActivity.todayLabel()

        val pending = store.pending()
        findViewById<TextView>(R.id.count).text =
            if (pending.isEmpty()) getString(R.string.wake_all_done) else getString(R.string.wake_count, pending.size)

        val list = findViewById<LinearLayout>(R.id.list)
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        pending.forEach { todo ->
            val row = inflater.inflate(R.layout.item_wake, list, false)
            row.findViewById<TextView>(R.id.text).text = todo.text
            // 点一下直接完成
            row.setOnClickListener {
                store.toggle(todo.id)
                ScreenWatchService.sync(this)
                render()
            }
            list.addView(row)
        }
    }

    companion object {
        /** 服务据此判断后台启动界面是否被系统拦截。 */
        @Volatile
        var visible = false

        /** 界面实例是否还在（进程被杀后会重置为 false）。 */
        @Volatile
        var alive = false

        /** 最近一次真正显示（非预热）的时间，elapsedRealtime。 */
        @Volatile
        var shownAt = 0L

        private const val EXTRA_WARM_UP = "warm_up"

        /** 后台还没有提醒界面就预先建一个（应用在前台时，或服务在熄屏时调用）。 */
        fun warmUp(context: Context) {
            if (alive || !TodoStore(context).wakeEnabled) return
            val intent = Intent(context, WakeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_WARM_UP, true)
            context.startActivity(intent, ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle())
        }
    }

    private fun openApp() {
        val km = getSystemService(KeyguardManager::class.java)
        val launch = {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hide()
        }
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = launch()
            })
        } else {
            launch()
        }
    }
}
