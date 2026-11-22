package com.tooodo.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class TaskTag(val id: Int, val name: String, val color: Int)

enum class Repeat { ONCE, DAILY, WEEKLY }

/**
 * 日期统一用 epochDay（LocalDate.toEpochDay）表示。
 * - 单次任务属于 [day] 那一天，用 [done] 记录是否完成；过了那天还没完成就进入「历史」。
 * - 周期任务从 [day] 起生效，每天各自完成：[doneDay] 是最近一次完成的日期，换一天自然又变回未完成。
 * - [weekdays] 仅 WEEKLY 用，1=周一 … 7=周日。
 */
data class Todo(
    val id: Long,
    val text: String,
    val repeat: Repeat = Repeat.ONCE,
    val weekdays: Set<Int> = emptySet(),
    val day: Long,
    val done: Boolean = false,
    val doneDay: Long = -1,
    val tagId: Int? = null,
) {
    fun isDueOn(d: Long): Boolean = when (repeat) {
        Repeat.ONCE -> day == d
        Repeat.DAILY -> d >= day
        Repeat.WEEKLY -> d >= day && LocalDate.ofEpochDay(d).dayOfWeek.value in weekdays
    }

    fun isDoneOn(d: Long): Boolean = if (repeat == Repeat.ONCE) done else doneDay == d

    /** 「每天」「每周一、三」；单次任务没有标签。 */
    val repeatLabel: String?
        get() = when (repeat) {
            Repeat.ONCE -> null
            Repeat.DAILY -> "每天"
            Repeat.WEEKLY -> "每周" + weekdays.sorted().joinToString("、") { WEEKDAY_NAMES[it - 1] }
        }

    companion object {
        val WEEKDAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")
    }
}

/** 待办和设置都存在 SharedPreferences 里，数据量很小，够用。 */
class TodoStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("tooodo", Context.MODE_PRIVATE),
    )

    fun all(): List<Todo> {
        val arr = JSONArray(prefs.getString(KEY_TODOS, "[]"))
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    fun tags(): List<TaskTag> {
        val arr = JSONArray(prefs.getString(KEY_TAGS, "[]"))
        return (0 until arr.length()).map { index ->
            val tag = arr.getJSONObject(index)
            TaskTag(tag.getInt("id"), tag.getString("name"), tag.getInt("color"))
        }
    }

    fun addTag(name: String): Boolean {
        val trimmed = name.trim()
        val tags = tags()
        if (trimmed.isEmpty() || tags.any { it.name.equals(trimmed, ignoreCase = true) }) return false

        val id = nextTagId(tags)
        val color = TAG_COLORS.getOrNull(id - 1)
            ?: Color.HSVToColor(floatArrayOf((id * 137.508f) % 360, 0.8f, 0.48f))
        prefs.edit()
            .putString(KEY_TAGS, encodeTags(tags + TaskTag(id, trimmed, color)))
            .putInt(KEY_NEXT_TAG_ID, id + 1)
            .apply()
        return true
    }

    /** 删除标签时保留待办，只去掉关联；两份数据一起保存。 */
    fun deleteTag(id: Int) {
        val tags = tags()
        if (tags.none { it.id == id }) return
        val todos = all().map { if (it.tagId == id) it.copy(tagId = null) else it }
        prefs.edit()
            .putString(KEY_TAGS, encodeTags(tags.filterNot { it.id == id }))
            .putString(KEY_TODOS, encodeTodos(todos))
            // 旧版本没有计数器，删除最高 ID 前先保留它，避免以后重用。
            .putInt(KEY_NEXT_TAG_ID, nextTagId(tags))
            .apply()
    }

    private fun nextTagId(tags: List<TaskTag>): Int =
        maxOf(prefs.getInt(KEY_NEXT_TAG_ID, 1), (tags.maxOfOrNull { it.id } ?: 0) + 1)

    private fun existingTagId(id: Int?): Int? = id?.takeIf { tagId -> tags().any { it.id == tagId } }

    fun pending(): List<Todo> = pendingOn(all(), today())

    /** [day] 只对单次任务有意义，可以是今天以后的某天；周期任务总是从今天开始。 */
    fun add(
        text: String,
        repeat: Repeat = Repeat.ONCE,
        weekdays: Set<Int> = emptySet(),
        day: Long = today(),
        tagId: Int? = null,
    ) {
        val d = if (repeat == Repeat.ONCE) day else today()
        save(all() + Todo(System.currentTimeMillis(), text, repeat, weekdays, d, tagId = existingTagId(tagId)))
    }

    fun toggle(id: Long) {
        val t = today()
        save(all().map {
            when {
                it.id != id -> it
                it.repeat == Repeat.ONCE -> it.copy(done = !it.done)
                else -> it.copy(doneDay = if (it.doneDay == t) -1 else t)
            }
        })
    }

    /** 改文字和日期；周期任务的日期是生效日，不在这里改。 */
    fun update(id: Long, text: String, day: Long) {
        save(all().map { if (it.id == id) it.copy(text = text, day = if (it.repeat == Repeat.ONCE) day else it.day) else it })
    }

    /** 删掉并返回原来的位置和内容，供「撤销」放回去。 */
    fun delete(id: Long): Pair<Int, Todo>? {
        val todos = all()
        val i = todos.indexOfFirst { it.id == id }
        if (i < 0) return null
        save(todos.filterIndexed { j, _ -> j != i })
        return i to todos[i]
    }

    fun restore(index: Int, todo: Todo) {
        val todos = all().toMutableList()
        if (todos.any { it.id == todo.id }) return
        todos.add(index.coerceIn(0, todos.size), todo.copy(tagId = existingTagId(todo.tagId)))
        save(todos)
    }

    fun history(): List<Todo> = historyOn(all(), today())

    fun upcoming(): List<Todo> = upcomingOn(all(), today())

    fun clearHistory() {
        val t = today()
        save(all() - historyOn(all(), t).toSet())
    }

    var wakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE, true)
        set(v) = prefs.edit().putBoolean(KEY_WAKE, v).apply()

    var skipWhenEmpty: Boolean
        get() = prefs.getBoolean(KEY_SKIP_EMPTY, true)
        set(v) = prefs.edit().putBoolean(KEY_SKIP_EMPTY, v).apply()

    /** 是否已经引导过一次授权，只引导一次，之后不再打扰。 */
    var permPrompted: Boolean
        get() = prefs.getBoolean(KEY_PERM_PROMPTED, false)
        set(v) = prefs.edit().putBoolean(KEY_PERM_PROMPTED, v).apply()

    private fun save(todos: List<Todo>) {
        prefs.edit().putString(KEY_TODOS, encodeTodos(todos)).apply()
    }

    private fun encodeTags(tags: List<TaskTag>): String {
        val arr = JSONArray()
        tags.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color))
        }
        return arr.toString()
    }

    private fun encodeTodos(todos: List<Todo>): String {
        val arr = JSONArray()
        todos.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("text", it.text).put("done", it.done)
                    .put("repeat", it.repeat.name).put("weekdays", JSONArray(it.weekdays.sorted()))
                    .put("day", it.day).put("doneDay", it.doneDay).put("tagId", it.tagId)
            )
        }
        return arr.toString()
    }

    private fun parse(o: JSONObject): Todo {
        val id = o.getLong("id")
        val days = o.optJSONArray("weekdays")
        return Todo(
            id = id,
            text = o.getString("text"),
            repeat = runCatching { Repeat.valueOf(o.optString("repeat")) }.getOrDefault(Repeat.ONCE),
            weekdays = if (days == null) emptySet() else (0 until days.length()).map { days.getInt(it) }.toSet(),
            // 旧版本数据没有 day：id 就是创建时间，按它算出是哪一天
            day = if (o.has("day")) o.getLong("day") else dayOf(id),
            done = o.optBoolean("done"),
            doneDay = o.optLong("doneDay", -1),
            tagId = if (o.isNull("tagId")) null else o.getInt("tagId"),
        )
    }

    companion object {
        private val TAG_COLORS = listOf(
            0xFF2453A6.toInt(), 0xFF226546.toInt(), 0xFF8F431A.toInt(), 0xFF743C99.toInt(),
            0xFF9B3254.toInt(), 0xFF16666B.toInt(), 0xFF695518.toInt(), 0xFF97362E.toInt(),
        )
        private const val KEY_TAGS = "tags"
        private const val KEY_NEXT_TAG_ID = "next_tag_id"
        private const val KEY_TODOS = "todos"
        private const val KEY_WAKE = "wake_enabled"
        private const val KEY_SKIP_EMPTY = "skip_when_empty"
        private const val KEY_PERM_PROMPTED = "perm_prompted"

        fun today(): Long = LocalDate.now().toEpochDay()

        // 下面几个按「今天是哪天」给待办分类，不碰存储，方便单元测试

        /** 需要提醒的：今天该做还没做的，加上以前没完成的单次任务。 */
        fun pendingOn(todos: List<Todo>, t: Long): List<Todo> = todos.filter {
            (it.isDueOn(t) && !it.isDoneOn(t)) || (it.repeat == Repeat.ONCE && it.day < t && !it.done)
        }

        /** 历史任务 = 以前日子的单次任务（不管完成没有）。 */
        fun historyOn(todos: List<Todo>, t: Long): List<Todo> =
            todos.filter { it.repeat == Repeat.ONCE && it.day < t }

        /** 有未完成任务的日期优先，同一类日期按时间倒序。 */
        fun historyByDay(todos: List<Todo>, today: Long): List<Map.Entry<Long, List<Todo>>> =
            historyOn(todos, today).groupBy { it.day }.entries.sortedWith(
                compareBy<Map.Entry<Long, List<Todo>>> { (_, tasks) -> tasks.all { it.done } }
                    .thenByDescending { it.key }
            )

        /** 以后的任务 = 安排在今天以后的单次任务，到那天自动进入「未完成」。 */
        fun upcomingOn(todos: List<Todo>, t: Long): List<Todo> =
            todos.filter { it.repeat == Repeat.ONCE && it.day > t }

        private fun dayOf(millis: Long): Long =
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    }
}
