package com.tooodo.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** 设置页的「检查更新」（只有 gitee 侧载版有；商店版见 src/oppo）。 */
class UpdateSection(private val activity: AppCompatActivity) {

    // 去系统设置里开「安装未知应用」期间，等着回来继续下载的版本
    private var waitingForInstallPerm: Updater.Release? = null

    private val button: Button = activity.findViewById(R.id.btn_update)
    private val status: TextView = activity.findViewById(R.id.update_status)

    init {
        status.text = activity.getString(R.string.update_current, currentVersion().second)
        button.setOnClickListener { checkUpdate() }
    }

    fun onResume() {
        val release = waitingForInstallPerm
        if (release != null && activity.packageManager.canRequestPackageInstalls()) {
            waitingForInstallPerm = null
            download(release)
        }
    }

    private fun currentVersion(): Pair<Long, String> {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return info.longVersionCode to (info.versionName ?: "")
    }

    private fun checkUpdate() {
        if (!Updater.configured) {
            status.setText(R.string.update_not_configured)
            return
        }
        button.isEnabled = false
        button.setText(R.string.update_checking)
        thread {
            val result = runCatching { Updater.fetchLatest() }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                button.isEnabled = true
                button.setText(R.string.update_check)
                val (code, name) = currentVersion()
                val release = result.getOrElse {
                    status.setText(R.string.update_failed)
                    return@runOnUiThread
                }
                if (release == null || !Updater.isNewer(release, code)) {
                    status.text = activity.getString(R.string.update_latest, name)
                } else {
                    status.text = activity.getString(R.string.update_current, name)
                    showRelease(release)
                }
            }
        }
    }

    private fun showRelease(release: Updater.Release) {
        val size = activity.getString(R.string.update_found_size, release.size / 1024.0 / 1024.0)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_found_title, release.versionName))
            .setMessage(listOf(release.notes, size).filter { it.isNotBlank() }.joinToString("\n\n"))
            .setPositiveButton(R.string.update_download) { _, _ -> startDownload(release) }
            .setNegativeButton(R.string.perm_dialog_later, null)
            .show()
    }

    /** 先确认能装应用；没开的话带去系统设置，回来在 onResume 里接着下载。 */
    private fun startDownload(release: Updater.Release) {
        if (activity.packageManager.canRequestPackageInstalls()) {
            download(release)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_perm_title)
            .setMessage(R.string.update_perm_msg)
            .setPositiveButton(R.string.perm_dialog_go) { _, _ ->
                waitingForInstallPerm = release
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun download(release: Updater.Release) {
        val cancelled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_found_title, release.versionName))
            .setMessage(activity.getString(R.string.update_downloading, 0))
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled.set(true) }
            .show()
        val dir = File(activity.cacheDir, "update")
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
                        activity.runOnUiThread { dialog.setMessage(activity.getString(R.string.update_downloading, percent)) }
                    }
                }, { cancelled.get() })
            }.getOrDefault(false)
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                dialog.dismiss()
                when {
                    cancelled.get() -> Unit
                    ok -> Updater.install(activity, apk)
                    else -> Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
