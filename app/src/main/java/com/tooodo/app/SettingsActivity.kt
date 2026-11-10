package com.tooodo.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var store: TodoStore
    private lateinit var wakeSwitch: SwitchCompat

    private lateinit var updates: UpdateSection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.root).padForSystemBars()
        store = TodoStore(this)

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }

        wakeSwitch = findViewById(R.id.switch_wake)
        wakeSwitch.isChecked = store.wakeEnabled
        wakeSwitch.setOnCheckedChangeListener { _, checked ->
            store.wakeEnabled = checked
            if (checked) requestNotificationsIfNeeded()
            ScreenWatchService.sync(this)
        }

        val skip = findViewById<SwitchCompat>(R.id.switch_skip)
        skip.isChecked = store.skipWhenEmpty
        skip.setOnCheckedChangeListener { _, checked -> store.skipWhenEmpty = checked }

        findViewById<Button>(R.id.btn_notify).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btn_app_details).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btn_preview).setOnClickListener {
            startActivity(Intent(this, WakeActivity::class.java))
        }

        updates = UpdateSection(this)
        findViewById<Button>(R.id.btn_privacy).setOnClickListener { Privacy.show(this) }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionRows()
        updates.onResume()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionRows()
        ScreenWatchService.sync(this)
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun updatePermissionRows() {
        val notifyOk = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val overlayOk = Settings.canDrawOverlays(this)
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        setRow(R.id.status_notify, R.id.btn_notify, notifyOk)
        setRow(R.id.status_overlay, R.id.btn_overlay, overlayOk)
        setRow(R.id.status_battery, R.id.btn_battery, batteryOk)
    }

    private fun setRow(statusId: Int, buttonId: Int, ok: Boolean) {
        findViewById<TextView>(statusId).visibility = if (ok) View.VISIBLE else View.GONE
        findViewById<Button>(buttonId).visibility = if (ok) View.GONE else View.VISIBLE
    }
}
