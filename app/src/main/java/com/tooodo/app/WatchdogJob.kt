package com.tooodo.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * 看门狗：系统省电策略或厂商清理可能把常驻服务杀掉且不再拉起，
 * 每 15 分钟检查一次，服务不在就重新启动它。开机后也会继续生效（persisted）。
 */
class WatchdogJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        ScreenWatchService.sync(this)
        return false
    }

    override fun onStopJob(params: JobParameters?) = true

    companion object {
        private const val JOB_ID = 42

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, WatchdogJob::class.java))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        }
    }
}
