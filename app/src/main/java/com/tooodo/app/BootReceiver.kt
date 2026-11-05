package com.tooodo.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机或应用更新后重新启动亮屏监听。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ScreenWatchService.sync(context)
        }
    }
}
