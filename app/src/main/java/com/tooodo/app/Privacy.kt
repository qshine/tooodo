package com.tooodo.app

import android.app.Activity
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * 隐私政策：首次打开时的同意弹窗，以及设置里的「隐私政策」。
 * 正文是 docs/privacy/privacy.md（构建时打进 assets），和商店里填写的网址 [URL] 是同一份。
 */
object Privacy {
    const val URL = "https://gitee.com/qqinling/tooodo/blob/main/docs/privacy/privacy.md"

    /** 已同意就直接 [onAgreed]；否则先弹同意框，不同意就退出应用。 */
    fun ensureAgreed(activity: Activity, onAgreed: () -> Unit) {
        val store = TodoStore(activity)
        if (store.privacyAgreed) {
            onAgreed()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.privacy_consent_title)
            .setMessage(R.string.privacy_consent_msg)
            .setCancelable(false)
            .setPositiveButton(R.string.privacy_agree) { _, _ ->
                store.privacyAgreed = true
                onAgreed()
            }
            .setNegativeButton(R.string.privacy_disagree) { _, _ -> activity.finishAffinity() }
            // 看完全文回来继续问
            .setNeutralButton(R.string.privacy_read_full) { _, _ -> show(activity) { ensureAgreed(activity, onAgreed) } }
            .show()
    }

    /** 显示隐私政策全文。 */
    fun show(activity: Activity, onClose: () -> Unit = {}) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val text = TextView(activity).apply {
            text = plainText(activity)
            textSize = 14f
            setLineSpacing(0f, 1.3f)
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.privacy_title)
            .setView(ScrollView(activity).apply { addView(text) })
            .setCancelable(false)
            .setPositiveButton(R.string.privacy_close) { _, _ -> onClose() }
            .show()
    }

    /** 去掉 Markdown 标记，在弹窗里当普通文字显示。 */
    private fun plainText(activity: Activity): String =
        activity.assets.open("privacy.md").bufferedReader().use { it.readText() }
            .lines()
            .filterNot { it.startsWith("# ") } // 标题已经在弹窗顶上
            .joinToString("\n") { it.replace(Regex("^#+\\s*"), "").replace("**", "").replace(Regex("^- "), "· ") }
            .trim()
}
