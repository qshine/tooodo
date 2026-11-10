package com.tooodo.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 设置里的「检查更新」：从 Gitee 发布页拿最新版本，下载 APK，交给系统安装。
 * 每个发布带两个附件：APK 和 version.json（由 scripts/release.sh 生成）。
 * 系统只允许同一个签名覆盖升级，所以发布包必须和手机上的一样用 debug 签名。
 */
object Updater {
    /** 项目在 Gitee 上的公开仓库，「owner/repo」；改成 OWNER 占位就表示不检查更新 */
    const val REPO = "qqinling/tooodo"
    val configured get() = !REPO.startsWith("OWNER/")
    private const val LATEST_API = "https://gitee.com/api/v5/repos/$REPO/releases/latest"
    private const val VERSION_ASSET = "version.json"

    data class Release(
        val versionCode: Long,
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val sha256: String,
        val size: Long,
    )

    /** 联网，别在主线程调。还没发布过任何版本时返回 null。 */
    fun fetchLatest(): Release? {
        val body = get(LATEST_API)?.trim()
        if (body.isNullOrEmpty() || body == "null") return null
        val assets = JSONObject(body).optJSONArray("assets") ?: return null
        val urls = (0 until assets.length()).map { assets.getJSONObject(it) }
            .associate { it.optString("name") to it.optString("browser_download_url") }
        val meta = urls[VERSION_ASSET] ?: return null
        val json = get(meta) ?: return null
        return parse(json) { name -> urls[name] }
    }

    /** 解析 version.json；[urlOf] 把 APK 文件名换成下载地址。 */
    fun parse(json: String, urlOf: (String) -> String?): Release? {
        val o = JSONObject(json)
        val apk = o.optString("apk")
        return Release(
            versionCode = o.getLong("versionCode"),
            versionName = o.getString("versionName"),
            notes = o.optString("notes"),
            apkUrl = urlOf(apk) ?: return null,
            sha256 = o.getString("sha256").lowercase(),
            size = o.optLong("size"),
        )
    }

    fun isNewer(release: Release, currentCode: Long) = release.versionCode > currentCode

    /** 下载到 [dest]；校验 sha256，不对就删掉并返回 false。[cancelled] 为 true 时中途停下。 */
    fun download(release: Release, dest: File, onProgress: (Long, Long) -> Unit, cancelled: () -> Boolean): Boolean {
        if (dest.exists() && sha256(dest) == release.sha256) return true
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val conn = open(release.apkUrl)
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        if (cancelled()) return false
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
            if (cancelled()) part.delete()
        }
        if (sha256(part) != release.sha256) {
            part.delete()
            return false
        }
        return part.renameTo(dest)
    }

    /** 交给系统安装；结果（包括需要用户确认）回到 [InstallReceiver]。 */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // 自己装的自己才可能免确认；adb 装的第一次、以及 ColorOS 的安装验证仍会弹确认
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("tooodo.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallReceiver::class.java),
                // 系统要往里填安装结果，必须是 MUTABLE
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    private fun get(url: String): String? {
        val conn = open(url)
        try {
            if (conn.responseCode == 404) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "Tooodo-Android")
        if (conn.responseCode !in 200..299 && conn.responseCode != 404) {
            val code = conn.responseCode
            conn.disconnect()
            throw java.io.IOException("HTTP $code")
        }
        return conn
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** 安装结果。需要用户确认时，打开系统的安装确认页。 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            // 装好后进程会被替换，BootReceiver 收到 MY_PACKAGE_REPLACED 会把服务拉起来
            PackageInstaller.STATUS_SUCCESS -> Unit
            // 用户在确认页点了取消
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, context.getString(R.string.update_install_failed, msg), Toast.LENGTH_LONG).show()
            }
        }
    }
}
