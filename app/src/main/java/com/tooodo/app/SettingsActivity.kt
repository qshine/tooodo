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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var store: TodoStore
    private lateinit var wakeSwitch: SwitchCompat

    // 去系统设置里开「安装未知应用」期间，等着回来继续下载的版本
    private var waitingForInstallPerm: Updater.Release? = null

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

        findViewById<TextView>(R.id.update_status).text = getString(R.string.update_current, currentVersion().second)
        findViewById<Button>(R.id.btn_update).setOnClickListener { checkUpdate() }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionRows()
        val release = waitingForInstallPerm
        if (release != null && packageManager.canRequestPackageInstalls()) {
            waitingForInstallPerm = null
            download(release)
        }
    }

    // ---------- 检查更新 ----------

    private fun currentVersion(): Pair<Long, String> {
        val info = packageManager.getPackageInfo(packageName, 0)
        return info.longVersionCode to (info.versionName ?: "")
    }

    private fun checkUpdate() {
        val button = findViewById<Button>(R.id.btn_update)
        val status = findViewById<TextView>(R.id.update_status)
        if (!Updater.configured) {
            status.setText(R.string.update_not_configured)
            return
        }
        button.isEnabled = false
        button.setText(R.string.update_checking)
        thread {
            val result = runCatching { Updater.fetchLatest() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                button.isEnabled = true
                button.setText(R.string.update_check)
                val (code, name) = currentVersion()
                val release = result.getOrElse {
                    status.setText(R.string.update_failed)
                    return@runOnUiThread
                }
                if (release == null || !Updater.isNewer(release, code)) {
                    status.text = getString(R.string.update_latest, name)
                } else {
                    status.text = getString(R.string.update_current, name)
                    showRelease(release)
                }
            }
        }
    }

    private fun showRelease(release: Updater.Release) {
        val size = getString(R.string.update_found_size, release.size / 1024.0 / 1024.0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_found_title, release.versionName))
            .setMessage(listOf(release.notes, size).filter { it.isNotBlank() }.joinToString("\n\n"))
            .setPositiveButton(R.string.update_download) { _, _ -> startDownload(release) }
            .setNegativeButton(R.string.perm_dialog_later, null)
            .show()
    }

    /** 先确认能装应用；没开的话带去系统设置，回来在 onResume 里接着下载。 */
    private fun startDownload(release: Updater.Release) {
        if (packageManager.canRequestPackageInstalls()) {
            download(release)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.update_perm_title)
            .setMessage(R.string.update_perm_msg)
            .setPositiveButton(R.string.perm_dialog_go) { _, _ ->
                waitingForInstallPerm = release
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun download(release: Updater.Release) {
        val cancelled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_found_title, release.versionName))
            .setMessage(getString(R.string.update_downloading, 0))
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled.set(true) }
            .show()
        val dir = File(cacheDir, "update")
        // 旧版本的安装包用不上了
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, "Tooodo-${release.versionCode}.apk")
        thread {
            var lastPercent = -1
            val ok = runCatching {
                Updater.download(release, apk, { done, total ->
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    if (percent != lastPercent) {
                        lastPercent = percent
                        runOnUiThread { dialog.setMessage(getString(R.string.update_downloading, percent)) }
                    }
                }, { cancelled.get() })
            }.getOrDefault(false)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dialog.dismiss()
                when {
                    cancelled.get() -> Unit
                    ok -> Updater.install(this, apk)
                    else -> Toast.makeText(this, R.string.update_download_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
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
