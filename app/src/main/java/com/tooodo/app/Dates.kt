package com.tooodo.app

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 日期显示文字，和 Android 无关，方便单元测试。 */
object Dates {
    private val DAY_FORMAT = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)
    private val SHORT_DAY_FORMAT = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

    /** 「11月2日 星期一（昨天）」「11月5日 星期四（明天）」 */
    fun dayLabel(day: Long, today: Long): String {
        val label = LocalDate.ofEpochDay(day).format(DAY_FORMAT)
        return when (day - today) {
            -1L -> "$label（昨天）"
            1L -> "$label（明天）"
            2L -> "$label（后天）"
            else -> label
        }
    }

    /** 「11月20日」，用在「选日期」按钮上 */
    fun shortLabel(day: Long): String = LocalDate.ofEpochDay(day).format(SHORT_DAY_FORMAT)
}
