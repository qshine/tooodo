package com.tooodo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 按「今天是哪天」给待办分类的逻辑：未完成（锁屏提醒）、以后要做、历史任务。 */
class DateLogicTest {
    // 2026-11-04 是星期三
    private val today = LocalDate.of(2026, 11, 4).toEpochDay()

    private fun once(day: Long, done: Boolean = false) = Todo(id = day * 10 + if (done) 1 else 0, text = "x", day = day, done = done)

    private fun pending(t: Long, vararg todos: Todo) = TodoStore.pendingOn(todos.toList(), t)
    private fun history(t: Long, vararg todos: Todo) = TodoStore.historyOn(todos.toList(), t)
    private fun upcoming(t: Long, vararg todos: Todo) = TodoStore.upcomingOn(todos.toList(), t)

    @Test
    fun onceToday_pendingUntilDone() {
        val open = once(today)
        val done = once(today, done = true)
        assertEquals(listOf(open), pending(today, open, done))
        assertTrue(history(today, open, done).isEmpty())
        assertTrue(upcoming(today, open, done).isEmpty())
    }

    @Test
    fun onceTomorrow_isUpcomingNotPending() {
        val t = once(today + 1)
        assertTrue(pending(today, t).isEmpty())
        assertEquals(listOf(t), upcoming(today, t))
        assertTrue(history(today, t).isEmpty())
    }

    @Test
    fun onceTomorrow_becomesPendingWhenTheDayComes() {
        val t = once(today + 1)
        assertEquals(listOf(t), pending(today + 1, t))
        assertTrue(upcoming(today + 1, t).isEmpty())
    }

    @Test
    fun onceFarFuture_staysUpcomingUntilThatDay() {
        val t = once(today + 16)
        assertTrue(pending(today + 15, t).isEmpty())
        assertEquals(listOf(t), upcoming(today + 15, t))
        assertEquals(listOf(t), pending(today + 16, t))
    }

    @Test
    fun onceYesterday_unfinishedStillRemindsAndIsHistory() {
        val open = once(today - 1)
        val done = once(today - 1, done = true)
        assertEquals(listOf(open), pending(today, open, done))
        assertEquals(listOf(open, done), history(today, open, done))
        assertTrue(upcoming(today, open, done).isEmpty())
    }

    @Test
    fun daily_resetsEachDay() {
        val notYet = Todo(id = 1, text = "x", repeat = Repeat.DAILY, day = today)
        val doneToday = notYet.copy(doneDay = today)
        assertEquals(listOf(notYet), pending(today, notYet))
        assertTrue(pending(today, doneToday).isEmpty())
        // 第二天又变回未完成
        assertEquals(listOf(doneToday), pending(today + 1, doneToday))
    }

    @Test
    fun daily_notDueBeforeItStarts() {
        val t = Todo(id = 1, text = "x", repeat = Repeat.DAILY, day = today)
        assertFalse(t.isDueOn(today - 1))
        assertTrue(t.isDueOn(today))
    }

    @Test
    fun weekly_onlyOnChosenWeekdays() {
        val monWed = Todo(id = 1, text = "x", repeat = Repeat.WEEKLY, weekdays = setOf(1, 3), day = today)
        assertEquals(listOf(monWed), pending(today, monWed)) // 周三
        assertTrue(pending(today + 1, monWed).isEmpty()) // 周四
        assertEquals(listOf(monWed), pending(today + 5, monWed)) // 下周一
    }

    @Test
    fun recurring_neverInHistoryOrUpcoming() {
        val daily = Todo(id = 1, text = "x", repeat = Repeat.DAILY, day = today - 30)
        val weekly = Todo(id = 2, text = "x", repeat = Repeat.WEEKLY, weekdays = setOf(5), day = today + 3)
        assertTrue(history(today, daily, weekly).isEmpty())
        assertTrue(upcoming(today, daily, weekly).isEmpty())
    }

    @Test
    fun repeatLabel() {
        assertEquals(null, once(today).repeatLabel)
        assertEquals("每天", Todo(id = 1, text = "x", repeat = Repeat.DAILY, day = today).repeatLabel)
        assertEquals("每周一、三", Todo(id = 1, text = "x", repeat = Repeat.WEEKLY, weekdays = setOf(3, 1), day = today).repeatLabel)
    }

    @Test
    fun dayLabel_relativeNames() {
        assertEquals("11月3日 星期二（昨天）", Dates.dayLabel(today - 1, today))
        assertEquals("11月4日 星期三", Dates.dayLabel(today, today))
        assertEquals("11月5日 星期四（明天）", Dates.dayLabel(today + 1, today))
        assertEquals("11月6日 星期五（后天）", Dates.dayLabel(today + 2, today))
        assertEquals("11月7日 星期六", Dates.dayLabel(today + 3, today))
    }

    @Test
    fun dayLabel_acrossYearEnd() {
        val dec31 = LocalDate.of(2026, 12, 31).toEpochDay()
        assertEquals("1月1日 星期五（明天）", Dates.dayLabel(dec31 + 1, dec31))
        assertEquals("1月2日", Dates.shortLabel(dec31 + 2))
    }
}
