package com.tooodo.app

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 商店版没有应用内更新（商店不允许绕过它自己安装），这里只显示当前版本，更新交给应用商店。 */
class UpdateSection(activity: AppCompatActivity) {

    init {
        val name = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
        activity.findViewById<TextView>(R.id.update_title).setText(R.string.version_title)
        activity.findViewById<TextView>(R.id.update_status).text = activity.getString(R.string.version_store, name)
        activity.findViewById<View>(R.id.btn_update).visibility = View.GONE
    }

    fun onResume() = Unit
}
